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
package org.apache.maven.plugins.nativebuild.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A digest of what a build step produced, written after every build step: the sorted path, size and modification
 * time of each object file, library and executable in the build tree. Test and install declare it as an input, so a
 * rebuild that relinks anything (after a configure or toolchain change) re-runs them, while a native no-op build
 * ({@code ninja: no work to do}) leaves it, and their content stamps, unchanged.
 */
public final class BuildDigest {
    /** The digest file in the build directory. */
    public static final String FILE = "build-products.digest";

    private static final Pattern PRODUCT =
            Pattern.compile("(?i).*\\.(o|obj|a|lib|so(\\.\\d+)*|dylib|dll|exe|pdb|wasm)$");
    private static final Set<String> SKIPPED = Set.of(".git", ".m3", ".svn", ".hg");

    private BuildDigest() {}

    /** Writes the digest of {@code root} (project-relative) into the build directory; true when it changed. */
    public static boolean write(Layout layout, String root) throws IOException {
        Path tree = layout.path(root);
        Set<Path> skipped = Stream.of(layout.build(), layout.stage(), layout.reports(), "target")
                .map(layout::path).filter(p -> !tree.startsWith(p)).collect(Collectors.toSet());
        return TaskFileWriter.write(layout.path(layout.inBuild(FILE)), render(tree, skipped));
    }

    /** The digest text of the products below {@code tree}, outside {@code skipped}. */
    static String render(Path tree, Set<Path> skipped) throws IOException {
        if (!Files.isDirectory(tree)) {
            return "";
        }
        boolean windows = Platform.current().windows();
        try (Stream<Path> walk = Files.walk(tree)) {
            return walk.filter(p -> skipped.stream().noneMatch(p::startsWith) && !hidden(tree, p))
                    .filter(p -> product(p, windows)).map(p -> line(tree, p)).sorted()
                    .collect(Collectors.joining());
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private static boolean product(Path file, boolean windows) {
        String name = file.getFileName().toString();
        return Files.isRegularFile(file) && (PRODUCT.matcher(name).matches()
                || (!windows && !name.contains(".") && Files.isExecutable(file)));
    }

    private static boolean hidden(Path tree, Path file) {
        return Stream.iterate(tree.relativize(file), p -> p != null, Path::getParent)
                .anyMatch(p -> SKIPPED.contains(String.valueOf(p.getFileName())));
    }

    private static String line(Path tree, Path file) {
        try {
            BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
            return WindowsPaths.relative(tree, file) + " " + a.size() + " " + a.lastModifiedTime().toMillis() + "\n";
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
