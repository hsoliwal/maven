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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The protocol-test checks that run Maven or read run results: builds, tests, explain_failure and build_events,
 * refused calls, one run at a time, and the time limit with its process-tree stop.
 */
final class McpRunChecks {

    static final int BUILD_SECONDS = 600;

    private McpRunChecks() {}

    static void builds(Checks checks, McpStdioClient client, boolean events) throws Exception {
        JsonNode ok = build(client, "[\"compile\"]", "[\"a\"]");
        checks.that("build: compile a succeeds", "success".equals(ok.path("outcome").asText())
                && ok.path("exitCode").asInt() == 0 && "mvn -pl a -am compile".equals(ok.path("command").asText())
                && Files.isRegularFile(Path.of(ok.path("files").path("log").asText())), ok.toString());
        javaVersion(checks, ok);
        JsonNode broken = build(client, "[\"test-compile\"]", "[]");
        checks.that("build: reactor fails on c", "failure".equals(broken.path("outcome").asText())
                && broken.path("exitCode").asInt() != 0, broken.toString());
        checks.that("build: the failure names Broken.java", events
                ? broken.path("events").path("diagnostics").toString().contains("Broken.java")
                : !broken.path("events").path("available").asBoolean()
                        && broken.path("events").path("reason").asText().contains("build-events.jsonl")
                        && broken.path("logErrors").toString().contains("Broken.java"));
    }

    /** Maven ran on the JDK given with --java-home: the one running this test (-V prints it into build.log). */
    static void javaVersion(Checks checks, JsonNode run) throws IOException {
        String log = Files.readString(Path.of(run.path("files").path("log").asText()), StandardCharsets.ISO_8859_1);
        checks.that("build: Maven ran on the requested JDK " + System.getProperty("java.version"),
                log.contains("Java version: " + System.getProperty("java.version") + ",")
                        && System.getProperty("java.home").equals(run.path("launcher").path("javaHome").asText()),
                run.path("launcher").toString());
    }

    static void tests(Checks checks, McpStdioClient client) throws Exception {
        JsonNode pass = client.tool("test", CompactJson.object().put("selection", "ATest#adds").put("project", "a"),
                BUILD_SECONDS);
        checks.that("test: ATest#adds passes", "success".equals(pass.path("outcome").asText())
                && pass.path("command").asText().contains("-Dtest=ATest#adds"), pass.toString());
        JsonNode fail = client.tool("test", CompactJson.object().put("selection", "ATest#subtracts").put("project",
                "a"), BUILD_SECONDS);
        checks.that("test: ATest#subtracts fails and is named", "failure".equals(fail.path("outcome").asText())
                && fail.toString().contains("subtracts"), fail.toString());
    }

    static void results(Checks checks, McpStdioClient client, boolean events) throws Exception {
        JsonNode stream = client.tool("build_events", CompactJson.object(), 60);
        checks.that("build_events: latest run, or why it is absent", events
                ? stream.path("total").asInt() > 0
                : !stream.path("available").asBoolean() && stream.path("run").asInt() == 4, stream.toString());
        JsonNode latest = client.tool("explain_failure", CompactJson.object(), 60);
        checks.that("explain_failure: latest run failed", "failure".equals(latest.path("outcome").asText())
                && (events ? latest.path("available").asBoolean()
                        : latest.path("logErrors").toString().contains("subtracts")), latest.toString());
        JsonNode compile = client.tool("explain_failure", CompactJson.object().put("run", "2"), 60);
        checks.that("explain_failure: run 2 names Broken.java", compile.toString().contains("Broken.java"));
        JsonNode success = client.tool("explain_failure", CompactJson.object().put("run", "1"), 60);
        checks.that("explain_failure: run 1 succeeded", "success".equals(success.path("outcome").asText()));
        JsonNode missing = client.tool("build_events", CompactJson.object().put("run", "99"), 60);
        checks.that("build_events: unknown run is an error", missing.path("isError").asBoolean());
    }

