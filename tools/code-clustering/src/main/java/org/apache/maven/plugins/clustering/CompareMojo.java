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
package org.apache.maven.plugins.clustering;

import java.io.File;
import java.io.IOException;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Compares the project's atoms with the baseline written by {@code snapshot} and writes COMPARE.tsv (see
 * {@link PlaneCompare}). With {@code failOn} empty it only reports. As a refactoring gate, use
 * {@code -Dclustering.failOn=CHANGED,LITERALS_CHANGED,ADDED,REMOVED}: then only the states that keep logic
 * (UNCHANGED, REFORMATTED, MOVED) and receipted changes (ACCEPTED) pass.
 */
@Mojo(name = "compare", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public final class CompareMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.compileSourceRoots}", readonly = true, required = true)
    private List<String> sourceRoots;

    /** Source directories to atomize instead of the compile source roots; comma-separated as a property. */
    @Parameter(property = "clustering.sourceDirectories")
    private List<File> sourceDirectories;

    @Parameter(property = "clustering.baseline", defaultValue = "${project.basedir}/.code-clustering/ATOM_HASHES.tsv")
    private File baseline;

    @Parameter(property = "clustering.outputDirectory", defaultValue = "${project.build.directory}/code-clustering")
    private File outputDirectory;

    /** {@code path#symbol} lines of changed atoms whose new behaviour is proven another way; optional. */
    @Parameter(property = "clustering.receipts")
    private File receipts;

    /** Comma-separated states that fail the build; empty reports only. */
    @Parameter(property = "clustering.failOn", defaultValue = "")
    private String failOn;

    @Override public void execute() throws MojoExecutionException, MojoFailureException {
        PlaneCompare.Result result;
        try {
            if (!baseline.isFile()) throw new MojoExecutionException("no baseline at " + baseline + "; run code-clustering:snapshot first");
            result = PlaneCompare.compare(PlaneTables.readAtoms(baseline.toPath()),
                    AtomPlanes.scan(SnapshotMojo.roots(sourceDirectories, sourceRoots)),
                    PlaneCompare.readReceipts(receipts == null ? null : receipts.toPath()), PlaneCompare.states(failOn),
                    outputDirectory.toPath());
        } catch (IOException | IllegalArgumentException e) {
            throw new MojoExecutionException("code clustering compare failed: " + e.getMessage(), e);
        }
        getLog().info(result.summary());
        if (!result.failing().isEmpty()) {
            result.failing().stream().limit(20).forEach(getLog()::error);
            throw new MojoFailureException(result.failing().size() + " atoms in a failing state (" + failOn + "); see "
                    + outputDirectory + "/COMPARE.tsv");
        }
    }
}
