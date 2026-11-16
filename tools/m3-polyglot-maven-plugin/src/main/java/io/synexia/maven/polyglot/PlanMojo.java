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

/** Read-only polyglot target plan; Ant/Make DAG semantics without tool execution. */
@Mojo(name = "plan", threadSafe = true, requiresProject = true)
public final class PlanMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File projectDirectory;
    @Parameter(property = "m3.polyglot.manifest", defaultValue = "${project.basedir}/m3-build.tsv")
    private File manifest;
    @Parameter(property = "m3.polyglot.target", defaultValue = "all")
    private String target;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            var graph = TaskManifest.load(manifest.toPath());
            TaskRunner.run(graph, projectDirectory.toPath(), target, true, Duration.ofSeconds(1),
                    message -> getLog().info(message));
        } catch (Exception error) {
            throw new MojoExecutionException("M3 polyglot plan rejected: " + error.getMessage(), error);
        }
    }
}
