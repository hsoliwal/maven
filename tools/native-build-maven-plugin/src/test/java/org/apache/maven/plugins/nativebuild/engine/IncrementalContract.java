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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Real processes through the polyglot engine: a second run is UP-TO-DATE, a toolchain fingerprint change re-runs
 * configure, a source change and a missing output re-run, a failing tool and a missing declared output fail.
 */
final class IncrementalContract {
    private static final String FINGERPRINT = "target/native/x/toolchain.properties";

    private IncrementalContract() {}

    static void run(Checks c) throws Exception {
        Path ws = c.dir("incremental", Map.of("in.txt", "source v1\n", FINGERPRINT, "tool.cc.version=gcc 13\n"));
        Path tasks = manifest(ws, "out/result.txt");
        List<String> first = run(tasks, ws, true);
        c.check("first run executes", runs(ws) == 1 && first.stream().noneMatch(l -> l.startsWith("UP-TO-DATE")));
        List<String> second = run(tasks, ws, true);
        c.check("second run is UP-TO-DATE", runs(ws) == 1
                && second.stream().anyMatch(l -> l.startsWith("UP-TO-DATE native-configure")));
        changes(c, ws, tasks);
        failures(c, ws);
    }

    private static void changes(Checks c, Path ws, Path tasks) throws Exception {
        Files.writeString(ws.resolve(FINGERPRINT), "tool.cc.version=gcc 14\n");
        run(tasks, ws, true);
        c.equal("toolchain fingerprint change re-runs configure", 2, runs(ws));
        Files.writeString(ws.resolve("in.txt"), "source v2\n");
        run(tasks, ws, true);
        c.equal("source change re-runs", 3, runs(ws));
        Files.delete(ws.resolve("out/result.txt"));
        run(tasks, ws, true);
        c.equal("missing output re-runs", 4, runs(ws));
        run(tasks, ws, false);
        c.equal("without incremental mode it always runs", 5, runs(ws));
    }

    private static void failures(Checks c, Path ws) throws Exception {
        Path failing = manifest(ws, "fail");
        c.fails("non-zero exit fails the step", IOException.class, () -> run(failing, ws, false));
        Path silent = manifest(ws, "none");
        Files.deleteIfExists(ws.resolve("out/result.txt"));
        c.fails("missing declared output fails the step", IOException.class, () -> run(silent, ws, false));
    }

    private static Path manifest(Path ws, String output) throws Exception {
        String java = WindowsPaths.mixed(Path.of(ProcessHandle.current().info().command().orElseThrow()));
        String classes = WindowsPaths.mixed(Path.of(TouchMain.class.getProtectionDomain().getCodeSource().getLocation()
                .toURI()));
        List<String> outputs = output.contains("/") || "none".equals(output) ? List.of("out/result.txt") : List.of();
        NativeStep step = new NativeStep(NativePhase.CONFIGURE, java, ".", List.of("-cp", classes,
                TouchMain.class.getName(), output), List.of("in.txt", FINGERPRINT), outputs);
        Path file = ws.resolve("tasks-" + output.replace('/', '-') + ".tsv");
        TaskFileWriter.write(file, TaskFileWriter.render(List.of(step)));
        return file;
    }

    private static List<String> run(Path tasks, Path ws, boolean incremental) throws Exception {
        List<String> log = new ArrayList<>();
        PolyglotEngine.run(tasks, ws, "native-configure", incremental, Duration.ofMinutes(2), log::add);
        return log;
    }

    private static int runs(Path ws) throws IOException {
        Path counter = ws.resolve("runs.txt");
        return Files.exists(counter) ? Files.readAllLines(counter).size() : 0;
    }
}
