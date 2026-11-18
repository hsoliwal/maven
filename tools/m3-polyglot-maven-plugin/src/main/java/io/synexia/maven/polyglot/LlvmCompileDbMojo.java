/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.File;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Explicit, side-effect-bounded export for clangd, Eclipse CDT and the
 * existing Clang AST atomizer. Does not invoke native tools or alter a build.
 */
@Mojo(name = "llvm-compile-db", threadSafe = true, requiresProject = true)
public final class LlvmCompileDbMojo extends AbstractMojo {
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
            var output = LlvmCompilationDatabase.publish(graph, projectDirectory.toPath(), target);
            getLog().info("M3 LLVM compilation database: " + output);
        } catch (Exception invalid) {
            throw new MojoExecutionException(
                    "M3 LLVM compilation database refused: " + invalid.getMessage(), invalid);
        }
    }
}
