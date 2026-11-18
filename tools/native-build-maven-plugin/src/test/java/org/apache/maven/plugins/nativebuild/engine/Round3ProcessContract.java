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

import static org.apache.maven.plugins.nativebuild.engine.ReviewProcessContract.classes;
import static org.apache.maven.plugins.nativebuild.engine.ReviewProcessContract.java;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Round-3 findings with real processes and links: an incremental step that fails loses its stamp, the stamp is gone
 * before the tool starts and later steps' stamps go with it (HIGH); a batch tool spelled with a trailing dot, space or
 * stream suffix is still a batch tool (MEDIUM); deleting a tree never follows a junction or symbolic link, a link in
 * the stage that leaves it is refused, and the clean goal skips recorded paths that leave the project (MEDIUM).
 */
final class Round3ProcessContract {
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    private Round3ProcessContract() {}

    static void run(Checks c) throws Exception {
        failedStepStamp(c);
        dependentStamps(c);
        unstampedStep(c);
        Round3LinkContract.run(c);
    }

    private static void failedStepStamp(Checks c) throws Exception {
        Path ws = c.dir("r3-failed-stamp", Map.of("in.txt", "v1\n", "mode.txt", "ok\n"));
        NativeStep step = step(ws, NativePhase.CONFIGURE, "cfg", "in.txt");
        StepExecutor executor = executor(ws, List.of(step));
        executor.run(step);
        Files.writeString(ws.resolve("in.txt"), "v2\n");
        Files.writeString(ws.resolve("mode.txt"), "fail\n");
        attempt(executor, step);
        Files.writeString(ws.resolve("in.txt"), "v1\n");
        Files.writeString(ws.resolve("mode.txt"), "ok\n");
        executor.run(step);
        c.equal("HIGH stamps: a step that failed is run again for the inputs of its last success", 3, lines(ws, "runs-cfg"));
        stampGoneAtStart(c, ws, executor, step);
    }

    private static void stampGoneAtStart(Checks c, Path ws, StepExecutor executor, NativeStep step) throws Exception {
        Files.writeString(ws.resolve("in.txt"), "v3\n");
        executor.run(step);
        List<String> seen = lines(ws, "seen-cfg") == 0 ? List.of() : Files.readAllLines(ws.resolve("seen-cfg.txt"));
        c.equal("HIGH stamps: the stamp is gone before the tool starts (a killed step keeps none)", "absent",
                seen.isEmpty() ? "" : seen.get(seen.size() - 1));
    }

    private static void dependentStamps(Checks c) throws Exception {
        Path ws = c.dir("r3-chain", Map.of("in.txt", "v1\n", "test-in.txt", "t\n", "mode.txt", "ok\n"));
        NativeStep configure = step(ws, NativePhase.CONFIGURE, "cfg", "in.txt");
        NativeStep test = step(ws, NativePhase.TEST, "tst", "test-in.txt");
        StepExecutor executor = executor(ws, List.of(configure, test));
        executor.run(configure);
        executor.run(test);
        Files.writeString(ws.resolve("in.txt"), "v2\n");
        executor.run(configure);
        executor.run(test);
        c.equal("HIGH stamps: a step that runs again invalidates the later steps' stamps", 2, lines(ws, "runs-tst"));
        executor.run(configure);
        executor.run(test);
        c.check("stamps: unchanged inputs are still up to date", lines(ws, "runs-cfg") == 2 && lines(ws, "runs-tst") == 2);
    }

    /** Found in the end-to-end run: the build step, which the runner never stamps, must not clear later stamps. */
    private static void unstampedStep(Checks c) throws Exception {
        Path ws = c.dir("r3-unstamped", Map.of("in.txt", "v1\n", "test-in.txt", "t\n", "mode.txt", "ok\n"));
        NativeStep build = new NativeStep(NativePhase.BUILD, java(), ".", List.of("-cp", classesOrEmpty(),
                ModeMain.class.getName(), "bld", "out/bld.txt", "none"), List.of(), List.of());
        NativeStep test = step(ws, NativePhase.TEST, "tst", "test-in.txt");
        StepExecutor executor = executor(ws, List.of(build, test));
        executor.run(build);
        executor.run(test);
        executor.run(build);
        executor.run(test);
        c.check("HIGH stamps: a step that runs every time (no outputs, never stamped) keeps the later stamps",
                lines(ws, "runs-bld") == 2 && lines(ws, "runs-tst") == 1);
    }

    private static NativeStep step(Path ws, NativePhase phase, String tag, String input) {
        String stamp = WindowsPaths.mixed(PolyglotEngine.stamp(ws, phase.task()));
        return new NativeStep(phase, java(), ".", List.of("-cp", classesOrEmpty(), ModeMain.class.getName(), tag,
                "out/" + tag + ".txt", stamp), List.of(input), List.of("out/" + tag + ".txt"));
    }

    private static String classesOrEmpty() {
        try {
            return classes();
        } catch (Exception unusable) {
            throw new IllegalStateException(unusable);
        }
    }

    static StepExecutor executor(Path ws, List<NativeStep> steps) throws IOException {
        Layout layout = Layout.of(ws, ws, ws.resolve("target/native/x"), ws.resolve("target/native/stage/x"),
                ws.resolve("target/native-reports"));
        StepExecutor executor = new StepExecutor(layout, true, Duration.ofMinutes(1), line -> { }, line -> { });
        executor.prepare(steps);
        return executor;
    }

    private static void attempt(StepExecutor executor, NativeStep step) throws InterruptedException {
        try {
            executor.run(step);
        } catch (IOException expected) {
            // The failing run is the point of the check.
        }
    }

    static int lines(Path ws, String name) throws IOException {
        Path file = ws.resolve(name + ".txt");
        return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8).size() : 0;
    }

    /** True on Windows, where the batch and junction checks apply. */
    static boolean windows() {
        return WINDOWS;
    }

    /** Collects the names of thrown failures, for checks that only need the side effect to be absent. */
    static List<String> quietly(List<Checks.ThrowingRunnable> bodies) {
        List<String> thrown = new ArrayList<>();
        bodies.forEach(body -> {
            try {
                body.run();
            } catch (Exception failure) {
                thrown.add(failure.getClass().getSimpleName());
            }
        });
        return thrown;
    }
}
