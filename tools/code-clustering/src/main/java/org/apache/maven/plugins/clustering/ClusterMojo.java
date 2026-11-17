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
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Atoms, hash planes and clusters of the project's main sources: {@code mvn code-clustering:cluster}. Writes
 * ATOM_HASHES.tsv, CLUSTERS.tsv, PLANE_SUMMARY.tsv, FILE_PAIRS.tsv and FILE_CLUSTERS.tsv to {@code outputDirectory}.
 * With {@code against} set to the
 * ATOM_HASHES.tsv of another source set (a library that may already own this code), also writes JOIN.tsv and
 * COVERAGE.tsv. Reports only; it never changes a source file and never fails the build on what it finds.
 */
@Mojo(name = "cluster", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public final class ClusterMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.compileSourceRoots}", readonly = true, required = true)
    private List<String> sourceRoots;

    @Parameter(property = "clustering.outputDirectory", defaultValue = "${project.build.directory}/code-clustering")
    private File outputDirectory;

    /** Smallest atom, in logic tokens, that takes part in a cluster; smaller atoms stay in the table. */
    @Parameter(property = "clustering.minTokens", defaultValue = "12")
    private int minTokens;

    /** SimHash Hamming radius of a near cluster or a NEAR match. */
    @Parameter(property = "clustering.radius", defaultValue = "3")
    private int radius;

    /** Jaccard similarity of two files' method logic sets from which they join one file family. */
    @Parameter(property = "clustering.fileThreshold", defaultValue = "0.5")
    private double fileThreshold;

    /** ATOM_HASHES.tsv of another source set to match this project's atoms against; optional. */
    @Parameter(property = "clustering.against")
    private File against;

    @Override public void execute() throws MojoExecutionException {
        try {
            List<Path> roots = sourceRoots.stream().map(Path::of).collect(Collectors.toList());
            Path out = outputDirectory.toPath();
            getLog().info(CodeClustering.cluster(out, roots, minTokens, radius, fileThreshold));
            if (against != null) {
                getLog().info(PlaneJoin.join(PlaneTables.readAtoms(out.resolve("ATOM_HASHES.tsv")),
                        PlaneTables.readAtoms(against.toPath()), out, radius));
            }
        } catch (IOException e) {
            throw new MojoExecutionException("code clustering failed: " + e.getMessage(), e);
        }
    }
}
