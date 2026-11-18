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
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs planned native steps: each step's real invocation goes into a {@link StepSpec} file, and the polyglot
 * manifest ({@code <build>/tasks.tsv}) holds one {@link StepShim} task per step with the step's declared inputs (plus
 * the step file) and outputs. The polyglot runner orders, times out and stamps the tasks; the shim starts the tool
 * with its output in {@code <build>/logs/<task>.log}, which is followed into the log while the step runs. Without
 * incremental mode the step's old content stamp is deleted first, so a later incremental run never trusts a stamp
 * that a run without stamps has made stale. Whenever a step really runs, the shim deletes its stamp and those of the
 * later steps before the tool starts (the runner writes a stamp only after a success), so a step that fails, times
 * out or is killed is run again next time even for the inputs of an earlier success, and the steps after a re-run
 * step run again too; a failure also deletes them here.
 */
public final class StepExecutor {
    /** Lines of tool output kept for a failure message. */
    public static final int TAIL = 40;

    private final Layout layout;
    private final boolean incremental;
    private final Duration timeout;
    private final Consumer<String> info;
    private final Consumer<String> debug;
    private List<NativeStep> prepared = List.of();

    /** An executor over one build layout; {@code info} receives tool output and up-to-date notes. */
    public StepExecutor(Layout layout, boolean incremental, Duration timeout, Consumer<String> info,
            Consumer<String> debug) {
        this.layout = layout;
        this.incremental = incremental;
        this.timeout = timeout;
        this.info = info;
        this.debug = debug;
    }

    /** Writes the step files and the shim manifest for {@code steps}. */
    public Path prepare(List<NativeStep> steps) throws IOException {
        prepared = List.copyOf(steps);
        String java = WindowsPaths.mixed(Path.of(System.getProperty("java.home"), "bin",
                Platform.current().windows() ? "java.exe" : "java"));
        String classpath = shimClasspath();
        try {
            steps.forEach(this::writeSpec);
        } catch (java.io.UncheckedIOException failure) {
            throw failure.getCause();
        }
        List<NativeStep> tasks = steps.stream().map(step -> shimTask(step, java, classpath)).toList();
        Path manifest = layout.path(layout.inBuild("tasks.tsv"));
        TaskFileWriter.write(manifest, TaskFileWriter.render(tasks));
        return manifest;
    }

    /** Runs one prepared step; a failure carries the last lines of the tool's output. */
    public void run(NativeStep step) throws IOException, InterruptedException {
        if (Platform.current().windows()) {
            WindowsArgs.checkBatch(Steps.list(step.tool(), step.arguments()));
        }
        if (!incremental) {
            PolyglotEngine.forget(layout.basedir(), step.name());
        }
        Path log = layout.path(logFile(step));
        Files.createDirectories(log.getParent());
        Files.deleteIfExists(log);
        LogTail tail = LogTail.follow(log, info, TAIL);
        IOException failure = attempt(step, tail);
        if (failure != null) {
            forget(invalidated(step));
            throw new StepFailure(failure, tail.last(), log);
        }
    }

    /** The stamps a run of {@code step} invalidates: its own and those of every step prepared after it. */
    List<Path> invalidated(NativeStep step) {
        int index = prepared.indexOf(step);
        List<NativeStep> from = index < 0 ? List.of(step) : prepared.subList(index, prepared.size());
        return from.stream().map(s -> PolyglotEngine.stamp(layout.basedir(), s.name())).distinct().toList();
    }

    private static void forget(List<Path> stamps) throws IOException {
        try {
            stamps.forEach(StepExecutor::deleteStamp);
        } catch (java.io.UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private static void deleteStamp(Path stamp) {
        try {
            Files.deleteIfExists(stamp);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private IOException attempt(NativeStep step, LogTail tail) throws IOException, InterruptedException {
        try {
            PolyglotEngine.run(layout.path(layout.inBuild("tasks.tsv")), layout.basedir(), step.name(), incremental,
                    timeout, line -> (line.startsWith("UP-TO-DATE") ? info : debug).accept(line));
            return null;
        } catch (IOException failed) {
            return failed;
        } finally {
            tail.close();
        }
    }

    private void writeSpec(NativeStep step) {
        try {
            TaskFileWriter.write(layout.path(stepFile(step)), spec(step).render());
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    /**
     * The step's real invocation; an install that fills the stage empties it first, and every step deletes the stamps
     * it invalidates before its tool starts. A step without declared outputs is never stamped by the runner (it runs
     * every time, as the build step does), so it deletes only its own: the later steps' inputs carry its result
     * ({@link BuildDigest}), and a failure of it still deletes theirs in {@link #run}.
     */
    StepSpec spec(NativeStep step) {
        boolean emptiesStage = step.phase() == NativePhase.INSTALL && step.outputs().contains(layout.stage())
                && layout.stageDisposable();
        List<Path> forget = step.outputs().isEmpty() ? invalidated(step).subList(0, 1) : invalidated(step);
        return new StepSpec(step.tool(), step.arguments(), step.environment(),
                emptiesStage ? layout.mixed(layout.stage()) : "", forget.stream().map(WindowsPaths::mixed).toList());
    }

    private NativeStep shimTask(NativeStep step, String java, String classpath) {
        List<String> command = StepShim.command(java, classpath, layout.path(stepFile(step)),
                layout.path(logFile(step)));
        return new NativeStep(step.phase(), java, step.workingDir(), command.subList(1, command.size()),
                Steps.list(step.inputs(), stepFile(step)), step.outputs(), Map.of());
    }

    /** The step file, project-relative. */
    public String stepFile(NativeStep step) {
        return layout.inBuild("steps/" + step.name() + ".step");
    }

    /** The step's output log, project-relative. */
    public String logFile(NativeStep step) {
        return layout.inBuild("logs/" + step.name() + ".log");
    }

    private static String shimClasspath() throws IOException {
        try {
            return WindowsPaths.mixed(Path.of(StepShim.class.getProtectionDomain().getCodeSource().getLocation()
                    .toURI()));
        } catch (URISyntaxException | RuntimeException unusable) {
            throw new IOException("cannot locate the plugin classes for the step shim", unusable);
        }
    }
}
