package io.synexia.maven.smart;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Emits a deterministic review plan. Only explicitly configured providers are invoked. */
@Mojo(name = "plan", threadSafe = true, requiresProject = true)
public final class PlanMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File baseDir;
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDir;
    @Parameter(property = "m3.smart.manifest")
    private File manifest;
    @Parameter(property = "m3.smart.profile", defaultValue = "none")
    private String profile;
    @Parameter(property = "m3.smart.candidateFile")
    private File candidateFile;
    @Parameter(property = "m3.smart.llmBackend")
    private String llmBackend;
    @Parameter(property = "m3.smart.chromeLlmJar")
    private File chromeLlmJar;
    @Parameter(property = "m3.smart.llamaBin")
    private File llamaBin;
    @Parameter(property = "m3.smart.jniLib")
    private File jniLib;
    @Parameter(property = "m3.smart.modelPath")
    private File modelPath;
    @Parameter(property = "m3.smart.llmTimeoutSeconds", defaultValue = "120")
    private int llmTimeoutSeconds;

    @Override public void execute() throws MojoExecutionException {
        try {
            if (llmBackend != null && candidateFile != null) throw new IllegalArgumentException("choose exactly one advisory input");
            ArtifactManifest m = manifest == null ? ArtifactManifest.empty() : ArtifactManifest.load(manifest.toPath());
            Path pom = baseDir.toPath().resolve("pom.xml");
            String candidate = "";
            if (candidateFile != null) {
                if (!Files.isRegularFile(candidateFile.toPath()) || Files.size(candidateFile.toPath()) > 32768)
                    throw new IllegalArgumentException("candidate input missing or over 32 KiB");
                candidate = Files.readString(candidateFile.toPath(), StandardCharsets.UTF_8);
            } else if (llmBackend != null) {
                File bin = "jni".equals(llmBackend) ? jniLib : llamaBin;
                McpAdviser.Config config = new McpAdviser.Config(
                        chromeLlmJar == null ? null : chromeLlmJar.toPath(),
                        llmBackend, bin == null ? null : bin.toPath(),
                        modelPath == null ? null : modelPath.toPath(), llmTimeoutSeconds);
                candidate = McpAdviser.propose(config, PlanEngine.prompt(pom, m, profile));
            }
            String json = PlanEngine.plan(pom, m, profile, candidate);
            Path root = baseDir.toPath().toAbsolutePath().normalize();
            Path output = buildDir.toPath().toAbsolutePath().normalize().resolve("m3-smart");
            if (!output.startsWith(root) || output.equals(root))
                throw new IllegalArgumentException("Maven build output must remain inside project checkout");
            Files.createDirectories(output);
            Path part = Files.createTempFile(output, "plan-", ".part");
            try {
                Files.writeString(part, json, StandardCharsets.UTF_8);
                Files.move(part, output.resolve("plan.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(part); }
            getLog().info("M3 review-only plan: " + output.resolve("plan.json"));
        } catch (Exception failure) {
            throw new MojoExecutionException("M3 planning failed: " + failure.getClass().getSimpleName(), failure);
        }
    }
}
