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

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * The program each polyglot task starts: {@code java -cp <plugin> StepShim <step file> <log file>}. It reads the
 * {@link StepSpec}, empties the declared clean directory, starts the real tool through {@link CapturedProcess} (so
 * every argument survives Windows command-line splitting and the environment additions apply) with both output
 * streams appended to the step's log, which the plugin follows into Maven's log, and exits with the tool's status.
 * The polyglot runner keeps its task graph, timeout (it stops the shim and its descendants) and content stamps.
 */
public final class StepShim {
    /** The first words of the log line written when the tool could not be started. */
    public static final String CANNOT_START = "native-step: cannot start";
    /** The exit status for a tool that could not be started. */
    public static final int START_FAILED = 127;

    private StepShim() {}

    public static void main(String[] args) {
        if (args.length != 2) {
            System.exit(START_FAILED);
        }
        capture(Path.of(args[1]));
        System.exit(run(Path.of(args[0]), Path.of(args[1])));
    }

    /** Runs the step; returns the tool's exit status, or {@link #START_FAILED}. */
    static int run(Path stepFile, Path log) {
        try {
            StepSpec spec = StepSpec.parse(Files.readString(stepFile, StandardCharsets.UTF_8));
            forget(spec.forget());
            empty(spec.clean());
            return CapturedProcess.append(spec.command(), spec.environment(), Path.of("").toAbsolutePath(), log);
        } catch (IOException | RuntimeException failure) {
            note(log, CANNOT_START + ": " + failure.getMessage());
            return START_FAILED;
        } catch (InterruptedException interrupt) {
            Thread.currentThread().interrupt();
            return START_FAILED;
        }
    }

    /** The shim command for a step: this JVM's java with small-heap, fast-start flags and this class's location. */
    public static List<String> command(String java, String classpath, Path stepFile, Path log) {
        return List.of(java, "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-Xmx64m", "-cp", classpath,
                StepShim.class.getName(), WindowsPaths.mixed(stepFile), WindowsPaths.mixed(log));
    }

    /**
     * Deletes the step's stamp and those of the later steps before the tool starts: the polyglot runner records a
     * stamp only after a success, so a step that fails, times out or is killed is never trusted afterwards.
     */
    private static void forget(List<String> stamps) throws IOException {
        try {
            stamps.stream().map(Path::of).forEach(StepShim::deleteStamp);
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private static void deleteStamp(Path stamp) {
        try {
            Files.deleteIfExists(stamp);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * Sends what this JVM prints from Java code (a stack trace, a warning) to the step log, which the plugin follows
     * into Maven's log; only what the JVM launcher prints before {@code main} (such as the JAVA_TOOL_OPTIONS notice)
     * still reaches the console, because the polyglot runner starts the shim with inherited output.
     */
    private static void capture(Path log) {
        try {
            PrintStream out = new PrintStream(new FileOutputStream(log.toFile(), true), true, StandardCharsets.UTF_8);
            System.setOut(out);
            System.setErr(out);
        } catch (IOException unwritable) {
            // The tool's own output still goes to the log through the launcher.
        }
    }

    private static void empty(String directory) throws IOException {
        if (!directory.isEmpty()) {
            FileTrees.delete(Path.of(directory));
        }
    }

    private static void note(Path log, String line) {
        try {
            Files.writeString(log, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // The exit status still reports the failure.
        }
    }
}
