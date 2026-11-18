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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What {@code native-build:clean} removes besides {@code target}: the native tool's own clean for in-place builds
 * ({@code make clean} when the makefile declares it, {@code ninja -t clean}), a CMake preset binaryDir outside
 * {@code target} (only when it holds a CMakeCache.txt), the files autoreconf created, the compile database or
 * {@code .clangd} the plugin published (only while unchanged since), the plugin's polyglot stamps and its records.
 *
 * @param command the native clean command, empty for none
 * @param environment its environment additions: those of the make steps ({@link Steps#makeEnvironment}), so
 *     {@code make clean} finds rm through the same PATH prefix (Git's usr/bin on Windows) as the build
 * @param workingDir its project-relative working directory
 * @param trees project-relative folders deleted with everything below them
 * @param files project-relative files, and folders deleted only when empty, deepest first
 */
public record CleanPlan(List<String> command, Map<String, String> environment, String workingDir, List<String> trees,
        List<String> files) {
    private static final List<String> PUBLISHED = List.of("compile_commands.json", ".clangd");
    private static final List<String> FOLDERS = List.of(GeneratedFiles.RECORDS, PolyglotEngine.STAMPS, ".m3");

    /** The plan for one build. */
    public static CleanPlan of(PlanContext c, StepPlanner planner) throws IOException {
        Layout l = c.layout();
        List<String> ninjaState = c.system() == BuildSystem.NINJA ? List.of(Layout.join(l.source(), ".ninja_log"),
                Layout.join(l.source(), ".ninja_deps")) : List.of();
        List<String> files = Steps.list(GeneratedFiles.recorded(l).stream().sorted(Comparator.reverseOrder()).toList(),
                ninjaState, published(l), stamps(l), GeneratedFiles.BOOTSTRAP, FOLDERS);
        Map<String, String> environment = c.system() == BuildSystem.MAKE ? Steps.makeEnvironment(c) : Map.of();
        return new CleanPlan(nativeClean(c), environment, c.system() == BuildSystem.MAKE ? l.source() : ".",
                presetTree(c, planner).stream().toList(), Steps.existing(l, files.toArray(String[]::new)));
    }

    private static List<String> nativeClean(PlanContext c) throws IOException {
        if (c.system() == BuildSystem.NINJA && c.tools().has("ninja")) {
            return List.of(c.tool("ninja"), "-C", c.layout().source(), "-t", "clean");
        }
        Path makefile = c.layout().path(Layout.join(c.layout().source(), MakePlanner.makefile(c)));
        boolean declared = c.system() == BuildSystem.MAKE && Files.isRegularFile(makefile)
                && !MakePlanner.declared(Files.readString(makefile), "clean").isEmpty();
        return declared && c.tools().has("make") ? Steps.list(c.tool("make"), "-f", MakePlanner.makefile(c), "clean",
                Steps.makeShell(c)) : List.of();
    }

    private static Optional<String> presetTree(PlanContext c, StepPlanner planner) throws IOException {
        if (c.system() != BuildSystem.CMAKE_PRESETS || c.options().preset().isEmpty()) {
            return Optional.empty();
        }
        Layout l = c.layout();
        String tree = planner.buildDirectory(c);
        Path path = l.path(tree);
        Path target = l.basedir().resolve("target");
        boolean safe = FileTrees.strictlyBelow(path, l.basedir()) && !path.startsWith(target)
                && !l.path(l.source()).startsWith(path) && Files.isRegularFile(path.resolve("CMakeCache.txt"));
        return safe ? Optional.of(tree) : Optional.empty();
    }

    private static List<String> published(Layout l) throws IOException {
        return Steps.list(PUBLISHED.stream().filter(name -> owned(l, name))
                .flatMap(name -> Stream.of(name, Layout.join(GeneratedFiles.RECORDS, name + ".sha256"))).toList());
    }

    private static boolean owned(Layout l, String name) {
        try {
            Path file = l.path(name);
            Path record = l.path(Layout.join(GeneratedFiles.RECORDS, name + ".sha256"));
            return Files.isRegularFile(record) && (!Files.exists(file) || Files.readString(record).strip()
                    .equals(CompileCommandsPublisher.sha256(Files.readAllBytes(file))));
        } catch (IOException unreadable) {
            return false;
        }
    }

    private static List<String> stamps(Layout l) throws IOException {
        Path folder = l.path(PolyglotEngine.STAMPS);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(folder)) {
            return list.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("native-") && n.endsWith(".sha256"))
                    .sorted().map(n -> Layout.join(PolyglotEngine.STAMPS, n)).toList();
        }
    }
}
