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
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The readers behind the tools, on synthetic files in the formats of the README section "Files the server reads"
 * and on the fixture reactor: event paging and filters, the build summary caps, the failure digest, the log
 * fallback, run directory rotation, the POM scan, and the effective POM and dependency tree views.
 */
public final class ResultReadersTest {

    private ResultReadersTest() {}

    public static void main(String[] args) throws IOException, URISyntaxException {
        Path work = Path.of(args[0]).toAbsolutePath();
        deleteTree(work);
        Checks checks = new Checks("ResultReaders");
        RunStore store = new RunStore(work.resolve("runs"), 2);
        Path failed = store.allocate();
        writeEventRun(failed);
        events(checks, failed);
        summary(checks, failed);
        digest(checks, failed);
        absent(checks, store.allocate(), store.allocate());
        rotation(checks, store);
        timedOut(checks, new RunStore(work.resolve("runs-timeout"), 2).allocate());
        credentials(checks, new RunStore(work.resolve("runs-credentials"), 2).allocate());
        views(checks, work);
        checks.finish();
    }

    private static void writeEventRun(Path dir) throws IOException {
        Files.write(dir.resolve(ServerArguments.EVENTS), eventLines(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(ServerArguments.FAILURE), "{\"v\":1,\"outcome\":\"failure\",\"failures\":"
                + "[{\"project\":\"fix:c\",\"kind\":\"compile\",\"reproduce\":\"mvn -pl c -am compile\"}]}");
        RunStore.record(dir, CompactJson.object().put("tool", "build").put("outcome", "failure"));
    }

    /** 30 events in the stream format and one line that is not JSON. */
    private static List<String> eventLines() {
        List<String> lines = new ArrayList<>();
        lines.add("{\"v\":1,\"seq\":1,\"tMs\":0,\"type\":\"session-start\",\"goals\":[\"verify\"]}");
        lines.add("{\"v\":1,\"seq\":2,\"tMs\":5,\"type\":\"project-start\",\"index\":1,\"project\":\"fix:b\","
                + "\"path\":\"b\"}");
        IntStream.range(0, 25).mapToObj(ResultReadersTest::diagnostic).forEach(lines::add);
        lines.add("not json");
        lines.add("{\"v\":1,\"seq\":40,\"tMs\":20,\"type\":\"test\",\"project\":\"fix:a\",\"class\":\"fix.a.ATest\","
                + "\"method\":\"subtracts\",\"outcome\":\"failed\",\"message\":\"expected:<5> but was:<-1>\","
                + "\"stack\":[\"at fix.a.ATest.subtracts(ATest.java:12)\"]}");
        lines.add("{\"v\":1,\"seq\":41,\"tMs\":21,\"type\":\"test\",\"project\":\"fix:a\",\"class\":\"fix.a.ATest\","
                + "\"method\":\"adds\",\"outcome\":\"passed\"}");
        lines.add("{\"v\":1,\"seq\":42,\"tMs\":30,\"type\":\"session-end\",\"outcome\":\"failure\"}");
        return lines;
    }

    /** Diagnostics 0 to 4 belong to fix:b, the rest to fix:c. */
    private static String diagnostic(int n) {
        return "{\"v\":1,\"seq\":" + (3 + n) + ",\"tMs\":9,\"type\":\"diagnostic\",\"project\":\"fix:"
                + (n < 5 ? "b" : "c") + "\",\"source\":\"compiler\",\"severity\":\"ERROR\",\"file\":"
                + "\"c/src/main/java/fix/c/Broken.java\",\"line\":" + (n + 1) + ",\"column\":16,\"message\":"
                + "\"cannot find symbol\"}";
    }

    private static void events(Checks checks, Path dir) throws IOException {
        ObjectNode all = RunResults.events(dir, Set.of(), "", 0, 500);
        checks.that("all valid events, invalid line skipped", all.path("total").asInt() == 30, all.toString());
        ObjectNode first = RunResults.events(dir, Set.of("diagnostic"), "", 0, 10);
        checks.that("type filter and first page", first.path("total").asInt() == 25
                && first.path("returned").asInt() == 10 && first.path("nextOffset").asInt() == 10);
        ObjectNode last = RunResults.events(dir, Set.of("diagnostic"), "", 20, 10);
        checks.that("last page has no next offset", last.path("returned").asInt() == 5 && !last.has("nextOffset"));
        ObjectNode byProject = RunResults.events(dir, Set.of(), "b", 0, 500);
        checks.that("project filter by artifactId", byProject.path("total").asInt() == 6, byProject.toString());
        checks.that("project filter by path", RunResults.events(dir, Set.of("project-start"), "b", 0, 5)
                .path("total").asInt() == 1);
    }

    private static void summary(Checks checks, Path dir) throws IOException {
        MavenLauncher.MavenRun run = new MavenLauncher.MavenRun(1, false, 1234, List.of("verify"),
                new MavenLauncher.Launcher(Path.of("mvn"), "PATH"), Path.of("jdk"));
        ObjectNode out = RunResults.summary(dir, run);
        JsonNode events = out.path("events");
        checks.that("summary outcome", "failure".equals(out.path("outcome").asText()));
        checks.that("diagnostics capped at 20 with omitted count", events.path("diagnostics").size() == 20
                && events.path("diagnosticsOmitted").asInt() == 5, events.toString());
        checks.that("failed test without stack", events.path("failedTests").size() == 1
                && !events.path("failedTests").get(0).has("stack")
                && "subtracts".equals(events.path("failedTests").get(0).path("method").asText()));
        checks.that("session end and invalid line count", events.has("sessionEnd")
                && events.path("invalidLines").asInt() == 1);
        checks.that("digest present, no log fallback", out.path("digest").path("available").asBoolean()
                && !out.has("logErrors"));
    }

    private static void digest(Checks checks, Path dir) throws IOException {
        ObjectNode failure = RunResults.failure(dir);
        checks.that("explain_failure passes the digest through", failure.path("available").asBoolean()
                && "fix:c".equals(failure.path("digest").path("failures").get(0).path("project").asText()));
    }

    /** A Maven log with a blank error line, 31 error lines, the help footer, and a byte that is not UTF-8. */
    private static void writeLog(Path dir) throws IOException {
        List<String> log = new ArrayList<>(List.of("[INFO] Scanning", "[ERROR] ", "[ERROR] first problem"));
        IntStream.range(0, 30).forEach(n -> log.add("[ERROR] problem " + n));
        log.add("[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.");
        log.add("[ERROR] after footer");
        Files.write(dir.resolve(ServerArguments.LOG), log, StandardCharsets.UTF_8);
        Files.write(dir.resolve(ServerArguments.LOG), new byte[] {'[', 'E', 'R', 'R', 'O', 'R', ']', ' ', (byte) 0xFF,
                '\n'}, java.nio.file.StandardOpenOption.APPEND);
    }

    private static void absent(Checks checks, Path failed, Path succeeded) throws IOException {
        writeLog(failed);
        RunStore.record(failed, CompactJson.object().put("outcome", "failure"));
        RunStore.record(succeeded, CompactJson.object().put("outcome", "success"));
        ObjectNode events = RunResults.events(failed, Set.of(), "", 0, 10);
        checks.that("absent stream says why", !events.path("available").asBoolean()
                && events.path("reason").asText().contains("build-events.jsonl"));
        ObjectNode failure = RunResults.failure(failed);
        JsonNode errors = failure.path("logErrors");
        checks.that("log fallback: 20 lines, blank skipped, first kept", errors.size() == 20
                && "first problem".equals(errors.get(0).asText()), failure.toString());
        checks.that("success has no digest to explain", RunResults.failure(succeeded).path("reason").asText()
                .contains("succeeded"));
    }

    /**
     * Maven prints a repository URL with its user and password in its own [ERROR] lines: the log fallback, a failed
     * query's error and every string of a tool result carry {@code ***@} instead, also when a line is capped.
     */
    private static void credentials(Checks checks, Path dir) throws IOException {
        Files.write(dir.resolve(ServerArguments.LOG), List.of("[ERROR] Failed to collect dependencies at fix:b:jar:1:"
                + " Could not transfer artifact x:y:pom:1 from/to private (https://alice:S3cretPw@127.0.0.1:9/repo):"
                + " Connect to 127.0.0.1:9 failed", "[ERROR] " + "x".repeat(280) + " https://alice:S3cretPw@h/r"),
                StandardCharsets.UTF_8);
        RunStore.record(dir, CompactJson.object().put("outcome", "failure"));
        String errors = RunResults.logErrors(dir).toString();
        checks.that("log errors: no password", !errors.contains("S3cretPw") && errors.contains("https://***@127.0.0.1"),
                errors);
        String failure = RunResults.failure(dir).toString();
        checks.that("explain_failure: no password", !failure.contains("S3cretPw"), failure);
        ObjectNode body = CompactJson.object().put("text", "clone https://u:S3cretPw@h/x");
        body.putArray("list").add("jdbc:mysql://u:S3cretPw@db").addObject().put("deep", "git+ssh://g:S3cretPw@h");
        String result = text(CompactJson.result(body));
        checks.that("every string of a result redacted", !result.contains("S3cretPw") && result.contains("***@"), result);
        String error = text(CompactJson.error("Maven failure: https://u:S3cretPw@h"));
        checks.that("an error result redacted", !error.contains("S3cretPw"), error);
        checks.that("a capped string redacted before the cut", !CompactJson.cap("https://alice:S3cretPw@h/x", 20)
                .contains("S3cret"), CompactJson.cap("https://alice:S3cretPw@h/x", 20));
    }

    private static String text(io.modelcontextprotocol.spec.McpSchema.CallToolResult result) {
        return ((io.modelcontextprotocol.spec.McpSchema.TextContent) result.content().get(0)).text();
    }

    /** A run stopped at its time limit says so, rather than that no extension ran. */
    private static void timedOut(Checks checks, Path dir) throws IOException {
        MavenLauncher.MavenRun run = new MavenLauncher.MavenRun(-1, true, 45_210, List.of("verify"),
                new MavenLauncher.Launcher(Path.of("mvn"), "PATH"), Path.of("jdk"));
        RunStore.record(dir, CompactJson.object().put("outcome", RunResults.outcome(run)).put("durationMs", 45_210));
        String reason = RunResults.summary(dir, run).path("events").path("reason").asText();
        checks.that("timeout: the stop is reported", reason.contains("stopped at the time limit after 45 s")
                && !reason.contains("no build-events extension"), reason);
        String digest = RunResults.failure(dir).path("reason").asText();
        checks.that("timeout: explain_failure reports the stop", digest.contains("stopped at the time limit"), digest);
        String page = RunResults.events(dir, Set.of(), "", 0, 10).path("reason").asText();
        checks.that("timeout: build_events reports the stop", page.contains("stopped at the time limit"), page);
    }

    private static void rotation(Checks checks, RunStore store) throws IOException {
        List<String> names = Files.list(store.runsDir()).map(path -> path.getFileName().toString()).sorted()
                .collect(Collectors.toList());
        checks.that("keeps the newest two runs", names.equals(List.of("2", "3")), names.toString());
        checks.that("latest is run 3", store.resolve("latest").map(RunStore::number).orElse(0) == 3);
        checks.that("a removed run does not resolve", store.resolve("1").isEmpty());
        Path query = store.query();
        Files.writeString(query.resolve("x"), "x");
        checks.that("query directory is emptied", Files.list(store.query()).count() == 0);
    }

    private static void views(Checks checks, Path work) throws IOException, URISyntaxException {
        Path fixture = Path.of(ResultReadersTest.class.getResource("/fixture/pom.xml").toURI()).getParent();
        JsonNode modules = ModuleScan.list(fixture);
        checks.that("pom scan finds 4 modules", modules.path("count").asInt() == 4, modules.toString());
        checks.that("b depends on a", "fix:a".equals(modules.path("modules").get(2).path("dependsOn").get(0).asText()));
        checks.that("module index starts at 1, as in the events and project.json", indexes(modules).equals(
                List.of(1, 2, 3, 4)), modules.toString());
        Path loop = Files.createDirectories(work.resolve("loop"));
        Files.writeString(loop.resolve("pom.xml"), "<project><groupId>g</groupId><artifactId>loop</artifactId>"
                + "<modules><module>.</module><module>pom.xml</module></modules></project>");
        checks.that("module cycle visited once", ModuleScan.list(loop).path("count").asInt() == 1);
        effectivePom(checks, work);
        dependencyTree(checks, work);
        checks.that("first sentence without tags", PluginDescriptors.firstSentence(
                "<p>Compiles <b>application</b> sources.  More &amp; more.</p>")
                .equals("Compiles application sources."));
    }

    private static List<Integer> indexes(JsonNode modules) {
        return java.util.stream.StreamSupport.stream(modules.path("modules").spliterator(), false)
                .map(module -> module.path("index").asInt()).collect(Collectors.toList());
    }

    private static void effectivePom(Checks checks, Path work) throws IOException {
        Path file = work.resolve("effective-pom.xml");
        Files.writeString(file, "<?xml version=\"1.0\"?><projects><project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<groupId>g</groupId><artifactId>a</artifactId><version>1</version><packaging>jar</packaging>"
                + "<dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId><version>2</version>"
                + "<scope>test</scope></dependency></dependencies><build><plugins><plugin><artifactId>maven-compiler-"
                + "plugin</artifactId><version>3.13.0</version><executions><execution><id>default-compile</id>"
                + "<phase>compile</phase><goals><goal>compile</goal></goals></execution></executions></plugin>"
                + "</plugins></build></project><project><groupId>g</groupId><artifactId>b</artifactId>"
                + "<version>1</version></project></projects>");
        JsonNode out = EffectivePom.summarize(file);
        JsonNode a = out.path("projects").get(0);
        checks.that("effective pom: two projects", out.path("projects").size() == 2, out.toString());
        checks.that("effective pom: dependency string", "x:y:2 test".equals(a.path("dependencies").get(0).asText()));
        checks.that("effective pom: execution string", "default-compile@compile:compile".equals(
                a.path("plugins").get(0).path("executions").get(0).asText()));
    }

    private static void dependencyTree(Checks checks, Path work) throws IOException {
        Path file = work.resolve("tree.json");
        Files.writeString(file, "{\"groupId\":\"g\",\"artifactId\":\"b\",\"version\":\"1\",\"type\":\"jar\","
                + "\"children\":[{\"groupId\":\"g\",\"artifactId\":\"a\",\"version\":\"1\",\"type\":\"jar\",\"scope\":"
                + "\"compile\",\"children\":[{\"groupId\":\"x\",\"artifactId\":\"z\",\"version\":\"3\",\"type\":"
                + "\"pom\",\"scope\":\"compile\",\"optional\":\"true\"}]}]}");
        JsonNode out = DependencyTree.summarize(file, 1);
        checks.that("tree: root and total", "g:b:1".equals(out.path("root").asText())
                && out.path("total").asInt() == 2, out.toString());
        checks.that("tree: capped with omitted count", out.path("nodes").size() == 1
                && out.path("nodesOmitted").asInt() == 1);
        checks.that("tree: depth and type", "x:z:pom:3".equals(DependencyTree.summarize(file, 5).path("nodes").get(1)
                .path("id").asText()));
    }

    private static void deleteTree(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var tree = Files.walk(dir)) {
                tree.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }
}
