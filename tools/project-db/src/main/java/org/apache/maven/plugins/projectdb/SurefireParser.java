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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Surefire/Failsafe {@code TEST-*.xml} reports line by line, so that every test case keeps its report line, and
 * the first stack frame in the test class gives the source line. Statuses: passed, failed, error, skipped, and flaky
 * (a {@code flakyFailure}/{@code flakyError} rerun that finally passed). No DTD or entity is ever resolved.
 */
final class SurefireParser {
    private static final Pattern FRAME = Pattern.compile("at ([\\w.$]+)\\.([\\w$<>]+)\\(([\\w$]+\\.java):(\\d+)\\)");
    private static final List<String> OUTCOMES = List.of("failure", "error", "skipped", "flakyFailure", "flakyError");

    private SurefireParser() {}

    static List<Chunk> parse(Path root, Source source) throws IOException {
        Path report = source.files().get(0);
        List<String> lines = Text.lines(report);
        List<Chunk> chunks = new ArrayList<>();
        TestCase open = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains("<testcase ")) open = new TestCase(Text.attributes(line), i + 1);
            if (open == null) continue;
            open.read(line);
            if (closes(line)) chunks.add(open.chunk(root, source));
            if (closes(line)) open = null;
        }
        return chunks;
    }

    /** The end of a test case: its end tag, or a self-closing start tag. */
    private static boolean closes(String line) {
        return line.contains("</testcase>") || line.contains("<testcase ") && line.strip().endsWith("/>");
    }

    /** One test case being read: its attributes, report line, outcome, message and first matching frame. */
    private static final class TestCase {
        private final Map<String, String> attributes;
        private final int line;
        private String outcome = "";
        private String message = "";
        private String type = "";
        private String frameFile = "";
        private int frameLine;

        TestCase(Map<String, String> attributes, int line) {
            this.attributes = attributes;
            this.line = line;
        }

        void read(String text) {
            for (String candidate : OUTCOMES) {
                if (text.contains("<" + candidate + " ") || text.contains("<" + candidate + ">")
                        || text.contains("<" + candidate + "/>")) {
                    outcome(candidate, Text.attributes(text.substring(text.indexOf("<" + candidate))));
                }
            }
            Matcher frame = FRAME.matcher(text);
            String owner = frame.find() ? frame.group(1).replaceAll("\\$.*", "") : "";
            if (frameFile.isEmpty() && !owner.isEmpty() && owner.equals(attributes.getOrDefault("classname", ""))) {
                int dot = owner.lastIndexOf('.');
                frameFile = (dot < 0 ? "" : owner.substring(0, dot).replace('.', '/') + "/") + frame.group(3);
                frameLine = Integer.parseInt(frame.group(4));
            }
        }

        /** failure/error win over flaky reruns; the first message of the winning outcome is kept. */
        private void outcome(String candidate, Map<String, String> values) {
            boolean stronger = rank(candidate) > rank(outcome);
            if (stronger) outcome = candidate;
            if (stronger || message.isEmpty()) {
                message = values.getOrDefault("message", "");
                type = values.getOrDefault("type", "");
            }
        }

        private static int rank(String outcome) {
            return switch (outcome) {
                case "failure", "error" -> 3;
                case "flakyFailure", "flakyError" -> 2;
                case "skipped" -> 1;
                default -> 0;
            };
        }

        Chunk chunk(Path root, Source source) {
            String test = attributes.getOrDefault("classname", "") + "#" + attributes.getOrDefault("name", "");
            String status = status();
            String text = "test " + test + " " + status + " in module " + source.module()
                    + (message.isEmpty() && type.isEmpty() ? "" : ": " + Text.clip(type + " " + message, 400))
                    + (frameFile.isEmpty() ? "" : " at " + frameFile + ":" + frameLine);
            String file = sourceFile(root, source);
            return Chunk.of("test", source.module(), file.isEmpty() ? source.path() : file,
                    file.isEmpty() ? line : frameLine, text, Map.of("status", status, "test", test,
                            "class", attributes.getOrDefault("classname", ""), "report", source.path() + ":" + line));
        }

        private String status() {
            return switch (outcome) {
                case "failure" -> "failed";
                case "error" -> "error";
                case "skipped" -> "skipped";
                case "flakyFailure", "flakyError" -> "flaky";
                default -> "passed";
            };
        }

        /** The test source when the frame's file exists under a test or main source root of the module. */
        private String sourceFile(Path root, Source source) {
            if (frameFile.isEmpty()) return "";
            Path module = root.resolve(".".equals(source.module()) ? "" : source.module());
            for (String sourceRoot : List.of("src/test/java/", "src/main/java/", "src/it/java/")) {
                Path candidate = module.resolve(sourceRoot + frameFile);
                if (Files.isRegularFile(candidate)) return Sources.relative(root, candidate);
            }
            return "";
        }
    }
}
