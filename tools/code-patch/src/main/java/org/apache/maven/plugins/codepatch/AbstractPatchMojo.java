/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.codepatch;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * The parameters every goal shares. A refusal ({@link PatchException}) fails the build; an I/O error is an execution
 * error.
 */
public abstract class AbstractPatchMojo extends AbstractMojo {
    /** Patch files, or directories whose {@code *.patch} and {@code *.diff} files apply in name order; comma-separated as a property. */
    @Parameter(property = "patch.patches")
    private List<File> patches;

    /** The directory the patch paths are relative to. */
    @Parameter(property = "patch.baseDirectory", defaultValue = "${project.basedir}")
    private File baseDirectory;

    /** Where plans, staged images, comparisons, receipts and the lock file go. */
    @Parameter(property = "patch.outputDirectory", defaultValue = "${project.build.directory}/patch")
    private File outputDirectory;

    /** The directory Maven runs in: the base directory when there is no pom.xml. */
    @Parameter(defaultValue = "${session.executionRootDirectory}", readonly = true, required = true)
    private File executionRootDirectory;

    @Override public final void execute() throws MojoExecutionException, MojoFailureException {
        try {
            Path base = (baseDirectory != null ? baseDirectory : executionRootDirectory).toPath();
            run(base, out(base));
        } catch (PatchException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            throw new MojoExecutionException("code patch failed: " + e, e);
        }
    }

    protected abstract void run(Path base, Path out) throws IOException, PatchException, MojoFailureException;

    /** Without a pom.xml the default output directory keeps an unresolved expression: then it is target/patch. */
    private Path out(Path base) {
        if (outputDirectory == null || outputDirectory.getPath().contains("${")) return base.resolve("target").resolve("patch");
        return outputDirectory.toPath();
    }

    protected final List<Path> patches() throws PatchException {
        if (patches == null || patches.isEmpty()) throw new PatchException("no patches: set -Dpatch.patches=<file or directory>,...");
        return patches.stream().map(File::toPath).collect(Collectors.toList());
    }

    protected final void logPlan(Plan plan) {
        getLog().info(plan.summary());
        plan.rows().forEach(r -> getLog().info("  " + r.operation() + " " + r.path()
                + (Digests.ABSENT.equals(r.fromPath()) ? "" : " (from " + r.fromPath() + ")") + ", " + r.hunks() + " hunks"));
    }
}
