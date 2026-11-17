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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Starts the shaded server jar as a child process and drives it over stdio with JSON-RPC: initialize, tools/list,
 * and tools/call for every tool against the fixture reactor copied to a scratch directory. Maven runs for real
 * (the launcher the server finds, or -Dmcp.test.mavenHome). Arguments: the server jar and the scratch directory.
 */
public final class McpProtocolTest {

    private static final int BUILD_SECONDS = McpRunChecks.BUILD_SECONDS;
    private static final Set<String> TOOLS = Set.of("build", "test", "effective_pom", "dependency_tree",
            "describe_goal", "list_modules", "explain_failure", "build_events");

    private McpProtocolTest() {}

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toAbsolutePath();
        Path scratch = Files.createDirectories(Path.of(args[1]).toAbsolutePath());
        Path fixture = copyFixture(scratch.resolve("fixture"));
        Path runs = scratch.resolve("runs");
        deleteTree(runs);
        Checks checks = new Checks("McpProtocol");
        try (McpStdioClient client = new McpStdioClient(command(jar, fixture, runs),
                scratch.resolve("server-stderr.log").toFile())) {
            session(checks, client, fixture, runs);
        }
        fallback(checks, jar, fixture, scratch);
        checks.finish();
    }

    private static void session(Checks checks, McpStdioClient client, Path fixture, Path runs) throws Exception {
        handshake(checks, client);
        projectTools(checks, client);
        McpRunChecks.builds(checks, client, extension().isPresent());
        McpRunChecks.tests(checks, client);
        describeGoal(checks, client);
        McpRunChecks.results(checks, client, extension().isPresent());
        McpRunChecks.refusals(checks, client, runs);
        McpRunChecks.protocolRefusals(checks, client, runs);
        McpRunChecks.concurrency(checks, client);
        McpRunChecks.timeout(checks, client, fixture);
        checks.that("stdout carried only JSON-RPC messages", client.pollution() == 0 && client.messages() > 10);
        checks.that("server exits 0 when stdin closes", client.shutdown(60) == 0);
    }

    /**
     * A second server that reads plugin descriptors from an empty --local-repo, so describe_goal must fall back to
     * help:describe; Maven itself still resolves the plugin from its own local repository.
     */
    private static void fallback(Checks checks, Path jar, Path fixture, Path scratch) throws Exception {
        Path empty = Files.createDirectories(scratch.resolve("empty-repo"));
        Path runs = scratch.resolve("runs-fallback");
        deleteTree(runs);
        try (McpStdioClient client = new McpStdioClient(command(jar, fixture, runs, "--local-repo", empty.toString()),
                scratch.resolve("server-fallback-stderr.log").toFile())) {
            initialize(client);
            JsonNode described = client.tool("describe_goal", CompactJson.object().put("goal", "compiler:compile"),
                    BUILD_SECONDS);
            checks.that("describe_goal: help:describe fallback", "help:describe".equals(described.path("source")
                    .asText()) && described.path("text").asText().contains("compiler:compile"), described.toString());
            checks.that("fallback server exits 0 when stdin closes", client.shutdown(60) == 0);
        }
    }

    private static List<String> command(Path jar, Path fixture, Path runs, String... extra) {
        String javaHome = System.getProperty("java.home");
        List<String> command = new ArrayList<>(List.of(Path.of(javaHome, "bin", "java").toString(), "-jar",
                jar.toString(), "--root", fixture.toString(), "--runs-dir", runs.toString(), "--java-home", javaHome));
        command.addAll(List.of(extra));
        Optional.ofNullable(System.getProperty("mcp.test.mavenHome")).ifPresent(home -> {
            command.add("--maven-home");
            command.add(home);
        });
        extension().ifPresent(jarPath -> {
            command.add("--extension");
            command.add(jarPath);
        });
        return command;
    }

    private static Optional<String> extension() {
        return Optional.ofNullable(System.getProperty("mcp.test.extension")).filter(path -> !path.isBlank());
    }

    private static void handshake(Checks checks, McpStdioClient client) throws Exception {
        JsonNode result = initialize(client);
        checks.that("initialize: server name and protocol", "maven".equals(result.path("serverInfo").path("name")
                .asText()) && "2025-06-18".equals(result.path("protocolVersion").asText()), result.toString());
        checks.that("initialize: tools capability", result.path("capabilities").has("tools"));
        toolList(checks, client);
    }

    /** initialize, then the initialized notification; returns the initialize result. */
    private static JsonNode initialize(McpStdioClient client) throws Exception {
        ObjectNode params = CompactJson.object().put("protocolVersion", "2025-06-18");
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "protocol-test").put("version", "1");
        JsonNode result = client.request("initialize", params, 60).path("result");
        client.notify("notifications/initialized");
        return result;
    }

    private static void toolList(Checks checks, McpStdioClient client) throws Exception {
        JsonNode tools = client.request("tools/list", CompactJson.object(), 60).path("result").path("tools");
        Set<String> names = StreamSupport.stream(tools.spliterator(), false).map(tool -> tool.path("name").asText())
                .collect(Collectors.toSet());
        checks.that("tools/list: the eight tools", names.equals(TOOLS), names.toString());
        checks.that("tools/list: object schemas", StreamSupport.stream(tools.spliterator(), false)
                .allMatch(tool -> "object".equals(tool.path("inputSchema").path("type").asText())));
        checks.that("tools/list: read-only hints", StreamSupport.stream(tools.spliterator(), false).allMatch(tool ->
                tool.path("annotations").path("readOnlyHint").asBoolean() != tool.path("name").asText().matches(
                        "build|test")));
    }

    private static void projectTools(Checks checks, McpStdioClient client) throws Exception {
        JsonNode modules = client.tool("list_modules", CompactJson.object(), 60);
        checks.that("list_modules: 4 modules, b depends on a", modules.path("count").asInt() == 4
                && "fix:a".equals(modules.path("modules").get(2).path("dependsOn").get(0).asText()),
                modules.toString());
        effectivePom(checks, client);
        dependencyTree(checks, client);
    }

    private static void effectivePom(Checks checks, McpStdioClient client) throws Exception {
        JsonNode pom = client.tool("effective_pom", CompactJson.object().put("project", "b"), BUILD_SECONDS);
        JsonNode b = pom.path("projects").get(0);
        checks.that("effective_pom: b with dependency a", !pom.path("isError").asBoolean()
                && "fix:b:0.1".equals(b.path("id").asText()) && contains(b.path("dependencies"), "fix:a:0.1 compile")
                && pom.path("sha256").asText().length() == 64, pom.toString());
        checks.that("effective_pom: pinned compiler plugin", StreamSupport.stream(b.path("plugins").spliterator(),
                false).anyMatch(plugin -> plugin.path("id").asText().endsWith("maven-compiler-plugin:3.13.0")));
        JsonNode root = client.tool("effective_pom", CompactJson.object(), BUILD_SECONDS).path("projects").get(0);
        checks.that("effective_pom: root alone with -N", "fix:fixture:0.1".equals(root.path("id").asText())
                && root.path("modules").size() == 3, root.toString());
    }

    private static void dependencyTree(Checks checks, McpStdioClient client) throws Exception {
        JsonNode tree = client.tool("dependency_tree", CompactJson.object().put("project", "a").put("scope", "test"),
                BUILD_SECONDS);
        checks.that("dependency_tree: junit in a's test scope", StreamSupport.stream(tree.path("nodes").spliterator(),
                false).anyMatch(node -> "junit:junit:4.13.2".equals(node.path("id").asText())), tree.toString());
        JsonNode bTree = client.tool("dependency_tree", CompactJson.object().put("project", "b"), BUILD_SECONDS);
        checks.that("dependency_tree: b depends on a", "fix:a:0.1".equals(bTree.path("nodes").get(0).path("id")
                .asText()) && "fix:b:0.1".equals(bTree.path("root").asText()), bTree.toString());
    }

    private static void describeGoal(Checks checks, McpStdioClient client) throws Exception {
        JsonNode compile = client.tool("describe_goal", CompactJson.object().put("goal", "compiler:compile"),
                BUILD_SECONDS);
        String compiler = "org.apache.maven.plugins:maven-compiler-plugin:3.13.0";
        checks.that("describe_goal: version from the root pom property", compiler.equals(compile.path("plugin")
                .asText()) && "pom.xml".equals(compile.path("versionSource").asText())
                && "compile".equals(compile.path("phase").asText()), compile.toString());
        checks.that("describe_goal: release parameter and its property", parameter(compile, "release")
                .map(release -> "maven.compiler.release".equals(release.path("property").asText())).orElse(false));
        describeByCoordinates(checks, client);
    }

    private static void describeByCoordinates(Checks checks, McpStdioClient client) throws Exception {
        JsonNode surefire = client.tool("describe_goal", CompactJson.object().put("goal",
                "org.apache.maven.plugins:maven-surefire-plugin:3.5.2:test"), BUILD_SECONDS);
        checks.that("describe_goal: full coordinates", parameter(surefire, "test")
                .map(test -> "test".equals(test.path("property").asText())).orElse(false), surefire.toString());
        JsonNode missing = client.tool("describe_goal", CompactJson.object().put("goal", "compiler:nosuch"),
                BUILD_SECONDS);
        checks.that("describe_goal: unknown goal lists the goals", missing.path("isError").asBoolean()
                && missing.path("error").asText().contains("testCompile"), missing.toString());
    }

    private static Optional<JsonNode> parameter(JsonNode goal, String name) {
        return StreamSupport.stream(goal.path("parameters").spliterator(), false)
                .filter(parameter -> name.equals(parameter.path("name").asText()))
                .findFirst();
    }

    private static boolean contains(JsonNode array, String text) {
        return StreamSupport.stream(array.spliterator(), false).anyMatch(item -> text.equals(item.asText()));
    }


    private static Path copyFixture(Path target) throws IOException {
        deleteTree(target);
        String files = new String(resource("FILES").readAllBytes(), StandardCharsets.UTF_8);
        Arrays.stream(files.split("\\R")).filter(file -> !file.isBlank()).forEach(file -> copy(target, file));
        return target;
    }

    private static void copy(Path target, String file) {
        Path out = target.resolve(file);
        try (InputStream in = resource(file)) {
            Files.createDirectories(out.getParent());
            Files.copy(in, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream resource(String path) {
        return Optional.ofNullable(McpProtocolTest.class.getResourceAsStream("/fixture/" + path))
                .orElseThrow(() -> new IllegalStateException("missing test resource fixture/" + path));
    }

    private static void deleteTree(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> tree = Files.walk(dir)) {
                tree.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }
}
