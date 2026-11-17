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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * The server's fixed configuration, from its command line. The project root is fixed for the life of the server, so
 * no tool call can point Maven at another directory.
 *
 * @param root the Maven project root (holds pom.xml); {@code --root}, default the working directory
 * @param mavenHome a Maven distribution to use first; {@code --maven-home}
 * @param javaHome the JDK for Maven; {@code --java-home}, else JAVA_HOME, else the server's own runtime
 * @param extension a build-events extension jar for a Maven without it built in; {@code --extension}
 * @param runsDir where run directories live; {@code --runs-dir}, default ~/.m2/agent-runs/(root hash)
 * @param localRepo the local repository for plugin descriptors; {@code --local-repo}, else settings.xml, else default
 * @param keepRuns how many build and test runs to keep; {@code --keep-runs}, default 5
 * @param timeoutSeconds the default time limit of one Maven run; {@code --timeout}, default 900
 */
record ServerSettings(
        Path root,
        Optional<Path> mavenHome,
        Path javaHome,
        Optional<Path> extension,
        Path runsDir,
        Path localRepo,
        int keepRuns,
        int timeoutSeconds) {

    static final String USAGE = "usage: java -jar maven-mcp-server.jar [--root <dir>] [--maven-home <dir>]"
            + " [--java-home <dir>] [--extension <jar>] [--runs-dir <dir>] [--local-repo <dir>]"
            + " [--keep-runs <n>] [--timeout <seconds>]";

    private static final Set<String> OPTIONS = Set.of(
            "--root", "--maven-home", "--java-home", "--extension", "--runs-dir", "--local-repo", "--keep-runs",
            "--timeout");

    static ServerSettings parse(String[] args, Map<String, String> env) throws IOException {
        Map<String, String> options = options(args);
        Path root = Path.of(options.getOrDefault("--root", System.getProperty("user.dir")))
                .toAbsolutePath()
                .normalize();
        if (!Files.isRegularFile(root.resolve("pom.xml"))) {
            throw new IllegalArgumentException("no pom.xml in " + root);
        }
        Path home = Path.of(System.getProperty("user.home"));
        return new ServerSettings(root, existing(options, "--maven-home"), javaHome(options, env),
                existing(options, "--extension"), runsDir(options, home, root), localRepo(options, home),
                integer(options, "--keep-runs", 5, 100), integer(options, "--timeout", 900, 86_400));
    }

    private static Map<String, String> options(String[] args) {
        if (args.length % 2 != 0) {
            throw new IllegalArgumentException("every option needs a value");
        }
        Map<String, String> options = new HashMap<>();
        IntStream.range(0, args.length / 2).forEach(pair -> {
            String name = args[2 * pair];
            if (!OPTIONS.contains(name)) {
                throw new IllegalArgumentException("unknown option " + name);
            }
            options.put(name, args[2 * pair + 1]);
        });
        return options;
    }

    private static Optional<Path> existing(Map<String, String> options, String name) {
        Optional<Path> path = Optional.ofNullable(options.get(name)).map(Path::of).map(Path::toAbsolutePath);
        path.filter(Files::notExists).ifPresent(missing -> {
            throw new IllegalArgumentException(name + " does not exist: " + missing);
        });
        return path;
    }

    private static Path javaHome(Map<String, String> options, Map<String, String> env) {
        return existing(options, "--java-home")
                .or(() -> Optional.ofNullable(env.get("JAVA_HOME")).map(Path::of).filter(Files::isDirectory))
                .orElse(Path.of(System.getProperty("java.home")));
    }

    private static Path runsDir(Map<String, String> options, Path home, Path root) {
        String rootKey = Digests.sha256(root.toString()).substring(0, 12);
        return Optional.ofNullable(options.get("--runs-dir"))
                .map(Path::of)
                .map(Path::toAbsolutePath)
                .orElse(home.resolve(".m2").resolve("agent-runs").resolve(rootKey));
    }

    private static Path localRepo(Map<String, String> options, Path home) throws IOException {
        if (options.containsKey("--local-repo")) {
            return Path.of(options.get("--local-repo")).toAbsolutePath();
        }
        Path settings = home.resolve(".m2").resolve("settings.xml");
        Optional<String> configured = Files.isRegularFile(settings)
                ? Optional.of(SecureXml.text(SecureXml.parse(settings).getDocumentElement(), "localRepository"))
                : Optional.empty();
        return configured.filter(text -> !text.isEmpty() && !text.contains("${"))
                .map(Path::of)
                .orElse(home.resolve(".m2").resolve("repository"));
    }

    private static int integer(Map<String, String> options, String name, int absent, int max) {
        int value = Integer.parseInt(options.getOrDefault(name, Integer.toString(absent)));
        if (value < 1 || value > max) {
            throw new IllegalArgumentException(name + " must be from 1 to " + max);
        }
        return value;
    }
}
