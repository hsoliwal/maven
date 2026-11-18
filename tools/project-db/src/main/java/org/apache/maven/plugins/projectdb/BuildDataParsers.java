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
package org.apache.maven.plugins.projectdb;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Line-oriented readers of the effective POM, the dependency tree, the build log and build events. Each chunk keeps
 * the 1-based line it was read from.
 */
final class BuildDataParsers {
    private static final Pattern FIELD = Pattern.compile("<(groupId|artifactId|version|scope)>([^<]*)</");
    private static final Pattern LOCATION = Pattern.compile("([^\\s\\[\\]]+\\.\\w+):\\[(\\d+),(\\d+)\\]");
    private static final Pattern TEST_LINE = Pattern.compile("^\\s*([\\w$]+)\\.([\\w$]+):(\\d+)\\s+(.*)$");
    private static final List<String> NOISE = List.of("-> [Help", "Re-run Maven", "For more information",
            "To see the full stack", "Re-run", "", "After correcting", "[Help", "mvn <args>", "Failures:", "Errors:",
            "Tests run:", "Please refer to", "See ");
    private static final Pattern ELAPSED = Pattern.compile("\\s*-- Time elapsed: [0-9.,]+ s");
    private static final Pattern RUN = Pattern.compile("^Run \\d+:\\s*");

    private BuildDataParsers() {}

    /** {@code dependency:tree} output: one chunk per dependency with the path that brings it in. */
    static List<Chunk> dependencyTree(Source source) throws IOException {
        List<String> lines = Text.lines(source.files().get(0));
        List<String> path = new ArrayList<>();
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            int start = firstCoordinate(lines.get(i));
            if (start < 0) continue;
            int depth = start / 3;
            String coordinates = lines.get(i).substring(start).strip();
            while (path.size() > depth) path.remove(path.size() - 1);
            path.add(coordinates);
            if (depth > 0 && !path.isEmpty()) chunks.add(dependency(source, path, i + 1));
        }
        return chunks;
    }

    private static int firstCoordinate(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (Character.isLetterOrDigit(line.charAt(i))) return line.indexOf(':', i) > 0 ? i : -1;
        }
        return -1;
    }

    private static Chunk dependency(Source source, List<String> path, int line) {
        String node = path.get(path.size() - 1);
        String root = path.get(0);
        String via = path.size() > 1 ? path.get(1) : node;
        String text = path.size() == 2 ? node + " is a direct dependency of " + root
                : node + " is brought into " + root + " by " + via + " (path: " + String.join(" -> ", path) + ")";
        String[] parts = node.split(":");
        return Chunk.of("dependency", source.module(), source.path(), line, text, Map.of("artifact", Text.ga(node),
                "via", Text.ga(via), "depth", Integer.toString(path.size() - 1),
                "scope", parts.length >= 5 ? parts[parts.length - 1].replaceAll("\\s.*", "") : ""));
    }

    /** Effective POM: one chunk per dependency and per plugin block, at the line the block starts. */
    static List<Chunk> pom(Source source) throws IOException {
        List<String> lines = Text.lines(source.files().get(0));
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String tag = lines.get(i).strip();
            if (!tag.equals("<dependency>") && !tag.equals("<plugin>")) continue;
            String block = block(lines, i, tag.equals("<plugin>") ? "</plugin>" : "</dependency>");
            chunks.add(pomBlock(source, tag.equals("<plugin>") ? "plugin" : "dependency", block, i + 1));
        }
        return chunks;
    }

    private static String block(List<String> lines, int from, String end) {
        StringBuilder text = new StringBuilder();
        for (int i = from; i < lines.size() && !lines.get(i).contains(end); i++) text.append(lines.get(i)).append(' ');
        return text.toString();
    }

    private static Chunk pomBlock(Source source, String what, String block, int line) {
        Map<String, String> fields = new java.util.TreeMap<>();
        Matcher matcher = FIELD.matcher(block);
        while (matcher.find()) fields.putIfAbsent(matcher.group(1), matcher.group(2).strip());
        String ga = fields.getOrDefault("groupId", "org.apache.maven.plugins") + ":" + fields.getOrDefault("artifactId", "");
        String text = "effective POM of module " + source.module() + " declares " + what + " " + ga + ":"
                + fields.getOrDefault("version", "") + (fields.containsKey("scope") ? " scope " + fields.get("scope") : "");
        return Chunk.of("pom", source.module(), source.path(), line, text,
                Map.of("artifact", ga, "element", what, "scope", fields.getOrDefault("scope", "")));
    }

    /** Maven console log: [ERROR] lines (noise removed) and BUILD FAILURE, with file:[line,col] when present. */
    static List<Chunk> buildLog(Path root, Source source) throws IOException {
        List<String> lines = Text.lines(source.files().get(0));
        Map<String, Chunk> chunks = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = Text.stripAnsi(lines.get(i)).strip();
            if (line.contains("BUILD FAILURE")) {
                chunks.putIfAbsent("", Chunk.of("build-error", ".", source.path(), i + 1,
                        "the build failed: BUILD FAILURE", Map.of("status", "failure", "signal", "build-failure")));
            } else if (line.startsWith("[ERROR]") && !noise(line.substring(7).strip())) {
                String message = normalized(line.substring(7));
                chunks.putIfAbsent(message, errorLine(root, source, message, i + 1));
            }
        }
        return List.copyOf(chunks.values());
    }

    /** Without timings and rerun numbers, so that a rerun's repeated error is one chunk (its first line). */
    private static String normalized(String message) {
        return RUN.matcher(ELAPSED.matcher(message.strip()).replaceAll("")).replaceFirst("").strip();
    }

    private static boolean noise(String message) {
        return NOISE.stream().anyMatch(prefix -> prefix.isEmpty() ? message.isEmpty() : message.startsWith(prefix));
    }

    private static Chunk errorLine(Path root, Source source, String message, int line) {
        Matcher location = LOCATION.matcher(message);
        Matcher test = TEST_LINE.matcher(message);
        String at = location.find() ? Sources.relative(root, Path.of(location.group(1))) + ":" + location.group(2)
                : test.matches() ? test.group(1) + ".java:" + test.group(3) : "";
        String text = "build error: " + Text.clip(message, 500) + (at.isEmpty() ? "" : " (at " + at + ")");
        return Chunk.of("build-error", ".", source.path(), line, text,
                Map.of("status", "failure", "signal", test.matches() ? "test" : "error", "at", at));
    }

    /** Build events (JSON Lines): one chunk per event with its scalar fields as text and common keys as facets. */
    static List<Chunk> events(Source source) throws IOException {
        List<String> lines = Text.lines(source.files().get(0));
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) chunks.add(event(source, lines.get(i), i + 1));
        }
        return chunks;
    }

    private static Chunk event(Source source, String line, int number) {
        Map<String, String> fields = Json.flatObject(line);
        Map<String, String> facets = new java.util.TreeMap<>();
        for (String key : List.of("type", "event", "phase", "goal", "status", "module", "project")) {
            if (fields.containsKey(key)) facets.put(key, fields.get(key));
        }
        String body = fields.isEmpty() ? line : fields.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).reduce((a, b) -> a + " " + b).orElse("");
        return Chunk.of("event", source.module(), source.path(), number, "event " + Text.clip(body, 500), facets);
    }
}
