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
package org.apache.maven.mcp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Runs Maven as a child process of the distribution's own launcher script ({@code bin/mvn}, or {@code bin/mvn.cmd}
 * on Windows), so the distribution keeps ownership of how Maven starts (class path, jvm.config, core extensions in
 * lib/ext). The command is an argument vector given to {@link ProcessBuilder}; nothing is joined into a shell command
 * line, every client value has passed {@link ArgumentGuard}, and a {@code .cmd} launcher's vector passes
 * {@link CmdArguments}. The child's output goes to files in the run
 * directory, never to this server's stdout, which carries the MCP protocol.
 */
final class MavenLauncher {

    /** The launcher script and how it was found: --maven-home, mvnw, MAVEN_HOME or PATH. */
    record Launcher(Path script, String source) {
        /** Whether Java starts the script through cmd.exe ({@code mvn.cmd}, {@code mvnw.cmd}, any .cmd or .bat). */
        boolean cmd() {
            String name = script.getFileName().toString().toLowerCase(Locale.ROOT);
            return name.endsWith(".cmd") || name.endsWith(".bat");
        }
    }

    /** The outcome of one Maven process. */
    record MavenRun(
            int exitCode, boolean timedOut, long durationMs, List<String> args, Launcher launcher, Path javaHome) {}

    static final String CONSOLE = "console.log";

    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    private static final Set<Process> RUNNING = ConcurrentHashMap.newKeySet();

    private final ServerSettings settings;
    private final Map<String, String> env;

    MavenLauncher(ServerSettings settings, Map<String, String> env) {
        this.settings = settings;
        this.env = env;
    }

    /** Finds the launcher: --maven-home, then the project's mvnw, then MAVEN_HOME, then mvn on PATH. */
    Launcher locate() {
        if (settings.mavenHome().isPresent()) {
            return home(settings.mavenHome().get(), "--maven-home")
                    .orElseThrow(() -> new IllegalStateException("no bin/" + script("mvn") + " in --maven-home"));
        }
        return Optional.of(settings.root().resolve(script("mvnw")))
                .filter(Files::isRegularFile)
                .map(wrapper -> new Launcher(wrapper, "mvnw"))
                .or(() -> Optional.ofNullable(env.get("MAVEN_HOME")).flatMap(dir -> home(Path.of(dir), "MAVEN_HOME")))
                .or(this::onPath)
                .orElseThrow(() -> new IllegalStateException("Maven not found: pass --maven-home, add mvnw to the"
                        + " project, set MAVEN_HOME or put mvn on PATH"));
    }

    /**
     * Runs Maven with {@code args} in the project root and waits at most {@code timeoutSeconds}, then stops the
     * process tree. The console output goes to {@code dir/console.log}. Callers hold {@link ServerContext#exclusive}.
     */
    MavenRun run(Path dir, List<String> args, int timeoutSeconds) throws IOException {
        return execute(locate(), dir, args, timeoutSeconds);
    }

    /** Stops every running Maven process and its children; used when the client goes away. */
    static void stopAll() {
        RUNNING.forEach(MavenLauncher::stop);
    }

    private MavenRun execute(Launcher launcher, Path dir, List<String> args, int timeoutSeconds) throws IOException {
        long start = System.nanoTime();
        Process process = processBuilder(launcher, dir, args).start();
        RUNNING.add(process);
        try {
            Exit exit = await(process, timeoutSeconds);
            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            return new MavenRun(exit.code(), exit.timedOut(), durationMs, List.copyOf(args), launcher,
                    settings.javaHome());
        } finally {
            RUNNING.remove(process);
        }
    }

    private ProcessBuilder processBuilder(Launcher launcher, Path dir, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(launcher.script().toString());
        command.addAll(launcher.cmd() ? CmdArguments.check(args) : args);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(settings.root().toFile())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve(CONSOLE).toFile());
        builder.environment().put("JAVA_HOME", settings.javaHome().toString());
        return builder;
    }

    private record Exit(int code, boolean timedOut) {}

    private static Exit await(Process process, int timeoutSeconds) throws IOException {
        process.getOutputStream().close();
        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                stop(process);
                process.waitFor();
            }
            return new Exit(process.exitValue(), !finished);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop(process);
            throw new IOException("interrupted while Maven was running", e);
        }
    }

    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private Optional<Launcher> onPath() {
        return Optional.ofNullable(env.get("PATH")).stream()
                .flatMap(path -> Arrays.stream(path.split(File.pathSeparator)))
                .filter(dir -> !dir.isBlank())
                .map(dir -> Path.of(dir.trim()).resolve(script("mvn")))
                .filter(Files::isRegularFile)
                .findFirst()
                .map(found -> new Launcher(found, "PATH"));
    }

    private static Optional<Launcher> home(Path home, String source) {
        return Optional.of(home.resolve("bin").resolve(script("mvn")))
                .filter(Files::isRegularFile)
                .map(found -> new Launcher(found, source));
    }

    private static String script(String name) {
        return WINDOWS ? name + ".cmd" : name;
    }
}
