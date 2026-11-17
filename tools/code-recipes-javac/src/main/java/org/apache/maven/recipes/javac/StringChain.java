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
package org.apache.maven.recipes.javac;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The source text of one string literal as javac's parser saw it: either a single literal or a chain of string
 * literals and text blocks joined by {@code +}, which the parser folds into one literal ({@code JavacParser.foldStrings},
 * JDK 21). Reads the literals and the whitespace and comments around each {@code +} back from the text, after
 * translating unicode escapes as javac's reader does, and decodes each literal's value.
 *
 * @param literals the source text of each literal, unicode escapes as written
 * @param values the value of each literal (escapes, text block indentation and line ends processed)
 * @param before the whitespace and comments before each {@code +}, one fewer than literals
 * @param after the whitespace and comments after each {@code +}
 */
record StringChain(List<String> literals, List<String> values, List<String> before, List<String> after) {
    private static final String TEXT_BLOCK = "\"\"\"";

    /** Reads the source text of a string literal; empty when it is neither one literal nor a chain of them. */
    static Optional<StringChain> read(String source) {
        try {
            return lex(UnicodeText.of(source));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<StringChain> lex(UnicodeText text) {
        StringChain chain = new StringChain(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        int at = 0;
        while (at >= 0) {
            int end = literalEnd(text.chars(), at);
            if (end < 0) {
                return Optional.empty();
            }
            chain.add(text, at, end);
            at = chain.gap(text, end);
        }
        return at == -1 ? Optional.of(chain) : Optional.empty();
    }

    /** Whether the text is a chain of two or more literals, as javac folds them. */
    boolean folded() {
        return literals.size() > 1;
    }

    /** The concatenated values, javac's value of the folded literal. */
    String value() {
        return String.join("", values);
    }

    private void add(UnicodeText text, int start, int end) {
        literals.add(text.source(start, end));
        values.add(decode(text.chars().substring(start, end)));
    }

    /** Reads the gap after a literal: -1 at the end of the text, -2 when it is no {@code +}, else the next literal. */
    private int gap(UnicodeText text, int end) {
        String chars = text.chars();
        int plus = skipSpace(chars, end);
        if (plus == chars.length() && plus == end) {
            return -1;
        }
        if (plus < 0 || plus == chars.length() || chars.charAt(plus) != '+') {
            return -2;
        }
        int next = skipSpace(chars, plus + 1);
        before.add(text.source(end, plus));
        after.add(text.source(plus + 1, Math.max(next, plus + 1)));
        return next < 0 || next == chars.length() ? -2 : next;
    }

    private static int literalEnd(String chars, int at) {
        if (chars.startsWith(TEXT_BLOCK, at)) {
            return closing(chars, at + TEXT_BLOCK.length(), TEXT_BLOCK);
        }
        return at < chars.length() && chars.charAt(at) == '"' ? closing(chars, at + 1, "\"") : -1;
    }

    /** The index after the closing delimiter, skipping escapes; -1 when there is none or a string spans lines. */
    private static int closing(String chars, int from, String delimiter) {
        int i = from;
        while (i >= 0 && i < chars.length()) {
            if (chars.startsWith(delimiter, i)) {
                return i + delimiter.length();
            }
            i = nextInString(chars, i, delimiter);
        }
        return -1;
    }

    /** The index after one character (or escape) of a string body; -1 at a line end inside a one-line string. */
    private static int nextInString(String chars, int i, String delimiter) {
        char c = chars.charAt(i);
        if (c == '\\') {
            return i + 2;
        }
        return delimiter.length() == 1 && (c == '\n' || c == '\r') ? -1 : i + 1;
    }

    /** The index after whitespace and comments, or -1 for an unterminated block comment. */
    private static int skipSpace(String chars, int from) {
        int i = from;
        while (i >= 0 && i < chars.length()) {
            int next = afterSpace(chars, i);
            if (next == i) {
                return i;
            }
            i = next;
        }
        return i;
    }

    /** The index after one whitespace character or comment at i; i when there is neither; -1 when unterminated. */
    private static int afterSpace(String chars, int i) {
        if (Character.isWhitespace(chars.charAt(i))) {
            return i + 1;
        } else if (chars.startsWith("//", i)) {
            int lineEnd = indexOfLineEnd(chars, i);
            return lineEnd < 0 ? chars.length() : lineEnd;
        } else if (chars.startsWith("/*", i)) {
            int close = chars.indexOf("*/", i + 2);
            return close < 0 ? -1 : close + 2;
        }
        return i;
    }

    private static int indexOfLineEnd(String chars, int from) {
        int lf = chars.indexOf('\n', from);
        int cr = chars.indexOf('\r', from);
        return lf < 0 ? cr : cr < 0 ? lf : Math.min(lf, cr);
    }

    /** The value of one literal (JLS 3.10.5 and 3.10.6), its unicode escapes already translated. */
    static String decode(String literal) {
        if (!literal.startsWith(TEXT_BLOCK)) {
            return literal.substring(1, literal.length() - 1).translateEscapes();
        }
        String body = literal.substring(TEXT_BLOCK.length(), literal.length() - TEXT_BLOCK.length());
        int lineEnd = indexOfLineEnd(body, 0);
        if (lineEnd < 0 || !body.substring(0, lineEnd).isBlank()) {
            throw new IllegalArgumentException("no line end after the opening delimiter of a text block");
        }
        String content = body.substring(lineEnd + 1);
        if (body.startsWith("\r\n", lineEnd)) {
            content = body.substring(lineEnd + 2);
        }
        return content.replace("\r\n", "\n").replace('\r', '\n').stripIndent().translateEscapes();
    }
}
