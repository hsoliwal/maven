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
        add(args, "--chrome-llm-jar", chromeLlmJar);
        add(args, "--chrome-binary", chromeBin);
        add(args, "--chrome-profile", chromeProfile);
        add(args, "--llama-bin", llamaBin);
        add(args, "--jni-lib", jniLib);
        add(args, "--model", modelPath);
        if ("chrome".equals(llmBackend)) args.addAll(List.of("--headless", Boolean.toString(chromeHeadless),
                "--allow-download", Boolean.toString(chromeDownload)));
        int status = BuildHookMain.run(args.toArray(String[]::new), System.out, System.err);
        if (status != 0) throw new MojoExecutionException("Chrome AI build hook failed with status " + status);
    }

    private static void add(List<String> args, String name, File value) {
        if (value != null) args.addAll(List.of(name, value.toString()));
    }
}
