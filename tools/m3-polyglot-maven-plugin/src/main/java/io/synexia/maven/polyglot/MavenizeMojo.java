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

/** Mavenizer: writes pom.xml and m3-build.tsv for a folder built by other tools; runs without a Maven project. */
@Mojo(name = "mavenize", requiresProject = false, threadSafe = true)
public final class MavenizeMojo extends AbstractMojo {
    @Parameter(property = "m3.polyglot.mavenize.dir", defaultValue = "${basedir}")
    private File directory;
    @Parameter(property = "m3.polyglot.mavenize.groupId", defaultValue = "mavenized")
    private String groupId;
    @Parameter(property = "m3.polyglot.mavenize.version", defaultValue = "0.0.0-SNAPSHOT")
    private String version;
    @Parameter(property = "m3.polyglot.dryRun", defaultValue = "false")
    private boolean dryRun;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            for (String line : Mavenizer.mavenize(directory.toPath(), groupId, version, !dryRun)) getLog().info(line);
        } catch (IOException failure) {
            throw new MojoExecutionException("Mavenizer failed: " + failure.getMessage(), failure);
        }
    }
}
