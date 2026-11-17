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
package org.apache.maven.plugins.codepatch;

import static org.apache.maven.plugins.codepatch.Checks.check;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Every method and constructor of the module (main and test sources) has at most 15 lines, counted from the first
 * line of its signature to its closing brace. Strings, character literals and comments are masked before braces are
 * counted.
 */
final class MethodLengthTest {
    private static final int LIMIT = 15;
    private static final Pattern TYPE = Pattern.compile("\\b(class|record|interface|enum)\\b");
    private static final Pattern ANNOTATIONS = Pattern.compile("^\\s*(@\\w+(\\([^)]*\\))?\\s*)*");
    private static final Pattern SIGNATURE = Pattern.compile("[\\w<>\\[\\],.?\\s]*\\w\\s*");

    private MethodLengthTest() { }

    static void run(Path module) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(module.resolve("src"))) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java")).sorted()::iterator) {
                found.addAll(scan(module, file));
            }
        }
        check("every method has at most " + LIMIT + " lines" + (found.isEmpty() ? "" : ": " + found), found.isEmpty());
    }

    static List<String> scan(Path module, Path file) throws IOException {
        Scan scan = new Scan();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) scan.line(i + 1, lines.get(i));
        String name = module.relativize(file).toString().replace('\\', '/');
        return scan.found.stream().map(f -> name + ":" + f).collect(Collectors.toList());
    }

    /** Brace state of one file: each open brace records the first line of its method (0: a type body, -1: other). */
    private static final class Scan {
        private final Deque<Integer> opened = new ArrayDeque<>();
        private final List<String> found = new ArrayList<>();
        private final StringBuilder code = new StringBuilder();
        private boolean comment;
        private int candidate;

        void line(int number, String raw) {
            String text = mask(raw);
            boolean type = TYPE.matcher(text).find();
            if (candidate == 0 && !type && inType() && signature(text)) candidate = number;
            for (char c : text.toCharArray()) {
                if (c == '{') open(type);
                if (c == '}') close(number);
            }
            if (text.strip().endsWith(";")) candidate = 0;
        }

        private boolean inType() {
            return opened.isEmpty() || opened.peek() == 0;
        }

        private static boolean signature(String text) {
            String code = ANNOTATIONS.matcher(text).replaceFirst("");
            int paren = code.indexOf('(');
            return paren > 0 && SIGNATURE.matcher(code.substring(0, paren)).matches();
        }

        private void open(boolean type) {
            opened.push(type ? 0 : inType() && candidate > 0 ? candidate : -1);
            if (opened.peek() > 0) candidate = 0;
        }

        private void close(int number) {
            int start = opened.isEmpty() ? -1 : opened.pop();
            if (start > 0 && number - start + 1 > LIMIT) found.add(start + ": " + (number - start + 1) + " lines");
        }

        /** The line without string and character literal contents and without comments. */
        private String mask(String raw) {
            code.setLength(0);
            for (int i = 0; i < raw.length(); i++) i = maskAt(raw, i);
            return code.toString();
        }

        private int maskAt(String raw, int i) {
            char c = raw.charAt(i);
            if (comment) {
                if (raw.startsWith("*/", i)) comment = false;
                return comment ? i : i + 1;
            }
            if (raw.startsWith("/*", i)) comment = true;
            if (raw.startsWith("//", i)) return raw.length();
            if (c == '"' || c == '\'') return skipLiteral(raw, i, c);
            if (!comment) code.append(c);
            return comment ? i + 1 : i;
        }

        private int skipLiteral(String raw, int i, char quote) {
            int at = i + 1;
            while (at < raw.length() && raw.charAt(at) != quote) at += raw.charAt(at) == '\\' ? 2 : 1;
            code.append(quote).append(quote);
            return at;
        }
    }
}