    static void refusals(Checks checks, McpStdioClient client, Path runs) throws Exception {
        long before = runCount(runs);
        checks.that("refused: option as goal", refused(build(client, "[\"-X\"]", "[]"), "goal is not allowed"));
        checks.that("refused: cmd metacharacter", refused(build(client, "[\"compile&calc\"]", "[]"), "not allowed"));
        checks.that("refused: option as project", refused(build(client, "[\"compile\"]", "[\"--also-make\"]"),
                "project is not allowed"));
        JsonNode property = client.tool("build", CompactJson.parse("{\"goals\":[\"compile\"],\"properties\":"
                + "{\"maven.ext.class.path\":\"evil.jar\"}}"), 60);
        checks.that("refused: maven.ext.class.path", refused(property, "set by the server"));
        JsonNode selection = client.tool("test", CompactJson.object().put("selection", "ATest;calc"), 60);
        checks.that("refused: shell separator in selection", refused(selection, "test selection"));
        checks.that("refusals started no Maven run", runCount(runs) == before);
    }

    /** Requests the SDK refuses before a handler runs: a schema violation and an unknown tool. */
    static void protocolRefusals(Checks checks, McpStdioClient client, Path runs) throws Exception {
        long before = runCount(runs);
        JsonNode noGoals = client.request("tools/call", CompactJson.parse("{\"name\":\"build\",\"arguments\":{}}"), 60);
        checks.that("refused: missing goals", noGoals.has("error") || noGoals.path("result").path("isError")
                .asBoolean(), noGoals.toString());
        JsonNode unknown = client.request("tools/call", CompactJson.parse("{\"name\":\"nope\",\"arguments\":{}}"), 60);
        checks.that("refused: unknown tool", unknown.has("error") || unknown.path("result").path("isError")
                .asBoolean(), unknown.toString());
        checks.that("protocol refusals started no Maven run", runCount(runs) == before);
    }

    /** Two builds sent together: the second is refused while the first holds the project root. */
    static void concurrency(Checks checks, McpStdioClient client) throws Exception {
        JsonNode params = CompactJson.parse("{\"name\":\"build\",\"arguments\":{\"goals\":[\"validate\"]}}");
        int first = client.send("tools/call", params);
        int second = client.send("tools/call", params);
        List<JsonNode> answers = List.of(McpStdioClient.toolResult(client.await(first, BUILD_SECONDS)),
                McpStdioClient.toolResult(client.await(second, BUILD_SECONDS)));
        long refused = answers.stream().filter(answer -> refused(answer, "another Maven run")).count();
        long succeeded = answers.stream().filter(answer -> "success".equals(answer.path("outcome").asText())).count();
        checks.that("one Maven run at a time per root", refused == 1 && succeeded == 1, answers.toString());
    }

    /**
     * A test that runs past the time limit: the run reports a timeout, and the whole process tree is stopped, so the
     * Surefire fork stops writing its heartbeat (stopping only the top process leaves it running).
     */
    static void timeout(Checks checks, McpStdioClient client, Path fixture) throws Exception {
        JsonNode slow = client.tool("test", CompactJson.parse("{\"selection\":\"SlowTest\",\"project\":\"a\","
                + "\"timeoutSeconds\":45}"), BUILD_SECONDS);
        checks.that("timeout: reported and bounded", "timeout".equals(slow.path("outcome").asText())
                && slow.path("timedOut").asBoolean() && slow.path("durationMs").asLong() < 90_000, slow.toString());
        Path beat = fixture.resolve("a").resolve("target").resolve("heartbeat.txt");
        String first = heartbeat(beat);
        Thread.sleep(4000L);
        String later = heartbeat(beat);
        checks.that("timeout: the forked test JVM stopped with Maven", !first.isEmpty() && first.equals(later),
                "heartbeat " + first + " -> " + later);
    }

    /** The slow test's counter after a one-second settle, or "" when the test never started. */
    static String heartbeat(Path beat) throws IOException, InterruptedException {
        Thread.sleep(1000L);
        return Files.isRegularFile(beat) ? Files.readString(beat, StandardCharsets.US_ASCII) : "";
    }

    static JsonNode build(McpStdioClient client, String goals, String projects) throws Exception {
        return client.tool("build", CompactJson.parse("{\"goals\":" + goals + ",\"projects\":" + projects + "}"),
                BUILD_SECONDS);
    }

    static boolean refused(JsonNode answer, String text) {
        return answer.path("isError").asBoolean() && answer.path("error").asText().contains(text);
    }

    static long runCount(Path runs) throws IOException {
        try (Stream<Path> entries = Files.list(runs)) {
            return entries.filter(path -> path.getFileName().toString().matches("[0-9]+")).count();
        }
    }
}
