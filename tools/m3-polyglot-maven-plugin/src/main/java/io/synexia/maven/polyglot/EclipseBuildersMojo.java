/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.File;
import java.io.IOException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Explicit read-only inventory of Eclipse language builder adapters; no lifecycle binding. */
@Mojo(name = "eclipse-builders", requiresProject = false, threadSafe = true)
public final class EclipseBuildersMojo extends AbstractMojo {
    @Parameter(property = "m3.polyglot.eclipse.dir", defaultValue = "${basedir}")
    private File directory;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            getLog().info(M3EclipseBuilderRegistry.inspect(directory.toPath()).toJson());
        } catch (IOException failure) {
            throw new MojoExecutionException(
                    "Cannot inspect Eclipse builder owners: " + failure.getMessage(), failure);
        }
    }
}
