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

/** Opt-in coding-agent recipe nomination; no source mutation or build lifecycle binding. */
@Mojo(name = "code-agent", threadSafe = true, requiresProject = true)
public final class CodeAgentMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File baseDir;
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDir;
    @Parameter(property = "m3.smart.sourceRoot", required = true)
    private File sourceRoot;
    @Parameter(property = "m3.smart.preparationReport", required = true)
    private File preparationReport;
    @Parameter(property = "m3.smart.atomPatternManifest", required = true)
    private File atomPatternManifest;
    @Parameter(property = "m3.smart.recipeCatalog", required = true)
    private File recipeCatalog;
    @Parameter(property = "m3.smart.machineEvidence")
    private File machineEvidence;
    @Parameter(property = "m3.smart.operatorCatalog")
    private File operatorCatalog;
    @Parameter(property = "m3.smart.requireMachineEvidence", defaultValue = "false")
    private boolean requireMachineEvidence;
    @Parameter(property = "m3.smart.codeAgentCandidate")
    private File candidateFile;
    @Parameter(property = "m3.smart.llmBackend", defaultValue = "none")
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
    private int llmTimeoutSeconds = 120;

    @Override public void execute() throws MojoExecutionException {
        if (baseDir == null || buildDir == null || sourceRoot == null || preparationReport == null
                || atomPatternManifest == null || recipeCatalog == null)
            throw new MojoExecutionException("code-agent requires source, preparation, atom and recipe-catalog evidence");
        List<String> args = new ArrayList<>(List.of("--project-dir", baseDir.toString(),
                "--build-dir", buildDir.toString(), "--source-root", sourceRoot.toString(),
                "--preparation-report", preparationReport.toString(),
                "--atom-pattern-manifest", atomPatternManifest.toString(),
                "--recipe-catalog", recipeCatalog.toString(),
                "--backend", llmBackend == null ? "none" : llmBackend,
                "--timeout-seconds", Integer.toString(llmTimeoutSeconds)));
        add(args, "--candidate-file", candidateFile);
        add(args, "--machine-evidence", machineEvidence);
        add(args, "--operator-catalog", operatorCatalog);
        if (requireMachineEvidence) args.addAll(List.of("--require-machine-evidence", "true"));
        add(args, "--chrome-llm-jar", chromeLlmJar);
        add(args, "--llama-bin", llamaBin);
        add(args, "--jni-lib", jniLib);
        add(args, "--model", modelPath);
        int status = CodeAgentMain.run(args.toArray(String[]::new), System.out, System.err);
        if (status != 0) throw new MojoExecutionException("M3 code-agent refused (status " + status + ")");
    }

    private static void add(List<String> args, String option, File file) {
        if (file != null) args.addAll(List.of(option, file.toString()));
    }
}
