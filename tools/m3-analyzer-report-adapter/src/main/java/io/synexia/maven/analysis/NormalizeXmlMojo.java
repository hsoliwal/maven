/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.analysis;

import java.io.File;
import java.nio.file.Path;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Opt-in Maven producer adapter for original analyzer XML files. This goal
 * does not execute an analyzer, bind itself to a lifecycle phase, validate
 * analyzer coverage, skip another Mojo or publish a Maven session receipt.
 */
@Mojo(name = "normalize-xml", threadSafe = true, requiresProject = true)
public final class NormalizeXmlMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.basedir}", required = true, readonly = true)
    private File projectRoot;

    @Parameter(property = "m3.analysis.xml", required = true)
    private File sourceXml;

    @Parameter(property = "m3.analysis.format", defaultValue = "CHECKSTYLE")
    private String format;

    @Parameter(property = "m3.analysis.originalStatus", defaultValue = "PARTIAL")
    private String originalStatus;

    @Parameter(property = "m3.analysis.report", defaultValue = "${project.build.directory}/m3-analysis.tsv")
    private File normalizedReport;

    @Parameter(property = "m3.analysis.sha256", defaultValue = "${project.build.directory}/m3-analysis.sha256")
    private File digestSidecar;

    private static Path absolute(Path root, File file) {
        Path provided = file.toPath();
        return (provided.isAbsolute() ? provided : root.resolve(provided)).normalize();
    }

    @Override
    public void execute() throws MojoExecutionException {
        try {
            if (sourceXml == null || projectRoot == null || normalizedReport == null || digestSidecar == null) {
                throw new IllegalArgumentException("original XML and project-local output locations required");
            }
            Path root = projectRoot.toPath().toAbsolutePath().normalize();
            NormalizeCli.main(new String[] {
                format, originalStatus, root.toString(), absolute(root, sourceXml).toString(),
                absolute(root, normalizedReport).toString(), absolute(root, digestSidecar).toString()
            });
            getLog().info("M3 original analyzer XML normalized with explicit status " + originalStatus);
        } catch (Exception invalid) {
            throw new MojoExecutionException("Original analyzer XML normalization refused: " + invalid.getMessage(),
                    invalid);
        }
    }
}
