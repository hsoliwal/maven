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
package org.apache.maven.plugins.polyglot.adapters;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Writes the findings of one analyzer or linter run in the two formats the fork reads: SARIF 2.1.0 (one run, rules,
 * results located against the {@code SRCROOT} base) and build-events v1 {@code diagnostic} lines (the shape of
 * {@code org.apache.maven.agent.events.Diagnostic}; {@code tMs} is 0 because single diagnostics are not timed).
 * Findings are de-duplicated and sorted, so the same findings always give the same bytes. JDK only: the SARIF reader
 * of m3-analyzer-report-adapter reads this output back (its writer gap is recorded in FOSS_REUSE_DECISION.tsv).
 */
final class FindingReports {
    static final String SARIF_SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";
    static final String ROOT_BASE = "SRCROOT";

    /** The analyzer: SARIF driver name, version, information URI (may be empty) and build-events source. */
    record Tool(String name, String version, String informationUri, String source) { }

    private FindingReports() { }

    /** Writes both reports; {@code incomplete} names what was not analysed (non-empty = unsuccessful invocation). */
    static void write(Path jsonl, Path sarif, Tool tool, Path root, List<Finding> findings, List<String> incomplete)
            throws IOException {
        List<Finding> sorted = findings.stream().distinct().sorted(Finding.ORDER).toList();
        Files.writeString(parents(jsonl), events(tool, sorted), StandardCharsets.UTF_8);
        Files.writeString(parents(sarif), sarif(tool, root, sorted, incomplete), StandardCharsets.UTF_8);
    }

    private static Path parents(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        return file;
    }

    /** The file relative to the root with '/' separators, or its absolute {@code file:} URI when outside the root. */
    static String relative(Path root, Path file) {
        Path normal = file.toAbsolutePath().normalize();
        return normal.startsWith(root) ? root.relativize(normal).toString().replace('\\', '/') : normal.toUri().toString();
    }

    static String events(Tool tool, List<Finding> findings) {
        return IntStream.range(0, findings.size()).mapToObj(i -> event(i + 1, tool, findings.get(i)) + "\n")
                .collect(Collectors.joining());
    }

    private static String event(int seq, Tool tool, Finding f) {
        return "{\"v\":1,\"seq\":" + seq + ",\"tMs\":0,\"type\":\"diagnostic\",\"project\":null,\"source\":"
                + quote(tool.source()) + ",\"severity\":" + quote(f.level().severity()) + ",\"file\":" + quote(f.file())
                + ",\"line\":" + number(f.line()) + ",\"column\":" + number(f.column()) + ",\"rule\":" + quote(f.rule())
                + ",\"message\":" + quote(f.message()) + ",\"mojo\":null}";
    }

    static String sarif(Tool tool, Path root, List<Finding> findings, List<String> incomplete) {
        List<String> rules = findings.stream().map(Finding::rule).distinct().sorted().toList();
        return "{\"$schema\":" + quote(SARIF_SCHEMA) + ",\"version\":\"2.1.0\",\"runs\":[{\"tool\":{\"driver\":"
                + driver(tool, rules) + "},\"originalUriBaseIds\":{\"" + ROOT_BASE + "\":{\"uri\":" + quote(rootUri(root))
                + "}},\"invocations\":[" + invocation(incomplete) + "],\"results\":["
                + findings.stream().map(f -> result(f, rules)).collect(Collectors.joining(",")) + "]}]}\n";
    }

    private static String driver(Tool tool, List<String> rules) {
        String uri = tool.informationUri().isEmpty() ? "" : ",\"informationUri\":" + quote(tool.informationUri());
        return "{\"name\":" + quote(tool.name()) + ",\"version\":" + quote(tool.version()) + uri + ",\"rules\":["
                + rules.stream().map(r -> "{\"id\":" + quote(r) + "}").collect(Collectors.joining(",")) + "]}";
    }

    private static String invocation(List<String> incomplete) {
        String notes = incomplete.stream().map(n -> "{\"level\":\"warning\",\"message\":{\"text\":" + quote(n) + "}}")
                .collect(Collectors.joining(","));
        return "{\"executionSuccessful\":" + incomplete.isEmpty() + ",\"toolExecutionNotifications\":[" + notes + "]}";
    }

    private static String result(Finding f, List<String> rules) {
        return "{\"ruleId\":" + quote(f.rule()) + ",\"ruleIndex\":" + rules.indexOf(f.rule()) + ",\"level\":"
                + quote(f.level().sarif()) + ",\"message\":{\"text\":" + quote(f.message()) + "}" + location(f) + "}";
    }

    private static String location(Finding f) {
        if (f.file().isEmpty()) return "";
        boolean absolute = f.file().startsWith("file:");
        String artifact = "{\"uri\":" + quote(absolute ? f.file() : uri(f.file()))
                + (absolute ? "" : ",\"uriBaseId\":\"" + ROOT_BASE + "\"") + "}";
        String region = f.line() == 0 ? "" : ",\"region\":{\"startLine\":" + f.line()
                + (f.column() == 0 ? "" : ",\"startColumn\":" + f.column()) + "}";
        return ",\"locations\":[{\"physicalLocation\":{\"artifactLocation\":" + artifact + region + "}}]";
    }

    /** A relative path as a URI reference: characters URIs do not allow (spaces, ...) are percent-encoded. */
    private static String uri(String relative) {
        try {
            return new URI(null, null, relative, null).getRawPath();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("unrepresentable path: " + relative, e);
        }
    }

    private static String rootUri(Path root) {
        String uri = root.toAbsolutePath().normalize().toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }

    private static String number(long value) {
        return value > 0 ? Long.toString(value) : "null";
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        text.chars().forEach(c -> out.append(switch (c) {
            case '"' -> "\\\"";
            case '\\' -> "\\\\";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> c < 0x20 ? String.format(Locale.ROOT, "\\u%04x", c) : String.valueOf((char) c);
        }));
        return out.append('"').toString();
    }
}
