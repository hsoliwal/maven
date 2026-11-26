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

/** Opt-in operator-approved OpenRewrite replay and Maven verification on a staged project. */
@Mojo(name = "code-agent-verify", threadSafe = false, requiresProject = true)
public final class CodeAgentVerifyMojo extends AbstractMojo {
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
    @Parameter(property = "m3.smart.operatorProofRegistry")
    private File operatorProofRegistry;
    @Parameter(property = "m3.smart.operatorProofDir")
    private File operatorProofDir;
    @Parameter(property = "m3.smart.operatorProofPublicKey")
    private File operatorProofPublicKey;
    @Parameter(property = "m3.smart.operatorProofPublicKeySha256")
    private String operatorProofPublicKeySha256;
    @Parameter(property = "m3.smart.approvedRecipeId", required = true)
    private String approvedRecipeId;
    @Parameter(property = "m3.smart.approveRecipeExecution", defaultValue = "false")
    private boolean approveRecipeExecution;
    @Parameter(property = "m3.smart.approvedMavenBin", required = true)
    private File mavenExecutable;
    @Parameter(property = "m3.smart.approvedMavenRepository", required = true)
    private File mavenRepository;
    @Parameter(property = "m3.smart.recipeCoordinates", required = true)
    private String recipeCoordinates;
    @Parameter(property = "m3.smart.rewritePluginSha256", required = true)
    private String rewritePluginSha256;
    @Parameter(property = "m3.smart.recipeTimeoutSeconds", defaultValue = "600")
    private int timeoutSeconds = 600;

    @Override public void execute() throws MojoExecutionException {
        if (baseDir == null || buildDir == null || sourceRoot == null || preparationReport == null
                || atomPatternManifest == null || recipeCatalog == null || approvedRecipeId == null
                || mavenExecutable == null || mavenRepository == null || recipeCoordinates == null
                || rewritePluginSha256 == null)
            throw new MojoExecutionException("M3 verification requires source, pinned artifacts and operator approval");
        List<String> args = new ArrayList<>(List.of("--project-dir", baseDir.toString(),
                "--build-dir", buildDir.toString(), "--source-root", sourceRoot.toString(),
                "--preparation-report", preparationReport.toString(),
                "--atom-pattern-manifest", atomPatternManifest.toString(),
                "--recipe-catalog", recipeCatalog.toString(),
                "--approved-recipe-id", approvedRecipeId,
                "--approve-execution", Boolean.toString(approveRecipeExecution),
                "--maven-executable", mavenExecutable.toString(),
                "--maven-repository", mavenRepository.toString(),
                "--recipe-coordinates", recipeCoordinates,
                "--plugin-sha256", rewritePluginSha256,
                "--timeout-seconds", Integer.toString(timeoutSeconds)));
        if (machineEvidence != null) args.addAll(List.of("--machine-evidence", machineEvidence.toString()));
        if (operatorCatalog != null) args.addAll(List.of("--operator-catalog", operatorCatalog.toString()));
        if (operatorProofRegistry != null) args.addAll(List.of("--operator-proof-registry", operatorProofRegistry.toString()));
        if (operatorProofDir != null) args.addAll(List.of("--operator-proof-dir", operatorProofDir.toString()));
        if (operatorProofPublicKey != null) args.addAll(List.of("--operator-proof-key", operatorProofPublicKey.toString()));
        if (operatorProofPublicKeySha256 != null)
            args.addAll(List.of("--operator-proof-key-sha256", operatorProofPublicKeySha256));
        int status = CodeAgentVerifyMain.run(args.toArray(String[]::new), System.out, System.err);
        if (status != 0) throw new MojoExecutionException("M3 recipe verification refused (status " + status + ")");
    }
}
