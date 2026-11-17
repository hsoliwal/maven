// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.File;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Optional JDK21 AST/attributed may-flow evidence; no source edits, LLM, or semantic proof. */
@Mojo(name = "machine-fingerprints", threadSafe = true, requiresProject = true)
public final class MachineFingerprintMojo extends AbstractMojo {
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
    @Parameter(property = "m3.smart.machineWorkers", defaultValue = "1")
    private int machineWorkers = 1;
    @Parameter(property = "m3.smart.attributeTypes", defaultValue = "false")
    private boolean attributeTypes;
    @Parameter(property = "m3.smart.analyzeFlow", defaultValue = "false")
    private boolean analyzeFlow;

    @Override public void execute() throws MojoExecutionException {
        if (baseDir == null || buildDir == null || sourceRoot == null || preparationReport == null
                || atomPatternManifest == null)
            throw new MojoExecutionException("machine-fingerprints requires canonical Java producer evidence");
        int status = MachineFingerprintMain.run(List.of("--project-dir", baseDir.toString(),
                "--build-dir", buildDir.toString(), "--source-root", sourceRoot.toString(),
                "--preparation-report", preparationReport.toString(),
                "--atom-pattern-manifest", atomPatternManifest.toString(),
                "--workers", Integer.toString(machineWorkers),
                "--attribute-types", Boolean.toString(attributeTypes),
                "--analyze-flow", Boolean.toString(analyzeFlow)).toArray(String[]::new), System.out, System.err);
        if (status != 0) throw new MojoExecutionException("M3 AST fingerprint generation refused (" + status + ")");
    }
}
