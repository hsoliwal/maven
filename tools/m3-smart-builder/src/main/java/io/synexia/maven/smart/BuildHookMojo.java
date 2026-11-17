// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Explicit lifecycle binding for the same independently runnable CI hook. */
@Mojo(name = "build-hook", threadSafe = true, requiresProject = true)
public final class BuildHookMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File baseDir;
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDir;
    @Parameter(property = "m3.smart.phase", required = true)
    private String phase;
    @Parameter(property = "m3.smart.buildOutcome")
    private String buildOutcome;
    @Parameter(property = "m3.smart.logFile")
    private File logFile;
    @Parameter(property = "m3.smart.sourceRoot")
    private File sourceRoot;
    @Parameter(property = "m3.smart.atomPatternManifest")
    private File atomPatternManifest;
    @Parameter(property = "m3.smart.requireAtomPattern", defaultValue = "false")
    private boolean requireAtomPattern;
    @Parameter(property = "m3.smart.preparationRequired", defaultValue = "false")
    private boolean preparationRequired;
    @Parameter(property = "m3.smart.preparationReport")
    private File preparationReport;
    @Parameter(property = "m3.smart.llmBackend", defaultValue = "none")
    private String llmBackend;
    @Parameter(property = "m3.smart.chromeLlmJar")
    private File chromeLlmJar;
    @Parameter(property = "m3.smart.chromeBin")
    private File chromeBin;
    @Parameter(property = "m3.smart.chromeProfile")
    private File chromeProfile;
    @Parameter(property = "m3.smart.chromeHeadless", defaultValue = "true")
    private boolean chromeHeadless;
    @Parameter(property = "m3.smart.chromeDownload", defaultValue = "false")
    private boolean chromeDownload;
    @Parameter(property = "m3.smart.llamaBin")
    private File llamaBin;
    @Parameter(property = "m3.smart.jniLib")
    private File jniLib;
    @Parameter(property = "m3.smart.modelPath")
    private File modelPath;
    @Parameter(property = "m3.smart.llmTimeoutSeconds", defaultValue = "120")
    private int llmTimeoutSeconds;
    @Parameter(property = "m3.smart.bridgeUrl")
    private String bridgeUrl;
    @Parameter(property = "m3.smart.bridgeTokenFile")
    private File bridgeTokenFile;
    @Parameter(property = "m3.smart.summarizer")
    private String summarizer;
    @Parameter(property = "m3.smart.summarizerTimeoutSeconds")
    private Integer summarizerTimeoutSeconds;
    /** False for a hook bound into a POM's lifecycle: advice must never change the build result. */
    @Parameter(property = "m3.smart.failOnError", defaultValue = "true")
    private boolean failOnError = true;  // the same default when the mojo is built without Maven's injection

    @Override public void execute() throws MojoExecutionException {
        if (baseDir == null || buildDir == null || phase == null)
            throw new MojoExecutionException("build-hook requires project, build directory and phase");
        List<String> args = new ArrayList<>(List.of("--project-dir", baseDir.toString(),
                "--build-dir", buildDir.toString(), "--phase", phase,
                "--backend", llmBackend == null ? "none" : llmBackend,
                "--timeout-seconds", Integer.toString(llmTimeoutSeconds)));
        if (buildOutcome != null) args.addAll(List.of("--build-outcome", buildOutcome));
        add(args, "--log-file", logFile);
        add(args, "--source-root", sourceRoot);
        add(args, "--atom-pattern-manifest", atomPatternManifest);
        args.addAll(List.of("--require-atom-pattern", Boolean.toString(requireAtomPattern)));
        args.addAll(List.of("--preparation-required", Boolean.toString(preparationRequired)));
        add(args, "--preparation-report", preparationReport);
        add(args, "--chrome-llm-jar", chromeLlmJar);
        add(args, "--chrome-binary", chromeBin);
        add(args, "--chrome-profile", chromeProfile);
        add(args, "--llama-bin", llamaBin);
        add(args, "--jni-lib", jniLib);
        add(args, "--model", modelPath);
        if ("chrome".equals(llmBackend)) args.addAll(List.of("--headless", Boolean.toString(chromeHeadless),
                "--allow-download", Boolean.toString(chromeDownload)));
        if ("bridge".equals(llmBackend) && bridgeUrl != null && !bridgeUrl.isBlank()) args.addAll(List.of("--bridge-url", bridgeUrl));
        if ("bridge".equals(llmBackend)) add(args, "--bridge-token-file", bridgeTokenFile);
        if (summarizer != null && !summarizer.isBlank()) {
            args.addAll(List.of("--summarizer", summarizer));
            if (summarizerTimeoutSeconds != null) args.addAll(List.of("--summarizer-timeout-seconds", summarizerTimeoutSeconds.toString()));
        }
        int status = BuildHookMain.run(args.toArray(String[]::new), System.out, System.err);
        if (status == 0) return;
        if (failOnError) throw new MojoExecutionException("Chrome AI build hook failed with status " + status);
        getLog().warn("Chrome AI build hook did not complete (status " + status + "); the build result is unchanged");
    }

    private static void add(List<String> args, String name, File value) {
        if (value != null) args.addAll(List.of(name, value.toString()));
    }
}
