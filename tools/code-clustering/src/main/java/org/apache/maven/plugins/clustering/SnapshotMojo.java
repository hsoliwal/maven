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
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Writes the atom table of the project's sources to {@code baseline}: {@code mvn code-clustering:snapshot} before a
 * refactoring, {@code mvn code-clustering:compare} after it. Keep the baseline outside {@code target} when it
 * must survive {@code mvn clean}.
 */
@Mojo(name = "snapshot", threadSafe = true)
public final class SnapshotMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.compileSourceRoots}", readonly = true, required = true)
    private List<String> sourceRoots;

    /** Source directories to atomize instead of the compile source roots; comma-separated as a property. */
    @Parameter(property = "clustering.sourceDirectories")
    private List<File> sourceDirectories;

    /** Where the baseline atom table is written. */
    @Parameter(property = "clustering.baseline", defaultValue = "${project.basedir}/.code-clustering/ATOM_HASHES.tsv")
    private File baseline;

    @Override public void execute() throws MojoExecutionException {
        try {
            List<AtomPlanes.Atom> atoms = AtomPlanes.scan(roots(sourceDirectories, sourceRoots));
            PlaneTables.writeAtoms(baseline.toPath(), atoms);
            getLog().info("baseline: " + atoms.size() + " atoms in " + baseline);
        } catch (IOException e) {
            throw new MojoExecutionException("code clustering snapshot failed: " + e.getMessage(), e);
        }
    }

    static List<Path> roots(List<File> sourceDirectories, List<String> sourceRoots) {
        return sourceDirectories != null && !sourceDirectories.isEmpty()
                ? sourceDirectories.stream().map(File::toPath).collect(Collectors.toList())
                : sourceRoots.stream().map(Path::of).collect(Collectors.toList());
    }
}
