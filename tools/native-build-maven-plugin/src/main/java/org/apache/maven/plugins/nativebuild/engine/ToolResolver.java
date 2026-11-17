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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds tools in this order: the {@code tools} parameter (a path, or a name looked up on PATH), toolchains.xml, a
 * provisioned CMake, then PATH (with the PATHEXT extensions on Windows, as the polyglot runner does). Compilers
 * ({@code cc}, {@code cxx}) are never taken from PATH: an unconfigured compiler is left to the build system's own
 * choice. ctest follows a configured cmake; make falls back to gmake and mingw32-make; sh on Windows falls back to
 * Git for Windows.
 */
public final class ToolResolver {
    private static final Map<String, List<String>> ALIASES = Map.of(
            "make", List.of("make", "gmake", "mingw32-make"), "sh", List.of("sh", "bash"));
    private static final List<String> GIT_SH = List.of(
            "C:/Program Files/Git/usr/bin/sh.exe", "C:/Program Files/Git/bin/sh.exe");

    private final Map<String, String> explicit;
    private final List<ToolchainsReader.Entry> toolchains;
    private final Map<String, String> requirements;
    private final Map<String, String> provisioned;
    private final Platform platform;
    private final Map<String, String> environment;

    /** A resolver over the given sources; {@code environment} supplies PATH and PATHEXT. */
    public ToolResolver(Map<String, String> explicit, List<ToolchainsReader.Entry> toolchains,
            Map<String, String> requirements, Map<String, String> provisioned, Platform platform,
            Map<String, String> environment) {
        this.explicit = Map.copyOf(explicit);
        this.toolchains = List.copyOf(toolchains);
        this.requirements = Map.copyOf(requirements);
        this.provisioned = Map.copyOf(provisioned);
        this.platform = platform;
        this.environment = PlanContext.environment(environment);
    }

    /** Resolves each name; names not found are left out of the set. */
    public ToolSet resolveAll(Collection<String> names) {
        return ToolSet.of(names.stream().distinct().map(this::resolve).flatMap(Optional::stream).toList());
    }

    /** Resolves one logical tool name. */
    public Optional<ToolSet.Tool> resolve(String name) {
        if (explicit.containsKey(name)) {
            return find(explicit.get(name)).map(p -> tool(name, p, ToolSet.Origin.TOOLS));
        }
        Optional<ToolSet.Tool> fromToolchains = ToolchainsReader.executable(toolchains, name, requirements, platform)
                .map(p -> tool(name, p, ToolSet.Origin.TOOLCHAINS));
        if (fromToolchains.isPresent()) {
            return fromToolchains;
        }
        if (provisioned.containsKey(name)) {
            return Optional.of(tool(name, Path.of(provisioned.get(name)), ToolSet.Origin.PROVISIONED));
        }
        return "ctest".equals(name) ? siblingOfCmake().or(() -> fromPath(name)) : fromPath(name);
    }

    /** A file on PATH, or the file itself when {@code value} names a path. */
    public Optional<Path> find(String value) {
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
            Path path = Path.of(value);
            return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
        }
        return folders().flatMap(folder -> candidates(folder, value)).filter(this::runnable).findFirst();
    }

    private Optional<ToolSet.Tool> fromPath(String name) {
        if ("cc".equals(name) || "cxx".equals(name)) {
            return Optional.empty();
        }
        Optional<Path> found = ALIASES.getOrDefault(name, List.of(name)).stream().map(this::find)
                .flatMap(Optional::stream).findFirst();
        if (found.isEmpty() && "sh".equals(name) && platform.windows()) {
            found = GIT_SH.stream().map(Path::of).filter(Files::isRegularFile).findFirst();
        }
        return found.map(p -> tool(name, p, ToolSet.Origin.PATH));
    }

    private Optional<ToolSet.Tool> siblingOfCmake() {
        return resolve("cmake").filter(cmake -> cmake.origin() != ToolSet.Origin.PATH).flatMap(cmake -> {
            Path folder = Path.of(cmake.path()).getParent();
            return candidates(folder, "ctest").filter(this::runnable).findFirst()
                    .map(p -> tool("ctest", p, cmake.origin()));
        });
    }

    private Stream<Path> folders() {
        String path = environment.getOrDefault("PATH", "");
        return Arrays.stream(path.split(File.pathSeparator)).filter(e -> !e.isBlank()).map(ToolResolver::folder)
                .flatMap(Optional::stream);
    }

    private static Optional<Path> folder(String entry) {
        try {
            return Optional.of(Path.of(entry.replace("\"", "")));
        } catch (InvalidPathException unusable) {
            return Optional.empty();
        }
    }

    private Stream<Path> candidates(Path folder, String name) {
        if (!platform.windows()) {
            return Stream.of(folder.resolve(name));
        }
        String extensions = environment.getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD");
        return Stream.concat(Stream.of(name).filter(n -> n.contains(".")), Arrays.stream(extensions.split(";"))
                .filter(e -> !e.isBlank()).map(e -> name + e.toLowerCase(Locale.ROOT))).map(folder::resolve);
    }

    private boolean runnable(Path candidate) {
        return Files.isRegularFile(candidate) && (platform.windows() || Files.isExecutable(candidate));
    }

    private static ToolSet.Tool tool(String name, Path path, ToolSet.Origin origin) {
        return new ToolSet.Tool(name, WindowsPaths.mixed(path), origin);
    }
}
