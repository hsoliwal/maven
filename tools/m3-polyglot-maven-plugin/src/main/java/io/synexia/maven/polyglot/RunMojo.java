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

    /** Reuse a success only after input, context, prerequisite and output contents are verified. */
    @Parameter(property = "m3.polyglot.incremental", defaultValue = "false")
    private boolean incremental;

    /** Embedded M3IndexDB by default; file/native, SQLite/H2, and explicit ext-* providers remain available. */
    @Parameter(property = "m3.polyglot.incrementalState", defaultValue = "embedded")
    private String incrementalState;

    /** Explicit cache directory for restoring and storing declared, relocatable task outputs. */
    @Parameter(property = "m3.polyglot.buildCache")
    private File buildCache;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("M3 polyglot target execution skipped by explicit configuration");
            return;
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 43_200) {
            throw new MojoExecutionException("m3.polyglot.timeoutSeconds must be between 1 and 43200");
        }
        try {
            var graph = TaskManifest.load(manifest.toPath());
            TaskRunner.run(graph, projectDirectory.toPath(), target, dryRun,
                    Duration.ofSeconds(timeoutSeconds), approveRewriteApply, incremental, incrementalState,
                    buildCache == null ? null : buildCache.toPath(),
                    message -> getLog().info(message));
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("M3 polyglot tool execution interrupted", interruption);
        } catch (Exception failure) {
            throw new MojoExecutionException("M3 polyglot execution failed: " + failure.getMessage(), failure);
        }
    }
}
