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
 * choice. ctest follows a configured cmake; make falls back to gmake and mingw32-make. On Windows sh is Git for
 * Windows' sh (on PATH, in its default folders or next to git.exe) before any bash, and never the WSL launcher
 * ({@code %SystemRoot%/System32/bash.exe}), which starts a Linux shell that cannot run Windows tools. A tool named in
 * the {@code tools} parameter or matched in toolchains.xml that does not exist fails: a pinned tool is never
 * silently replaced by one on PATH.
 */
public final class ToolResolver {
    private static final Map<String, List<String>> ALIASES = Map.of(
            "make", List.of("make", "gmake", "mingw32-make"));
    private static final List<String> GIT_SH = List.of(
            "C:/Program Files/Git/usr/bin/sh.exe", "C:/Program Files/Git/bin/sh.exe");

    private final Map<String, String> explicit;
    private final List<ToolchainsReader.Entry> toolchains;
    private final Map<String, String> requirements;
    private final Map<String, String> provisioned;
    private final Platform platform;
    private final Map<String, String> environment;

    /** A resolver over the given sources; {@code environment} supplies PATH, PATHEXT and SystemRoot. */
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

    /** Resolves each name; names not found are left out, a configured tool that does not exist fails. */
    public ToolSet resolveAll(Collection<String> names) {
        return ToolSet.of(names.stream().distinct().map(this::resolve).flatMap(Optional::stream).toList());
    }

    /** Resolves one logical tool name; a configured tool that does not exist fails. */
    public Optional<ToolSet.Tool> resolve(String name) {
        if (explicit.containsKey(name)) {
            return Optional.of(find(explicit.get(name)).map(p -> tool(name, p, ToolSet.Origin.TOOLS))
                    .orElseThrow(() -> missing(name, explicit.get(name), "the tools parameter")));
        }
        Optional<Path> fromToolchains = ToolchainsReader.executable(toolchains, name, requirements, platform);
        if (fromToolchains.isPresent()) {
            if (!Files.isRegularFile(fromToolchains.get())) {
                throw missing(name, fromToolchains.get().toString(), "toolchains.xml");
            }
            return Optional.of(tool(name, fromToolchains.get(), ToolSet.Origin.TOOLCHAINS));
        }
        if (provisioned.containsKey(name)) {
            return Optional.of(tool(name, Path.of(provisioned.get(name)), ToolSet.Origin.PROVISIONED));
        }
        return "ctest".equals(name) ? siblingOfCmake().or(() -> fromPath(name)) : fromPath(name);
    }

    /** A file on PATH, or the file itself when {@code value} names a path. */
    public Optional<Path> find(String value) {
        return locate(value).findFirst();
    }

    private Stream<Path> locate(String value) {
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
            Path path = Path.of(value);
            return Files.isRegularFile(path) ? Stream.of(canonical(path)) : Stream.empty();
        }
        return folders().flatMap(folder -> candidates(folder, value)).filter(this::runnable);
    }

    /**
     * On Windows, a configured file name that Windows reads differently from how it is spelled (trailing dots or
     * spaces, which Windows drops, or an 8.3 short name with {@code ~}) is replaced by the real name of the file it
     * opens, so the tool runs and is judged (batch or not) by that name. Other names stay as configured, so a tool
     * that looks at its own name (a link named after a compiler) keeps it.
     */
    private Path canonical(Path path) {
        String name = String.valueOf(path.getFileName());
        if (!platform.windows() || !(name.endsWith(".") || name.endsWith(" ") || name.contains("~"))) {
            return path;
        }
        return WindowsArgs.realName(path.toString()).map(path::resolveSibling).orElse(path);
    }

    private static IllegalStateException missing(String name, String value, String source) {
        return new IllegalStateException("configured tool " + name + "=" + value + " (from " + source + ") was not "
                + "found; a pinned tool is never replaced by one on PATH: fix the path or remove the entry");
    }

    private Optional<ToolSet.Tool> fromPath(String name) {
        if ("cc".equals(name) || "cxx".equals(name)) {
            return Optional.empty();
        }
        Optional<Path> found = "sh".equals(name) ? shell() : ALIASES.getOrDefault(name, List.of(name)).stream()
                .map(this::find).flatMap(Optional::stream).findFirst();
        return found.map(p -> tool(name, p, ToolSet.Origin.PATH));
    }

    private Optional<Path> shell() {
        if (!platform.windows()) {
            return Stream.concat(locate("sh"), locate("bash")).findFirst();
        }
        Stream<Path> git = Stream.concat(GIT_SH.stream().map(Path::of).filter(Files::isRegularFile),
                locate("git").flatMap(ToolResolver::gitShells));
        return Stream.of(locate("sh"), git, locate("bash")).flatMap(s -> s).filter(p -> !wslLauncher(p)).findFirst();
    }

    private static Stream<Path> gitShells(Path git) {
        Path folder = git.toAbsolutePath().getParent();
        Path root = folder == null ? null : folder.getParent();
        return root == null ? Stream.empty() : Stream.of(root.resolve("usr/bin/sh.exe"), root.resolve("bin/sh.exe"))
                .filter(Files::isRegularFile);
    }

    /** True for the WSL launcher: bash.exe or sh.exe in System32 or Sysnative, or a WindowsApps alias. */
    private boolean wslLauncher(Path candidate) {
        String file = WindowsPaths.mixed(candidate).toLowerCase(Locale.ROOT);
        String root = environment.getOrDefault("SystemRoot", "C:/Windows").replace('\\', '/').toLowerCase(Locale.ROOT);
        String folder = file.substring(0, Math.max(file.lastIndexOf('/'), 0));
        return folder.equals(root + "/system32") || folder.equals(root + "/sysnative") || file.contains("/windowsapps/");
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
