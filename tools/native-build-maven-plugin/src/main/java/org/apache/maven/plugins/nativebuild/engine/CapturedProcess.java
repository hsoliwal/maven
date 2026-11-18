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
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The plugin's one process launcher: runs a tool by argument list (no shell; on Windows each argument quoted by the
 * runtime's rules, see {@link WindowsArgs}) and captures what it prints. The polyglot runner owns the task graph,
 * timeouts and content stamps but starts tools with inherited output and no environment, so native steps run
 * through {@link StepShim}, which uses {@link #append}; probes ({@code --version}, vswhere, {@code ninja -t compdb},
 * the make dry run) use {@link #run}. Output goes to files, so a chatty tool cannot block on a full pipe. When the
 * core ProcessLauncher service (hsoliwal/maven branch core/process-launcher) lands, this class adapts to it.
 */
public final class CapturedProcess {
    private static final System.Logger LOG = System.getLogger(CapturedProcess.class.getName());
    private static final long MAX_BYTES = 64L << 20;
    private static final java.util.regex.Pattern REFERENCE =
            java.util.regex.Pattern.compile("\\$\\{([A-Za-z_]\\w*)\\}");

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
        return run(command, Map.of(), directory, timeout);
    }

    /**
     * Runs {@code command} in {@code directory} with {@code environment} added (each {@code ${NAME}} expanded from this
     * process's environment); a timeout stops the tool and its children and fails.
     */
    public static Result run(List<String> command, Map<String, String> environment, Path directory, Duration timeout)
            throws IOException, InterruptedException {
        LOG.log(Level.DEBUG, () -> "capture " + String.join(" ", command) + " in " + directory);
        Path out = Files.createTempFile("native-out", ".txt");
        Path err = Files.createTempFile("native-err", ".txt");
        try {
            ProcessBuilder builder = builder(command, directory).redirectOutput(out.toFile()).redirectError(err.toFile());
            environment.forEach((name, value) -> builder.environment().put(name, expand(value)));
            Process process = builder.start();
            int exit = await(process, timeout, command);
            return new Result(exit, read(out), read(err));
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }

    /**
     * Runs {@code command} in {@code directory} with {@code environment} added to this process's environment, both
     * output streams appended to {@code log}; returns the exit status. The caller owns the timeout.
     */
    public static int append(List<String> command, Map<String, String> environment, Path directory, Path log)
            throws IOException, InterruptedException {
        ProcessBuilder builder = builder(command, directory).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        environment.forEach((name, value) -> builder.environment().put(name, expand(value)));
        Process process = builder.start();
        try {
            return process.waitFor();
        } catch (InterruptedException interrupt) {
            stop(process);
            throw interrupt;
        }
    }

    /**
     * An environment value with each {@code ${NAME}} replaced by this process's {@code NAME} (empty when unset), so a
     * step can extend a variable, such as {@code PATH=<folder>;${PATH}}, without recording the whole inherited value.
     */
    static String expand(String value) {
        Map<String, String> inherited = PlanContext.environment(System.getenv());
        return REFERENCE.matcher(value).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
                inherited.getOrDefault(m.group(1), "")));
    }

    /** A process builder whose argument list survives Windows command-line splitting. */
    static ProcessBuilder builder(List<String> command, Path directory) {
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        return new ProcessBuilder(windows ? WindowsArgs.encode(command) : command).directory(directory.toFile());
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
