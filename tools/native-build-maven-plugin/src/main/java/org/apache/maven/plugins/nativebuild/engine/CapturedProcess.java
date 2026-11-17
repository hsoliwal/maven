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
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs a tool by argument list (no shell) and captures what it prints, for the work the polyglot runner does not do
 * because it passes output through: {@code --version} probes, vswhere, {@code ninja -t compdb} and the make dry run.
 * Output goes to temporary files, so a chatty tool cannot block on a full pipe.
 */
public final class CapturedProcess {
    private static final System.Logger LOG = System.getLogger(CapturedProcess.class.getName());
    private static final long MAX_BYTES = 64L << 20;

    /**
     * What the tool did.
     *
     * @param exit exit status
     * @param out standard output (UTF-8)
     * @param err standard error (UTF-8)
     */
    public record Result(int exit, String out, String err) {
        /** The first non-blank line of standard output, else of standard error, else empty. */
        public String firstLine() {
            return (out + "\n" + err).lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("");
        }
    }

    private CapturedProcess() {}

    /** Runs {@code command} in {@code directory}; a timeout stops the tool and its children and fails. */
    public static Result run(List<String> command, Path directory, Duration timeout)
            throws IOException, InterruptedException {
        LOG.log(Level.DEBUG, () -> "capture " + String.join(" ", command) + " in " + directory);
        Path out = Files.createTempFile("native-out", ".txt");
        Path err = Files.createTempFile("native-err", ".txt");
        try {
            Process process = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            int exit = await(process, timeout, command);
            return new Result(exit, read(out), read(err));
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }

    private static int await(Process process, Duration timeout, List<String> command)
            throws IOException, InterruptedException {
        try {
            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                stop(process);
                throw new IOException("timed out after " + timeout.toSeconds() + " s: " + command.get(0));
            }
            return process.exitValue();
        } catch (InterruptedException interrupt) {
            stop(process);
            throw interrupt;
        }
    }

    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static String read(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) {
            throw new IOException("captured output exceeds " + (MAX_BYTES >> 20) + " MiB");
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
