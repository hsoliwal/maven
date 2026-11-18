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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Real processes for the review findings: every argument arrives unchanged (quotes, trailing backslashes, empty
 * arguments) and a batch file cannot run injected commands (HIGH 1); a non-incremental run invalidates stamps
 * (HIGH 3); a failing step's output reaches the log and the error (HIGH 4).
 */
final class ReviewProcessContract {
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    private ReviewProcessContract() {}

    static void run(Checks c) throws Exception {
        argv(c);
        argvThroughEngine(c);
        batch(c);
        staleStamps(c);
        failureTail(c);
    }

    static String java() {
        return WindowsPaths.mixed(Path.of(ProcessHandle.current().info().command().orElseThrow()));
    }

    static String classes() throws Exception {
        return WindowsPaths.mixed(Path.of(ArgvMain.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
    }

    private static void argv(Checks c) throws Exception {
        Path dir = c.dir("review-argv", Map.of());
        List<String> args = List.of("-DX=a \"b c\" d", "say \"hi\"", "q\"", "tail\\", "sp ace\\", "", "a\\\\\"b",
                "x\" SHELL=C:/evil/sh.exe \"", "& | < > ^ %PATH% !x!");
        List<String> command = new ArrayList<>(List.of(java(), "-cp", classes(), ArgvMain.class.getName()));
        command.addAll(args);
        CapturedProcess.Result result = CapturedProcess.run(command, dir, Duration.ofMinutes(1));
        c.equal("HIGH 1: every argument arrives unchanged", args, received(result.out()));
    }

    static List<String> received(String out) {
        return out.lines().filter(l -> l.startsWith("ARG<") && l.endsWith(">"))
                .map(l -> l.substring(4, l.length() - 1)).toList();
    }

    private static void batch(Checks c) throws Exception {
        if (!WINDOWS) {
            c.check("HIGH 1: batch file check (Windows only, skipped)", true);
            return;
        }
        Path dir = c.dir("review-batch", Map.of("fake.cmd", "@echo off\r\necho %*\r\n"));
        String injected = "x\"&echo INJ>" + WindowsPaths.mixed(dir.resolve("INJ.txt")) + "&rem \"";
        try {
            CapturedProcess.run(List.of(WindowsPaths.mixed(dir.resolve("fake.cmd")), injected), dir,
                    Duration.ofMinutes(1));
        } catch (IOException | IllegalArgumentException refused) {
            // Refusing the argument is the expected outcome.
        }
        c.check("HIGH 1: a .cmd tool cannot run an injected command", !Files.exists(dir.resolve("INJ.txt")));
    }

    private static void staleStamps(Checks c) throws Exception {
        Path ws = c.dir("review-stamps", Map.of("in.txt", "v1\n"));
        NativeStep step = new NativeStep(NativePhase.CONFIGURE, java(), ".", List.of("-cp", classes(),
                TouchMain.class.getName(), "out/result.txt"), List.of("in.txt"), List.of("out/result.txt"));
        execute(ws, step, true, new ArrayList<>());
        Files.writeString(ws.resolve("in.txt"), "v2\n");
        execute(ws, step, false, new ArrayList<>());
        Files.writeString(ws.resolve("in.txt"), "v1\n");
        List<String> log = new ArrayList<>();
        execute(ws, step, true, log);
        c.equal("HIGH 3: a run without stamps invalidates the old stamp", 3, runs(ws));
        execute(ws, step, true, log);
        c.check("HIGH 3: stamps still work afterwards",
                runs(ws) == 3 && log.stream().anyMatch(l -> l.startsWith("UP-TO-DATE")));
    }

    private static void failureTail(Checks c) throws Exception {
        Path ws = c.dir("review-tail", Map.of());
        NativeStep step = new NativeStep(NativePhase.BUILD, java(), ".", List.of("-cp", classes(),
                ArgvMain.class.getName(), "--fail=3"), List.of(), List.of());
        List<String> log = new ArrayList<>();
        StepFailure failure = failure(ws, step, log);
        String message = failure == null ? "" : String.valueOf(failure.getMessage());
        c.check("HIGH 4: the failure message carries the tool's last lines", message.contains(ArgvMain.SENTINEL));
        c.check("HIGH 4: tool output goes through the log", log.stream().anyMatch(l -> l.contains(ArgvMain.SENTINEL)));
        c.check("HIGH 4: a failing tool is not a start failure", failure != null && !failure.notStarted());
        NativeStep missing = new NativeStep(NativePhase.TEST, WindowsPaths.mixed(ws.resolve("no-such-tool.exe")), ".",
                List.of("x"), List.of(), List.of());
        StepFailure notStarted = failure(ws, missing, new ArrayList<>());
        c.check("a tool that cannot start is reported as such", notStarted != null && notStarted.notStarted());
    }

    /** HIGH 1 through the whole path: polyglot runner, step shim, tool. */
    private static void argvThroughEngine(Checks c) throws Exception {
        Path ws = c.dir("review-argv-engine", Map.of());
        List<String> args = List.of("-DX=a \"b c\" d", "q\"", "tail\\", "x\" SHELL=C:/evil/sh.exe \"", "$(id) `id`");
        NativeStep step = new NativeStep(NativePhase.BUILD, java(), ".", Steps.list(List.of("-cp", classes(),
                ArgvMain.class.getName()), args), List.of(), List.of());
        List<String> log = new ArrayList<>();
        execute(ws, step, false, log);
        c.equal("HIGH 1: arguments arrive unchanged through polyglot and the shim", args,
                received(String.join("\n", log)));
    }

    static StepFailure failure(Path ws, NativeStep step, List<String> log) throws Exception {
        try {
            execute(ws, step, false, log);
            return null;
        } catch (StepFailure failed) {
            return failed;
        }
    }

    static void execute(Path ws, NativeStep step, boolean incremental, List<String> log) throws Exception {
        Layout layout = Layout.of(ws, ws, ws.resolve("target/native/x"), ws.resolve("target/native/stage/x"),
                ws.resolve("target/native-reports"));
        StepExecutor executor = new StepExecutor(layout, incremental, Duration.ofMinutes(1), log::add, line -> { });
        executor.prepare(List.of(step));
        executor.run(step);
    }

    private static int runs(Path ws) throws IOException {
        Path counter = ws.resolve("runs.txt");
        return Files.exists(counter) ? Files.readAllLines(counter, StandardCharsets.UTF_8).size() : 0;
    }
}
