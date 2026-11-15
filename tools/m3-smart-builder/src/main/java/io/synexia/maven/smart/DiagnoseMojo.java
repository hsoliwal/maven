package io.synexia.maven.smart;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Read-only diagnostics of an explicitly supplied Maven log and source directory. */
@Mojo(name = "diagnose", threadSafe = true, requiresProject = true)
public final class DiagnoseMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File baseDir;
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDir;
    @Parameter(property = "m3.smart.logFile", required = true)
    private File logFile;
    @Parameter(property = "m3.smart.sourceRoot", required = true)
    private File sourceRoot;
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
            if (logFile == null || sourceRoot == null) throw new IllegalArgumentException("explicit log and source root required");
            if (llmBackend != null && candidateFile != null) throw new IllegalArgumentException("choose exactly one advisory input");
            Path pom = baseDir.toPath().resolve("pom.xml");
            BuildLogFacts log = BuildLogFacts.inspect(logFile.toPath());
            SourceInventory source = SourceInventory.inspect(baseDir.toPath(), sourceRoot.toPath());
            String candidate = "";
            if (candidateFile != null) {
                if (!Files.isRegularFile(candidateFile.toPath()) || Files.isSymbolicLink(candidateFile.toPath())
                        || Files.size(candidateFile.toPath()) > 32768) throw new IllegalArgumentException("invalid candidate file");
                candidate = Files.readString(candidateFile.toPath(), StandardCharsets.UTF_8);
            } else if (llmBackend != null) {
                File selected = "jni".equals(llmBackend) ? jniLib : llamaBin;
                McpAdviser.Config config = new McpAdviser.Config(
                        chromeLlmJar == null ? null : chromeLlmJar.toPath(), llmBackend,
                        selected == null ? null : selected.toPath(),
                        modelPath == null ? null : modelPath.toPath(), llmTimeoutSeconds);
                candidate = McpAdviser.propose(config, DiagnoseEngine.prompt(PomFacts.inspect(pom), log, source));
            }
            String report = DiagnoseEngine.diagnose(pom, log, source, candidate);
            Path directory = SafeBuildOutput.directory(baseDir.toPath(), buildDir.toPath());
            Path saved = SafeBuildOutput.write(directory, "diagnose.json", report);
            getLog().info("M3 review-only diagnosis: " + saved);
        } catch (Exception failure) {
            throw new MojoExecutionException("M3 diagnosis failed: " + failure.getClass().getSimpleName(), failure);
        }
    }
}
