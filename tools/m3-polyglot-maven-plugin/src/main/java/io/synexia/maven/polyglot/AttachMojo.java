/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

/**
 * Attaches the NuGet packages a native step wrote ({@code dotnet pack --output target/nuget}) to the project, so
 * {@code install} and {@code deploy} carry them with the POM: type {@code nupkg} or {@code snupkg}, classifier the
 * NuGet package id. A missing folder attaches nothing. Two packages with the same id and type (an old version left
 * in the folder) fail the goal instead of attaching one of them by chance.
 */
@Mojo(name = "attach", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true, requiresProject = true)
public final class AttachMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Component
    private MavenProjectHelper helper;

    /** The folder holding the packages. */
    @Parameter(property = "m3.polyglot.attach.directory", defaultValue = "${project.basedir}/target/nuget")
    private File directory;

    @Parameter(property = "m3.polyglot.attach.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("M3 polyglot attach skipped by explicit configuration");
            return;
        }
        try {
            List<NugetPackages.Package> packages = NugetPackages.unique(NugetPackages.list(directory.toPath()));
            if (packages.isEmpty()) getLog().info("M3 polyglot attach: no NuGet package in " + directory);
            for (NugetPackages.Package nuget : packages) {
                helper.attachArtifact(project, nuget.type(), nuget.id(), nuget.file().toFile());
                getLog().info("ATTACH " + nuget.type() + ":" + nuget.id() + " (NuGet " + nuget.version() + ") "
                        + nuget.file().getFileName());
            }
        } catch (IOException failure) {
            throw new MojoExecutionException("M3 polyglot attach failed: " + failure.getMessage(), failure);
        }
    }
}
