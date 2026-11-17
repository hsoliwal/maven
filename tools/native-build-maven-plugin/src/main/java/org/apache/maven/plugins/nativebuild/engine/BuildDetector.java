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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds the build system of one directory (not its sub-directories). A file that generates another wins over the
 * generated one: CMake and Meson over a build.ninja or Makefile they may have left behind, a configure script over
 * the Makefile it writes. File names are compared exactly as the directory lists them, also on Windows.
 */
public final class BuildDetector {
    private record Rule(BuildSystem system, List<String> needs, List<String> oneOf) {}

    private static final List<Rule> RULES = List.of(
            new Rule(BuildSystem.CMAKE_PRESETS, List.of("CMakeLists.txt"), List.of("CMakePresets.json")),
            new Rule(BuildSystem.CMAKE, List.of(), List.of("CMakeLists.txt")),
            new Rule(BuildSystem.MESON, List.of(), List.of("meson.build")),
            new Rule(BuildSystem.AUTOTOOLS, List.of(), List.of("configure")),
            new Rule(BuildSystem.AUTOTOOLS_BOOTSTRAP, List.of(), List.of("configure.ac", "configure.in")),
            new Rule(BuildSystem.NINJA, List.of(), List.of("build.ninja")),
            new Rule(BuildSystem.MAKE, List.of(), List.of("GNUmakefile", "makefile", "Makefile")));

    private BuildDetector() {}

    /**
     * The build system of {@code directory}; {@code explicit} (a {@link BuildSystem#id()}) overrides the precedence
     * rules. An explicit {@code autotools} still becomes the bootstrap variant when the configure script is missing.
     */
    public static Detection detect(Path directory, String explicit) throws IOException {
        Set<String> names = fileNames(directory);
        if (explicit != null && !explicit.isBlank()) {
            return explicit(names, explicit);
        }
        return RULES.stream().map(rule -> match(rule, names, "precedence")).flatMap(Optional::stream).findFirst()
                .orElse(new Detection(BuildSystem.NOTHING, "", "no CMakeLists.txt, meson.build, configure, "
                        + "configure.ac, configure.in, build.ninja, GNUmakefile, makefile or Makefile in " + directory));
    }

    private static Detection explicit(Set<String> names, String explicit) {
        BuildSystem wanted = BuildSystem.parse(explicit).orElseThrow(() -> new IllegalArgumentException(
                "unknown buildSystem '" + explicit + "'; use one of " + Arrays.stream(BuildSystem.values())
                        .filter(s -> s != BuildSystem.NOTHING).map(BuildSystem::id).collect(Collectors.joining(", "))));
        BuildSystem system = wanted == BuildSystem.AUTOTOOLS && !names.contains("configure")
                ? BuildSystem.AUTOTOOLS_BOOTSTRAP : wanted;
        Rule rule = RULES.stream().filter(r -> r.system() == system).findFirst().orElseThrow();
        String descriptor = rule.oneOf().stream().filter(names::contains).findFirst().orElse("");
        return new Detection(system, descriptor, "explicit buildSystem=" + explicit.trim());
    }

    private static Optional<Detection> match(Rule rule, Set<String> names, String reason) {
        if (!names.containsAll(rule.needs())) {
            return Optional.empty();
        }
        return rule.oneOf().stream().filter(names::contains).findFirst()
                .map(found -> new Detection(rule.system(), found, reason));
    }

    private static Set<String> fileNames(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("native source directory does not exist: " + directory);
        }
        try (Stream<Path> list = Files.list(directory)) {
            return list.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        }
    }
}
