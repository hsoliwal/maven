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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * One owner per native build descriptor. The polyglot plugin's Mavenizer maps CMakeLists.txt, meson.build and
 * makefiles into rows of the module's {@code m3-build.tsv} (or a manifest configured for its run and plan goals,
 * see {@link #manifests}), which the polyglot plugin runs; this plugin
 * builds the same descriptors from its own goals. Both in one module would configure, build and test twice with
 * different directories and flags, so a module whose {@code m3-build.tsv} has a task running a native build tool is
 * refused here: remove those rows (keep the module's other tasks) or drop this plugin from the module.
 */
public final class OwnerCheck {
    /** The root manifest the polyglot lifecycle participant reads. */
    public static final String MANIFEST = "m3-build.tsv";

    private static final Set<String> NATIVE_TOOLS = Set.of("cmake", "ctest", "meson", "make", "gmake", "mingw32-make",
            "nmake", "ninja", "autoreconf", "configure");

    private OwnerCheck() {}

    /** The rows of {@code <basedir>/m3-build.tsv} that run native build tools, as {@code task (tool)}. */
    public static List<String> competing(Path basedir) throws IOException {
        return competing(List.of(basedir.resolve(MANIFEST)));
    }

    /** The rows of the given manifests that run native build tools, as {@code task (tool) in <manifest>}. */
    public static List<String> competing(List<Path> manifests) throws IOException {
        String where = manifests.size() > 1 ? " in " : "";
        try {
            return manifests.stream().filter(Files::isRegularFile).flatMap(m -> rows(m).stream()
                    .map(row -> row + (where.isEmpty() ? "" : where + m))).toList();
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private static List<String> rows(Path manifest) {
        try {
            return Files.readAllLines(manifest, StandardCharsets.UTF_8).stream().map(l -> l.split("\t"))
                    .filter(f -> f.length >= 3 && "task".equals(f[0]))
                    .filter(f -> NATIVE_TOOLS.contains(WindowsPaths.stem(f[2]).toLowerCase(Locale.ROOT)))
                    .map(f -> f[1] + " (" + f[2] + ")").toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * The manifests the polyglot plugin may read for this module, found the way its run and plan goals find theirs:
     * each {@code <manifest>} configured for the plugin or one of its executions (relative to the project
     * directory), else the {@code m3.polyglot.manifest} property, else {@code <basedir>/m3-build.tsv}. The default is
     * always included, because the Mavenizer writes it.
     */
    public static List<Path> manifests(Path basedir, List<String> configured, String property) {
        Stream<String> values = Stream.concat(Stream.of(MANIFEST), Stream.concat(configured.stream(),
                Stream.ofNullable(property)));
        return values.filter(v -> v != null && !v.isBlank())
                .map(v -> basedir.resolve(v.strip()).toAbsolutePath().normalize()).distinct().toList();
    }

    /** Fails when {@code m3-build.tsv} also builds the native descriptors. */
    public static void require(Path basedir) throws IOException {
        require(basedir, List.of(basedir.resolve(MANIFEST)));
    }

    /** Fails when one of {@code manifests} also builds the native descriptors. */
    public static void require(Path basedir, List<Path> manifests) throws IOException {
        List<String> rows = competing(manifests);
        if (!rows.isEmpty()) {
            throw new IllegalStateException(manifests + " also build this module's native descriptors with " + rows
                    + " (polyglot route); one owner per descriptor: remove those rows, or remove "
                    + "maven-native-build-plugin from this module (" + basedir + ")");
        }
    }
}
