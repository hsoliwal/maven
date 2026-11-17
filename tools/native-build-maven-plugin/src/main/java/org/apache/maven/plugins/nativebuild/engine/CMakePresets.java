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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The binary directory of a configure preset, so the compile database, test output and stage of a preset build are
 * found. Only {@code ${sourceDir}}, {@code ${sourceDirName}}, {@code ${sourceParentDir}} and {@code ${presetName}}
 * are expanded; a preset that inherits its binaryDir or uses other macros yields nothing, and the plugin then
 * falls back to its own build directory for what it reads.
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

    private static Optional<String> declared(Object root, String preset) {
        if (!(root instanceof Map<?, ?> map) || !(map.get("configurePresets") instanceof List<?> presets)) {
            return Optional.empty();
        }
        return presets.stream().filter(p -> p instanceof Map<?, ?> m && preset.equals(m.get("name")))
                .map(p -> ((Map<?, ?>) p).get("binaryDir")).filter(String.class::isInstance).map(String.class::cast)
                .findFirst();
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
