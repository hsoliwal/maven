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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Planned steps survive a round trip through the polyglot manifest loader unchanged, isolated and chained. */
final class TaskFileContract {
    private TaskFileContract() {}

    static void run(Checks c) throws Exception {
        List<NativeStep> steps = List.of(
                new NativeStep(NativePhase.CONFIGURE, "C:/Program Files/CMake/bin/cmake.exe", ".", List.of("-S", ".",
                        "-DNAME=a b;rm -rf / && echo \"$(id)\" `x` 'q'"), List.of("CMakeLists.txt"),
                        List.of("target/native/x/CMakeCache.txt")),
                new NativeStep(NativePhase.BUILD, "cmake", ".", List.of("--build", "target/native/x"), List.of(), List.of()),
                new NativeStep(NativePhase.TEST, "ctest", ".", List.of("--test-dir", "target/native/x"), List.of("src"),
                        List.of("target/native/x/ctest-junit.xml")),
                new NativeStep(NativePhase.INSTALL, "cmake", ".", List.of("--install", "target/native/x"), List.of("src"),
                        List.of("target/native/stage/x")));
        isolated(c, steps);
        chained(c, steps);
        rejected(c);
    }

    private static void isolated(Checks c, List<NativeStep> steps) throws Exception {
        Path file = c.dir("taskfile", Map.of()).resolve("tasks.tsv");
        TaskFileWriter.write(file, TaskFileWriter.render(steps, false));
        List<PolyglotEngine.TaskView> loaded = PolyglotEngine.load(file);
        Map<String, PolyglotEngine.TaskView> byName = loaded.stream()
                .collect(Collectors.toMap(PolyglotEngine.TaskView::name, t -> t));
        c.equal("isolated: four tasks, no aggregates", 4, loaded.size());
        steps.forEach(s -> c.equal("round trip " + s.name(), List.of(s.tool(), s.workingDir(), s.arguments(), s.inputs(),
                s.outputs(), List.of()), view(byName.get(s.name()))));
        c.check("rewriting identical content is a no-op",
                !TaskFileWriter.write(file, TaskFileWriter.render(steps, false)));
    }

    private static void chained(Checks c, List<NativeStep> steps) throws Exception {
        Path file = c.dir("taskfile-chained", Map.of()).resolve("m3-build.tsv");
        TaskFileWriter.write(file, TaskFileWriter.render(steps, true));
        Map<String, List<String>> deps = PolyglotEngine.load(file).stream()
                .collect(Collectors.toMap(PolyglotEngine.TaskView::name, PolyglotEngine.TaskView::prerequisites));
        c.equal("chained prerequisites", Map.of("native-configure", List.of(), "native-build",
                List.of("native-configure"), "native-test", List.of("native-build"), "native-install",
                List.of("native-test"), "compile", List.of("native-build"), "test", List.of("native-test"),
                "package", List.of("native-install")), deps);
    }

    private static void rejected(Checks c) {
        NativeStep tab = new NativeStep(NativePhase.BUILD, "make", ".", List.of("a\tb"), List.of(), List.of());
        c.fails("tab in an argument", IllegalArgumentException.class,
                () -> TaskFileWriter.render(List.of(tab), false));
        NativeStep empty = new NativeStep(NativePhase.BUILD, "make", ".", List.of(""), List.of(), List.of());
        c.fails("empty argument", IllegalArgumentException.class, () -> TaskFileWriter.render(List.of(empty), false));
        NativeStep newline = new NativeStep(NativePhase.BUILD, "make", ".", List.of("x\nrm -rf /"), List.of(),
                List.of());
        c.fails("line break in an argument", IllegalArgumentException.class,
                () -> TaskFileWriter.render(List.of(newline), false));
    }

    private static List<Object> view(PolyglotEngine.TaskView t) {
        return List.of(t.tool(), t.workingDir(), t.arguments(), t.inputs(), t.outputs(), t.prerequisites());
    }
}
