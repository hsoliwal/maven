/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.File;
import java.time.Duration;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Explicit opt-in external tool graph execution, with no implicit lifecycle binding. */
@Mojo(name = "run", threadSafe = false, requiresProject = true)
public final class RunMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File projectDirectory;
    @Parameter(property = "m3.polyglot.manifest", defaultValue = "${project.basedir}/m3-build.tsv")
    private File manifest;
    @Parameter(property = "m3.polyglot.target", defaultValue = "all")
    private String target;
    @Parameter(property = "m3.polyglot.skip", defaultValue = "false")
    private boolean skip;
    @Parameter(property = "m3.polyglot.dryRun", defaultValue = "false")
    private boolean dryRun;
    @Parameter(property = "m3.polyglot.timeoutSeconds", defaultValue = "900")
    private int timeoutSeconds;

    @Parameter(property = "m3.polyglot.approveRewriteApply", defaultValue = "false")
    private boolean approveRewriteApply;

    /**
     * Skip a task whose declared inputs and definition are unchanged since its last success (content stamps);
     * with the indexdb backend, reuse M3DB task state only after verifying declared inputs, context and output
     * content.
     */
    @Parameter(property = "m3.polyglot.incremental", defaultValue = "false")
    private boolean incremental;

    /**
     * "legacy" (unchanged default), "file" (one image per module), "m3db" (that image as one M3IndexDB
     * artifact), or "indexdb" (one M3IndexDB receipt archive per module, {@code .m3/module.m3db}).
     */
    @Parameter(property = "m3.polyglot.incrementalState", defaultValue = "legacy")
    private String incrementalState;

    /** Store declared-input and verified-output receipts in one file per project module (the indexdb backend). */
    @Parameter(property = "m3.polyglot.m3db", defaultValue = "false")
    private boolean moduleDb;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("M3 polyglot target execution skipped by explicit configuration");
            return;
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 43_200) {
            throw new MojoExecutionException("m3.polyglot.timeoutSeconds must be between 1 and 43200");
        }
        String backend = backend();
        try {
            var graph = TaskManifest.load(manifest.toPath());
            TaskRunner.run(graph, projectDirectory.toPath(), target, dryRun,
                    Duration.ofSeconds(timeoutSeconds), approveRewriteApply, incremental, backend,
                    message -> getLog().info(message));
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("M3 polyglot tool execution interrupted", interruption);
        } catch (Exception failure) {
            throw new MojoExecutionException("M3 polyglot execution failed: " + failure.getMessage(), failure);
        }
    }

    /** m3.polyglot.m3db=true selects the indexdb backend; it cannot contradict an explicit other backend. */
    private String backend() throws MojoExecutionException {
        if (!moduleDb) return incrementalState;
        if (!"legacy".equals(incrementalState) && !"indexdb".equals(incrementalState)) {
            throw new MojoExecutionException("m3.polyglot.m3db=true conflicts with m3.polyglot.incrementalState="
                    + incrementalState);
        }
        if (!incremental) throw new MojoExecutionException("m3.polyglot.m3db requires m3.polyglot.incremental=true");
        return "indexdb";
    }
}
