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

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import java.net.URI;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Where a compilation unit's atoms are filed: its source file, and its path relative to its source root with
 * {@code /} separators, the path {@link AtomPlanes#scan} gives the same file when it scans that root.
 */
final class UnitPaths {
    private UnitPaths() { }

    /** The unit's source file; empty for a source that is not a file (for example one held in memory). */
    static Optional<Path> file(CompilationUnitTree unit) {
        URI uri = unit.getSourceFile().toUri();
        if (!"file".equalsIgnoreCase(uri.getScheme())) return Optional.empty();
        return Optional.of(Path.of(uri).toAbsolutePath().normalize());
    }

    /** The file relative to the longest given root that holds it, else to its package root. */
    static String relative(Path file, CompilationUnitTree unit, List<Path> roots) {
        Path root = root(file, roots).orElseGet(() -> packageRoot(file, unit.getPackageName()));
        return root.relativize(file).toString().replace('\\', '/');
    }

    /** The longest given root that holds the file. */
    static Optional<Path> root(Path file, List<Path> roots) {
        return roots.stream().filter(file::startsWith).max(Comparator.comparingInt(Path::getNameCount));
    }

    /**
     * The directory above the file's package directories: {@code src/main/java} for
     * {@code src/main/java/p/q/A.java} in package {@code p.q}. When the directories do not spell the package, or the
     * unit has none, the file's own directory.
     */
    static Path packageRoot(Path file, ExpressionTree packageName) {
        Path directory = file.getParent();
        if (packageName == null || directory == null) return directory;
        String[] parts = packageName.toString().split("\\.");
        Path root = directory;
        for (int i = parts.length - 1; i >= 0; i--) {
            if (root == null || root.getFileName() == null || !root.getFileName().toString().equals(parts[i])) {
                return directory;
            }
            root = root.getParent();
        }
        return root == null ? directory : root;
    }
}
