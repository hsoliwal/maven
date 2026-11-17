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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Windows only: the server starts Maven through cmd.exe ({@code mvn.cmd}, {@code mvnw.cmd}), which splits parameters
 * at {@code =} and {@code ,} before {@code mvn.cmd} scans them for {@code -f}/{@code --file}. A probe {@code mvn.cmd}
 * with the same scan as Maven 3.9's records what it finds: accepted calls give it no file, hostile values are refused
 * before any launch. A {@code mvnw.cmd} that ends like the Maven Wrapper's ({@code "<mvn.cmd>" %*}) runs real builds.
 */
final class WindowsLauncherChecks {

    /**
     * Maven 3.9's {@code mvn.cmd} scan for {@code -f}/{@code --file}; the probe appends what it found next to its
     * {@code bin} directory, which it keeps first because {@code shift} moves {@code %0} too.
     */
    private static final String PROBE = String.join("\r\n", "@echo off", "set \"PROBE_DIR=%~dp0\"", "set FILE_ARG=",
            ":arg_loop", "if \"%~1\" == \"-f\" (", "  set \"FILE_ARG=%~2\"", "  goto found", ")",
            "if \"%~1\" == \"--file\" (", "  set \"FILE_ARG=%~2\"", "  goto found", ")", "shift",
            "if not \"%~1\" == \"\" goto arg_loop", ":found",
            ">>\"%PROBE_DIR%..\\probe.txt\" echo FILE_ARG=[%FILE_ARG%]", "exit /b 0", "");

    private WindowsLauncherChecks() {}

    static void run(Checks checks, Path jar, Path fixture, Path scratch) throws Exception {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            checks.that("Windows launcher checks NOT RUN on " + System.getProperty("os.name"), true);
            return;
        }
        probe(checks, jar, fixture, scratch);
        wrapper(checks, jar, fixture, scratch);
    }

    private static void probe(Checks checks, Path jar, Path fixture, Path scratch) throws Exception {
        Path home = Files.createDirectories(scratch.resolve("probe-home").resolve("bin")).getParent();
        Files.writeString(home.resolve("bin").resolve("mvn.cmd"), PROBE, StandardCharsets.US_ASCII);
        Path found = home.resolve("probe.txt");
        Files.deleteIfExists(found);
        try (McpStdioClient client = client(jar, fixture, scratch, "probe", "--maven-home", home.toString())) {
            McpProtocolTest.initialize(client);
            JsonNode ok = build(client, "{\"a\":\"x.y/z#1\",\"b\":\"\"}");
            JsonNode test = client.tool("test", CompactJson.parse("{\"selection\":\"ATest#adds+subtracts\"}"), 60);
            checks.that("mvn.cmd: accepted calls ran", "success".equals(ok.path("outcome").asText())
                    && "success".equals(test.path("outcome").asText()), ok + " " + test);
            hostile(checks, client, "mvn.cmd");
            checks.that("mvn.cmd: its -f scan found no file, and refused calls never ran it",
                    Files.readAllLines(found).equals(List.of("FILE_ARG=[]", "FILE_ARG=[]")), Files.readString(found));
            checks.that("mvn.cmd probe server exits 0", client.shutdown(60) == 0);
        }
    }

    /** A project with a mvnw.cmd that hands its parameters to the real mvn.cmd, as the Maven Wrapper does last. */
    private static void wrapper(Checks checks, Path jar, Path fixture, Path scratch) throws Exception {
        Optional<Path> mvn = realMvnCmd();
        if (mvn.isEmpty()) {
            checks.that("mvnw.cmd checks NOT RUN: no mvn.cmd on PATH or in -Dmcp.test.mavenHome", true);
            return;
        }
        Files.writeString(fixture.resolve("mvnw.cmd"), "@echo off\r\n\"" + mvn.get() + "\" %*\r\n",
                StandardCharsets.US_ASCII);
        try (McpStdioClient client = client(jar, fixture, scratch, "wrapper")) {
            McpProtocolTest.initialize(client);
            wrapperRuns(checks, client);
            hostile(checks, client, "mvnw.cmd");
            checks.that("mvnw.cmd server exits 0", client.shutdown(60) == 0);
        } finally {
            Files.deleteIfExists(fixture.resolve("mvnw.cmd"));
        }
    }

    /** A build with a '=' in a property value, which cmd.exe splits in two layers, and a test, through mvnw.cmd. */
    private static void wrapperRuns(Checks checks, McpStdioClient client) throws Exception {
        JsonNode compile = client.tool("build", CompactJson.parse("{\"goals\":[\"compile\"],\"projects\":[\"a\"],"
                + "\"properties\":{\"maven.compiler.showWarnings\":\"true\"}}"), McpRunChecks.BUILD_SECONDS);
        checks.that("mvnw.cmd: build runs through the wrapper", "success".equals(compile.path("outcome").asText())
                && "mvnw".equals(compile.path("launcher").path("source").asText()), compile.toString());
        JsonNode test = client.tool("test", CompactJson.parse("{\"selection\":\"ATest#adds\",\"project\":\"a\"}"),
                McpRunChecks.BUILD_SECONDS);
        checks.that("mvnw.cmd: test runs", "success".equals(test.path("outcome").asText()), test.toString());
    }

    /** Values that would hand mvn.cmd a {@code -f} after cmd.exe splits them: each refused with the reason. */
    private static void hostile(Checks checks, McpStdioClient client, String launcher) throws Exception {
        List.of("-f=C:/evil", "x,-f,C:/evil", "a=b").forEach(value -> checks.that(launcher + ": refused value " + value,
                McpRunChecks.refused(unchecked(() -> build(client, "{\"a\":\"" + value + "\"}")), "through mvn.cmd")));
        JsonNode selection = client.tool("test", CompactJson.parse("{\"selection\":\"A,-f,../x\"}"), 60);
        checks.that(launcher + ": refused selection A,-f,../x", McpRunChecks.refused(selection, "through mvn.cmd"),
                selection.toString());
    }

    private static JsonNode build(McpStdioClient client, String properties) throws Exception {
        return client.tool("build", CompactJson.parse("{\"goals\":[\"validate\"],\"properties\":" + properties + "}"),
                McpRunChecks.BUILD_SECONDS);
    }

    private static McpStdioClient client(Path jar, Path fixture, Path scratch, String name, String... extra)
            throws IOException {
        String javaHome = System.getProperty("java.home");
        List<String> command = new ArrayList<>(List.of(Path.of(javaHome, "bin", "java").toString(), "-jar",
                jar.toString(), "--root", fixture.toString(), "--runs-dir", scratch.resolve("runs-" + name).toString(),
                "--java-home", javaHome));
        command.addAll(List.of(extra));
        return new McpStdioClient(command, scratch.resolve("server-" + name + "-stderr.log").toFile());
    }

    /** {@code bin/mvn.cmd} of -Dmcp.test.mavenHome, else the first mvn.cmd on PATH. */
    private static Optional<Path> realMvnCmd() {
        return Optional.ofNullable(System.getProperty("mcp.test.mavenHome")).map(home -> Path.of(home, "bin", "mvn.cmd"))
                .or(() -> Arrays.stream(Optional.ofNullable(System.getenv("PATH")).orElse("").split(File.pathSeparator))
                        .filter(dir -> !dir.isBlank()).map(dir -> Path.of(dir.trim(), "mvn.cmd"))
                        .filter(Files::isRegularFile).findFirst())
                .filter(Files::isRegularFile);
    }

    @FunctionalInterface
    private interface Call {
        JsonNode run() throws Exception;
    }

    private static JsonNode unchecked(Call call) {
        try {
            return call.run();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
