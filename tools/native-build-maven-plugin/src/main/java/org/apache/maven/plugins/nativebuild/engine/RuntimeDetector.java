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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Windows C runtime family a configured build really uses, read from what the build system detected rather than
 * from the plugin's parameters: CMake's {@code CMakeFiles/<version>/CMakeCCompiler.cmake} (compiler id and simulated
 * id: MSVC, clang-cl and clang targeting MSVC are msvc; GNU and MinGW clang are mingw), Meson's
 * {@code meson-info/intro-compilers.json}, the {@code CC} of an Autotools build's Makefile, or the configured compiler
 * of a Make or Ninja build. Empty before configure and on other systems.
 */
public final class RuntimeDetector {
    private static final Pattern CMAKE_ID = Pattern.compile("set\\(CMAKE_(?:C|CXX)_(COMPILER|SIMULATE)_ID \"(\\w*)\"\\)");
    private static final Pattern MAKE_CC = Pattern.compile("(?m)^CC\\s*=\\s*(\\S+)");

    private RuntimeDetector() {}

    /** The runtime ({@link Platform#MSVC} or {@link Platform#MINGW}) of a configured Windows build, if known. */
    public static Optional<String> runtime(PlanContext c, StepPlanner planner) throws IOException {
        if (!c.platform().windows()) {
            return Optional.empty();
        }
        Path build = c.layout().path(planner.buildDirectory(c));
        return switch (c.system()) {
            case CMAKE, CMAKE_PRESETS -> cmake(build);
            case MESON -> meson(build.resolve("meson-info/intro-compilers.json"));
            case AUTOTOOLS, AUTOTOOLS_BOOTSTRAP -> makefileCc(c.layout().path(c.layout().build()).resolve("Makefile"));
            case MAKE, NINJA -> Optional.of(compilerRuntime(c.tools().configured("cc")
                    .orElse(c.environment().getOrDefault("CC", "gcc"))));
            case NOTHING -> Optional.empty();
        };
    }

    /** msvc for cl and clang-cl, else mingw. */
    static String compilerRuntime(String compiler) {
        String stem = WindowsPaths.stem(compiler).toLowerCase(Locale.ROOT);
        return "cl".equals(stem) || "clang-cl".equals(stem) ? Platform.MSVC : Platform.MINGW;
    }

    static Optional<String> cmake(Path build) throws IOException {
        Path files = build.resolve("CMakeFiles");
        if (!Files.isDirectory(files)) {
            return Optional.empty();
        }
        try (Stream<Path> walk = Files.walk(files, 2)) {
            return walk.filter(p -> p.getFileName().toString().matches("CMake(C|CXX)Compiler\\.cmake")).sorted()
                    .findFirst().map(RuntimeDetector::read).map(RuntimeDetector::cmakeRuntime);
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    static String cmakeRuntime(String compilerFile) {
        Matcher m = CMAKE_ID.matcher(compilerFile);
        Map<String, String> ids = new java.util.HashMap<>();
        Stream.generate(() -> m.find()).takeWhile(Boolean::booleanValue).forEach(f -> ids.put(m.group(1), m.group(2)));
        boolean msvc = "MSVC".equals(ids.get("COMPILER")) || "MSVC".equals(ids.get("SIMULATE"));
        return msvc ? Platform.MSVC : Platform.MINGW;
    }

    static Optional<String> meson(Path intro) {
        if (!Files.isRegularFile(intro)) {
            return Optional.empty();
        }
        Object root = JsonText.parse(read(intro));
        Object c = path(root, "host", "c");
        if (!((c != null ? c : path(root, "host", "cpp")) instanceof Map<?, ?> fields)) {
            return Optional.empty();
        }
        String id = String.valueOf(fields.get("id"));
        String linker = String.valueOf(fields.get("linker_id"));
        boolean msvcLinker = "link".equals(linker) || linker.endsWith("-link") || "xilink".equals(linker);
        return Optional.of("msvc".equals(id) || id.endsWith("-cl") || msvcLinker ? Platform.MSVC : Platform.MINGW);
    }

    private static Optional<String> makefileCc(Path makefile) {
        if (!Files.isRegularFile(makefile)) {
            return Optional.empty();
        }
        Matcher m = MAKE_CC.matcher(read(makefile));
        return m.find() ? Optional.of(compilerRuntime(m.group(1))) : Optional.empty();
    }

    private static Object path(Object root, String... keys) {
        return Stream.of(keys).<Object>reduce(root, (node, key) -> node instanceof Map<?, ?> map ? map.get(key) : null,
                (first, second) -> second);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
