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
package org.apache.maven.recipes.javac;

import javax.tools.JavaFileObject;

import java.nio.file.Path;
import java.util.Optional;

/** Source paths of javac file objects, as the census and the trees name them. */
final class SourcePaths {
    private SourcePaths() {}

    /** The file on disk behind a file object; empty for in-memory sources. */
    static Optional<Path> ofFile(JavaFileObject file) {
        return "file".equalsIgnoreCase(file.toUri().getScheme())
                ? Optional.of(Path.of(file.toUri()).normalize())
                : Optional.empty();
    }

    /** The path under {@code root} when the file is there, else its absolute path or, in memory, its name. */
    static Path relative(JavaFileObject file, Path root) {
        return ofFile(file)
                .map(path -> path.startsWith(root) ? root.relativize(path) : path)
                .orElseGet(() -> Path.of(file.getName()));
    }

    /** The census key: the relative path with {@code /} separators. */
    static String key(JavaFileObject file, Path root) {
        return relative(file, root).toString().replace('\\', '/');
    }

    /**
     * Whether the file lies under a directory, such as the build directory: rewrite-maven-plugin skips every source
     * root under the project's build directory ({@code MavenMojoProjectParser.filterGeneratedSourceRoots}), which holds
     * the generated sources of annotation processors and code generators.
     */
    static boolean isUnder(JavaFileObject file, Path dir) {
        return ofFile(file).map(path -> path.toAbsolutePath().startsWith(dir)).orElse(false);
    }
}
