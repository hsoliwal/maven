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
package org.apache.maven.plugins.polyglot.adapters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The LSP collector ({@code LspDiagnosticsMain}) against {@link FakeLanguageServer} in its own JVM: diagnostics a
 * server publishes in a second stage or while work-done progress is open are kept; a silent, crashing or refused
 * server and unreadable or missing files end with both reports written and the documented exit (2 = the invocation
 * failed, 4 = partial), inside the deadline. Needs LSP4J on the class path.
 */
public final class LspCollectorContractTestMain {
    /** Generous for cases that end when the server is quiet (a loaded machine starts JVMs slowly). */
    private static final long DEADLINE = 30_000;
    /** For the case that must run into the deadline. */
    private static final long SHORT_DEADLINE = 6000;
    private static int passed;

    private LspCollectorContractTestMain() { }

    public static void main(String[] ignored) throws Exception {
        Path ws = Files.createTempDirectory("lsp-collector-").toRealPath();
        try {
            cases(ws);
            System.out.println("PASS: " + passed + " LSP collector contract cases");
        } finally {
            delete(ws);
        }
    }

    private static void cases(Path ws) throws Exception {
        Files.writeString(ws.resolve("a.c"), "int a;\n", StandardCharsets.UTF_8);
        Files.write(ws.resolve("latin1.c"), new byte[] {'/', '*', ' ', (byte) 0xE9, ' ', '*', '/', '\n'});
        secondStageIsKept(ws);
        progressIsAwaited(ws);
        silentServerFailsInsideDeadline(ws);
        crashingServerFailsFast(ws);
        unreadableAndMissingFilesArePartial(ws);
        batchLauncherWithMetacharactersIsRefused(ws);
    }

    private static void secondStageIsKept(Path ws) throws Exception {
        Run run = collect(ws, "twostage", DEADLINE, 2000, fakeServer("twostage"), "a.c");
        require(run.exit == 0 && run.sarif.contains("\"executionSuccessful\":true") && run.sarif.contains("late-error"),
                "a diagnostic published 1.5 s after an empty first publish is kept: " + run);
        passed++;
    }

    private static void progressIsAwaited(Path ws) throws Exception {
        Run run = collect(ws, "progress", DEADLINE, 500, fakeServer("progress"), "a.c");
        require(run.exit == 0 && run.sarif.contains("late-error"),
                "diagnostics published before work-done progress ends are kept (settle 500 ms < 2.5 s): " + run);
        passed++;
    }

    private static void silentServerFailsInsideDeadline(Path ws) throws Exception {
        Run run = collect(ws, "silent", SHORT_DEADLINE, 1000, fakeServer("silent"), "a.c");
        require(run.exit == 2 && run.sarif.contains("\"executionSuccessful\":false") && run.jsonlExists
                && run.millis < SHORT_DEADLINE + 2500, "a silent server: exit 2, reports, inside the deadline: " + run);
        passed++;
    }

    private static void crashingServerFailsFast(Path ws) throws Exception {
        Run run = collect(ws, "crash", DEADLINE, 1000, fakeServer("crash"), "a.c");
        require(run.exit == 2 && run.sarif.contains("\"executionSuccessful\":false") && run.millis < DEADLINE / 3,
                "a server that exits at once fails the run fast with reports: " + run);
        passed++;
    }

    private static void unreadableAndMissingFilesArePartial(Path ws) throws Exception {
        Run run = collect(ws, "unreadable", DEADLINE, 500, fakeServer("ok"), "a.c", "latin1.c", "missing.c");
        require(run.exit == LspDiagnosticsMain.PARTIAL_EXIT && run.sarif.contains("\"executionSuccessful\":false")
                && run.sarif.contains("\"ruleId\":\"w1\"") && run.sarif.contains("latin1.c")
                && run.sarif.contains("missing.c"), "files not analysed are named, the others kept: " + run);
        passed++;
    }

    private static void batchLauncherWithMetacharactersIsRefused(Path ws) throws Exception {
        Path launcher = ws.resolve("fake-server.cmd");
        Files.writeString(launcher, "@echo off\r\necho started>started.txt\r\n", StandardCharsets.US_ASCII);
        refusedLauncher(ws, launcher.toString(), "x&md,pwned");
        refusedLauncher(ws, launcher.toString(), "a%PATHEXT%b");
        refusedLauncher(ws, launcher.toString(), "q^w");
        refusedLauncher(ws, launcher.toString().toUpperCase(java.util.Locale.ROOT) + ". ", "x|y");
        passed++;
    }

    private static void refusedLauncher(Path ws, String launcher, String unsafe) throws Exception {
        Run run = collect(ws, "cmd", DEADLINE, 500, List.of(launcher, unsafe), "a.c");
        require(run.exit == 2 && run.sarif.contains("metacharacter") && !Files.exists(ws.resolve("started.txt"))
                && !Files.exists(ws.resolve("pwned")), "a .cmd server never receives " + unsafe + ": " + run);
    }

    /** The server command: this JVM's java running the fake server from the test class path. */
    private static List<String> fakeServer(String mode) throws Exception {
        return List.of(ToolArgs.java(), "-cp", ToolArgs.home(FakeLanguageServer.class),
                FakeLanguageServer.class.getName(), mode);
    }

    private record Run(int exit, String sarif, boolean jsonlExists, long millis) { }

    private static Run collect(Path ws, String name, long deadline, long settle, List<String> server, String... files)
            throws Exception {
        List<String> args = new ArrayList<>(List.of(ws.toString(), ws.resolve(name + ".jsonl").toString(),
                ws.resolve(name + ".sarif").toString(), Long.toString(deadline), Long.toString(settle), "c"));
        args.addAll(server);
        args.add("--");
        List.of(files).forEach(f -> args.add(ws.resolve(f).toString()));
        long start = System.nanoTime();
        int exit = LspDiagnosticsMain.run(args);
        long millis = (System.nanoTime() - start) / 1_000_000;
        Path sarif = ws.resolve(name + ".sarif");
        return new Run(exit, Files.exists(sarif) ? Files.readString(sarif) : "",
                Files.exists(ws.resolve(name + ".jsonl")), millis);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void delete(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
        }
    }
}
