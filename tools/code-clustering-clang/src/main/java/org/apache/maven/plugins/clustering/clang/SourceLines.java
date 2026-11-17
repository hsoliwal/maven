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
package org.apache.maven.plugins.clustering.clang;

import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A source file's lines, with the lines that lie inside a preprocessor conditional ({@code #if}, {@code #ifdef},
 * {@code #ifndef} up to the matching {@code #endif}). An include guard (an {@code #ifndef X} whose next directive is
 * {@code #define X}) does not count. Used to explain differences between front ends.
 */
final class SourceLines {
    private static final Pattern DIRECTIVE = Pattern.compile("^\\s*#\\s*(\\w+)\\s*(\\w*)");

    private final List<String> lines;
    private final boolean[] conditional;

    private SourceLines(List<String> lines) {
        this.lines = lines;
        this.conditional = new boolean[lines.size() + 2];
        Deque<Boolean> open = new ArrayDeque<>();
        for (int i = 0; i < lines.size(); i++) {
            conditional[i + 1] = open.contains(Boolean.FALSE);
            directive(i, open);
        }
    }

    static SourceLines read(Path file) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString();
        return of(text);
    }

    static SourceLines of(String text) {
        return new SourceLines(text.lines().toList());
    }

    /** Line {@code n} (1-based), or empty outside the file. */
    String line(int n) {
        return n >= 1 && n <= lines.size() ? lines.get(n - 1) : "";
    }

    /** Lines {@code from} to {@code to}, inclusive, joined with newlines. */
    String text(int from, int to) {
        int a = Math.max(1, from);
        int b = Math.min(lines.size(), to);
        return a > b ? "" : String.join("\n", lines.subList(a - 1, b));
    }

    /** True when the line lies inside a preprocessor conditional other than an include guard. */
    boolean conditional(int line) {
        return line >= 1 && line < conditional.length && conditional[line];
    }

    /** Pushes TRUE for an include guard, FALSE for another conditional; pops at #endif. */
    private void directive(int i, Deque<Boolean> open) {
        Matcher m = DIRECTIVE.matcher(lines.get(i));
        if (!m.find()) return;
        String word = m.group(1);
        if (word.equals("endif") && !open.isEmpty()) open.pop();
        else if (word.equals("ifndef")) open.push(guard(i, m.group(2)));
        else if (word.equals("if") || word.equals("ifdef")) open.push(Boolean.FALSE);
    }

    private boolean guard(int i, String macro) {
        for (int j = i + 1; j < lines.size(); j++) {
            Matcher m = DIRECTIVE.matcher(lines.get(j));
            if (m.find()) return m.group(1).equals("define") && m.group(2).equals(macro) && !macro.isEmpty();
        }
        return false;
    }
}
