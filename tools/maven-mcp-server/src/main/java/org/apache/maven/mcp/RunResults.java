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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Reads what a run left behind: the build event stream and the failure digest that the build-events extension
 * writes (formats: README, "Files the server reads"), and the Maven log. The server only reads these files; it does not
 * parse compiler or test output itself. When the extension did not run, results say so and carry the first
 * {@code [ERROR]} lines of the log instead, without the credentials of URLs ({@link CompactJson#cap} redacts).
 */
final class RunResults {

    static final int PRINT_LIMIT = 20;

    private static final int MAX_EVENT_LINES = 1_000_000;
    private static final int MAX_EVENT_CHARS = 4000;
    private static final long MAX_DIGEST_BYTES = 1L << 20;
    private static final String TRACE_HINT = "[ERROR] To see the full stack trace";
    private static final Set<String> FAILED = Set.of("failed", "error");

    /** The parsed lines of an event stream and how many lines were not JSON. */
    record EventLines(List<JsonNode> events, int invalid) {}

    private RunResults() {}

    /** The answer of build and test: outcome, the extension's results or why they are absent, and file paths. */
    static ObjectNode summary(Path dir, MavenLauncher.MavenRun run) throws IOException {
        ObjectNode out = CompactJson.object()
                .put("run", RunStore.number(dir))
                .put("outcome", outcome(run))
                .put("exitCode", run.exitCode())
                .put("timedOut", run.timedOut())
                .put("durationMs", run.durationMs());
        out.set("events", eventSummary(dir));
        out.set("digest", digest(dir, outcome(run)));
        if (!out.path("events").path("available").asBoolean()) {
            out.set("logErrors", logErrors(dir));
        }
        out.set("files", files(dir));
        return out.set("launcher", launcher(run));
    }

    private static ObjectNode launcher(MavenLauncher.MavenRun run) {
        return CompactJson.object()
                .put("source", run.launcher().source())
                .put("script", run.launcher().script().toString())
                .put("javaHome", run.javaHome().toString());
    }

    static String outcome(MavenLauncher.MavenRun run) {
        return run.timedOut() ? "timeout" : run.exitCode() == 0 ? "success" : "failure";
    }

    /** One page of the event stream, filtered by event types and project. */
    static ObjectNode events(Path dir, Set<String> types, String project, int offset, int limit) throws IOException {
        Path file = dir.resolve(ServerArguments.EVENTS);
        ObjectNode out = CompactJson.object().put("run", RunStore.number(dir)).put("file", file.toString());
        if (!Files.isRegularFile(file)) {
            return out.put("available", false).put("reason", eventsAbsent(dir));
        }
        List<JsonNode> matches = readEvents(file).events().stream()
                .filter(event -> types.isEmpty() || types.contains(event.path("type").asText()))
                .filter(event -> project.isEmpty() || sameProject(event, project))
                .collect(Collectors.toList());
        List<JsonNode> page = matches.stream().skip(offset).limit(limit).map(RunResults::bounded)
                .collect(Collectors.toList());
        out.put("available", true).put("total", matches.size()).put("offset", offset).put("returned", page.size());
        out.putArray("events").addAll(page);
        return offset + page.size() < matches.size() ? out.put("nextOffset", offset + page.size()) : out;
    }

    /** The failure digest of a run, or why there is none plus the log's first error lines. */
    static ObjectNode failure(Path dir) throws IOException {
        Path file = dir.resolve(ServerArguments.FAILURE);
        String outcome = RunStore.summary(dir).map(run -> run.path("outcome").asText()).orElse("unknown");
        ObjectNode out = CompactJson.object().put("run", RunStore.number(dir)).put("outcome", outcome);
        if (!Files.isRegularFile(file)) {
            out.put("available", false).put("reason", failureAbsent(dir, outcome));
            return out.set("logErrors", logErrors(dir));
        }
        if (Files.size(file) > MAX_DIGEST_BYTES) {
            throw new IOException(file + " is larger than " + MAX_DIGEST_BYTES + " bytes");
        }
        out.put("available", true).put("file", file.toString());
        markdown(dir).ifPresent(md -> out.put("markdown", md.toString()));
        return out.set("digest", CompactJson.parse(Files.readString(file, StandardCharsets.UTF_8)));
    }

    /** The first {@value #PRINT_LIMIT} non-empty {@code [ERROR]} lines of the log, before Maven's help footer. */
    static ArrayNode logErrors(Path dir) throws IOException {
        Path log = Files.isRegularFile(dir.resolve(ServerArguments.LOG))
                ? dir.resolve(ServerArguments.LOG)
                : dir.resolve(MavenLauncher.CONSOLE);
        ArrayNode errors = CompactJson.array();
        if (Files.isRegularFile(log)) {
            readLines(log, lines -> lines.takeWhile(line -> !line.startsWith(TRACE_HINT))
                    .filter(line -> line.startsWith("[ERROR]"))
                    .map(line -> line.substring("[ERROR]".length()).trim())
                    .filter(line -> !line.isEmpty())
                    .limit(PRINT_LIMIT)
                    .map(line -> CompactJson.cap(line, CompactJson.MESSAGE_CHARS))
                    .collect(Collectors.toList())).forEach(errors::add);
        }
        return errors;
    }

    static EventLines readEvents(Path file) throws IOException {
        List<Optional<JsonNode>> parsed = readLines(file, lines -> lines.limit(MAX_EVENT_LINES)
                .filter(line -> !line.isBlank())
                .map(RunResults::parseLine)
                .collect(Collectors.toList()));
        List<JsonNode> events = parsed.stream().flatMap(Optional::stream).collect(Collectors.toList());
        return new EventLines(events, parsed.size() - events.size());
    }

    private static ObjectNode eventSummary(Path dir) throws IOException {
        Path file = dir.resolve(ServerArguments.EVENTS);
        ObjectNode out = CompactJson.object().put("file", file.toString());
        if (!Files.isRegularFile(file)) {
            return out.put("available", false).put("reason", eventsAbsent(dir));
        }
        EventLines lines = readEvents(file);
        out.put("available", true).put("count", lines.events().size());
        if (lines.invalid() > 0) {
            out.put("invalidLines", lines.invalid());
        }
        ofType(lines, "session-end").reduce((first, second) -> second).ifPresent(end -> out.set("sessionEnd", end));
        CompactJson.capped(out, "diagnostics", ofType(lines, "diagnostic").collect(Collectors.toList()), PRINT_LIMIT);
        return CompactJson.capped(out, "failedTests", failedTests(lines), PRINT_LIMIT);
    }

    private static List<JsonNode> failedTests(EventLines lines) {
        return ofType(lines, "test")
                .filter(test -> FAILED.contains(test.path("outcome").asText()))
                .map(RunResults::withoutStack)
                .collect(Collectors.toList());
    }

    /** A test event without its stack lines, which explain_failure and build_events still carry. */
    private static JsonNode withoutStack(JsonNode test) {
        ObjectNode copy = test.deepCopy();
        copy.remove("stack");
        return copy;
    }

    private static ObjectNode digest(Path dir, String outcome) throws IOException {
        Path file = dir.resolve(ServerArguments.FAILURE);
        ObjectNode out = CompactJson.object().put("file", file.toString());
        if (!Files.isRegularFile(file)) {
            return out.put("available", false).put("reason", failureAbsent(dir, outcome));
        }
        out.put("available", true);
        markdown(dir).ifPresent(md -> out.put("markdown", md.toString()));
        return out;
    }

    /** The run's files; the extension's one-screen summary.txt when it wrote one. */
    private static ObjectNode files(Path dir) {
        ObjectNode files = CompactJson.object()
                .put("dir", dir.toString())
                .put("log", dir.resolve(ServerArguments.LOG).toString())
                .put("console", dir.resolve(MavenLauncher.CONSOLE).toString());
        Optional.of(dir.resolve(ServerArguments.SUMMARY))
                .filter(Files::isRegularFile)
                .ifPresent(summary -> files.put("summary", summary.toString()));
        return files;
    }

    /** Why a run has no event stream: stopped at the time limit (run.json says so), else no extension ran. */
    private static String eventsAbsent(Path dir) throws IOException {
        Optional<JsonNode> run = RunStore.summary(dir);
        if (run.map(summary -> "timeout".equals(summary.path("outcome").asText())).orElse(false)) {
            return ServerArguments.EVENTS + " was not written: Maven was stopped at the time limit after "
                    + run.get().path("durationMs").asLong() / 1000 + " s, before the build-events extension published"
                    + " its files at the end of the build (raise timeoutSeconds)";
        }
        return ServerArguments.EVENTS + " was not written: no build-events extension ran in this build (start the"
                + " server with --extension <jar>, or use a Maven distribution that has the extension in lib/ext)";
    }

    private static String failureAbsent(Path dir, String outcome) throws IOException {
        if ("success".equals(outcome)) {
            return "the run succeeded, so there is no failure digest";
        }
        return Files.isRegularFile(dir.resolve(ServerArguments.EVENTS))
                ? ServerArguments.FAILURE + " was not written by the build-events extension"
                : eventsAbsent(dir);
    }

    private static Optional<Path> markdown(Path dir) {
        return Optional.of(dir.resolve(ServerArguments.FAILURE_MD)).filter(Files::isRegularFile);
    }

    private static Stream<JsonNode> ofType(EventLines lines, String type) {
        return lines.events().stream().filter(event -> type.equals(event.path("type").asText()));
    }

    /** True when the event names the project as given, as its path, or as the artifactId after "groupId:". */
    private static boolean sameProject(JsonNode event, String project) {
        String id = event.path("project").asText();
        return project.equals(id) || project.equals(event.path("path").asText()) || id.endsWith(":" + project);
    }

    private static JsonNode bounded(JsonNode event) {
        if (CompactJson.text(event).length() <= MAX_EVENT_CHARS) {
            return event;
        }
        return CompactJson.object()
                .put("seq", event.path("seq").asLong())
                .put("type", event.path("type").asText())
                .put("truncated", true);
    }

    private static Optional<JsonNode> parseLine(String line) {
        try {
            return Optional.of(CompactJson.parse(line));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Reads a text file as UTF-8 with malformed bytes replaced, so a log in another encoding still reads. */
    private static <T> T readLines(Path file, Function<Stream<String>, T> reader) throws IOException {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
            return reader.apply(in.lines());
        }
    }
}
