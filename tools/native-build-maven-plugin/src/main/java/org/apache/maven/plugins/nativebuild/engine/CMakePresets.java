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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The binary directory of a configure preset (its own, or the first one along {@code inherits}), so the compile
 * database, test output and stage of a preset build are found, and whether build and test presets of that name
 * exist. Only {@code ${sourceDir}}, {@code ${sourceDirName}}, {@code ${sourceParentDir}} and {@code ${presetName}}
 * (the requested preset's name) are expanded; a binaryDir with other macros yields nothing.
 */
final class CMakePresets {
    private CMakePresets() {}

    /** Project-relative binary directory of {@code preset}, when it is declared plainly and inside the project. */
    static Optional<String> binaryDir(Layout layout, String preset) throws IOException {
        Path file = layout.path(Layout.join(layout.source(), "CMakePresets.json"));
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Object root = JsonText.parse(Files.readString(file, StandardCharsets.UTF_8));
        return declared(root, preset).map(raw -> expand(raw, layout.path(layout.source()), preset))
                .filter(value -> !value.contains("${")).flatMap(value -> inside(layout, value));
    }

    /**
     * True when CMakePresets.json or CMakeUserPresets.json declares a preset {@code name} of {@code kind}
     * ({@code buildPresets} or {@code testPresets}). Many projects declare only configure presets.
     */
    static boolean declares(Layout layout, String kind, String name) throws IOException {
        try {
            return Stream.of("CMakePresets.json", "CMakeUserPresets.json")
                    .map(file -> layout.path(Layout.join(layout.source(), file))).filter(Files::isRegularFile)
                    .map(CMakePresets::read).anyMatch(root -> named(root, kind, name));
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private static Object read(Path file) {
        try {
            return JsonText.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static boolean named(Object root, String kind, String name) {
        return root instanceof Map<?, ?> map && map.get(kind) instanceof List<?> presets
                && presets.stream().anyMatch(p -> p instanceof Map<?, ?> m && name.equals(m.get("name")));
    }

    private static Optional<String> declared(Object root, String preset) {
        return declared(root, preset, 0);
    }

    /** The preset's binaryDir, else the first one its {@code inherits} presets declare (at most 16 levels). */
    private static Optional<String> declared(Object root, String preset, int depth) {
        if (depth > 16 || !(root instanceof Map<?, ?> map) || !(map.get("configurePresets") instanceof List<?> all)) {
            return Optional.empty();
        }
        Optional<Map<?, ?>> found = all.stream().filter(p -> p instanceof Map<?, ?> m && preset.equals(m.get("name")))
                .<Map<?, ?>>map(p -> (Map<?, ?>) p).findFirst();
        if (found.isEmpty() || found.get().get("binaryDir") instanceof String) {
            return found.map(p -> p.get("binaryDir")).filter(String.class::isInstance).map(String.class::cast);
        }
        Object inherits = found.get().get("inherits");
        List<?> parents = inherits instanceof List<?> list ? list : inherits == null ? List.of() : List.of(inherits);
        return parents.stream().map(String::valueOf).map(parent -> declared(root, parent, depth + 1))
                .flatMap(Optional::stream).findFirst();
    }

    private static String expand(String raw, Path source, String preset) {
        Path parent = source.getParent() == null ? source : source.getParent();
        return raw.replace("${sourceDir}", WindowsPaths.mixed(source))
                .replace("${sourceParentDir}", WindowsPaths.mixed(parent))
                .replace("${sourceDirName}", String.valueOf(source.getFileName()))
                .replace("${presetName}", preset);
    }

    private static Optional<String> inside(Layout layout, String value) {
        Path absolute = layout.path(layout.source()).resolve(value).normalize();
        return absolute.startsWith(layout.basedir())
                ? Optional.of(WindowsPaths.relative(layout.basedir(), absolute)) : Optional.empty();
    }
}
