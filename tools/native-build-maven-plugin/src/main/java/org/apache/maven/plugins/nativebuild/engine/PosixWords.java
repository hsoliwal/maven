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
package org.apache.maven.plugins.nativebuild.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * POSIX shell word splitting for reading commands that make printed, without running a shell: quotes and backslash
 * escapes are removed, nothing is expanded ({@code $var} and back-quoted text stay as written). Commands split at
 * unquoted {@code ;}, {@code &&}, {@code ||}, {@code |}, {@code &} and line breaks.
 */
public final class PosixWords {
    private PosixWords() {}

    /** The commands of one (already joined) line, each still in shell syntax. */
    public static List<String> commands(String line) {
        Splitter splitter = new Splitter();
        for (int i = 0; i < line.length(); ) {
            i += splitter.step(line, i);
        }
        splitter.flush();
        return splitter.commands;
    }

    /** The words of one command, with quotes and escapes removed. */
    public static List<String> words(String command) {
        Words words = new Words();
        for (int i = 0; i < command.length(); ) {
            i += words.step(command, i);
        }
        words.end();
        return words.result;
    }

    private static int separator(String s, int i) {
        char c = s.charAt(i);
        char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
        if (c == ';' || c == '\n') {
            return 1;
        }
        if ((c == '&' && next == '&') || (c == '|' && next == '|')) {
            return 2;
        }
        boolean redirect = c == '&' && ((i > 0 && s.charAt(i - 1) == '>') || next == '>');
        return c == '|' || (c == '&' && !redirect) ? 1 : 0;
    }

    /** Command splitting with quote state; the text of each command is copied unchanged. */
    private static final class Splitter {
        private final List<String> commands = new ArrayList<>();
        private final StringBuilder out = new StringBuilder();
        private char quote;
        private int depth;

        int step(String s, int i) {
            int separator = quote != 0 || depth > 0 ? 0 : separator(s, i);
            if (separator > 0) {
                flush();
                return separator;
            }
            return consume(s, i);
        }

        void flush() {
            String command = out.toString().strip();
            if (!command.isEmpty()) {
                commands.add(command);
            }
            out.setLength(0);
        }

        private int consume(String s, int i) {
            char c = s.charAt(i);
            if (c == '\\' && quote != '\'' && i + 1 < s.length()) {
                out.append(c).append(s.charAt(i + 1));
                return 2;
            }
            out.append(c);
            if (quote == 0) {
                quote = c == '\'' || c == '"' || c == '`' ? c : 0;
                depth += c == '(' && i > 0 && s.charAt(i - 1) == '$' ? 1 : c == ')' && depth > 0 ? -1 : 0;
            } else if (c == quote) {
                quote = 0;
            }
            return 1;
        }
    }

    /** Word state; back-quoted text is kept with its back quotes. */
    private static final class Words {
        private final List<String> result = new ArrayList<>();
        private final StringBuilder word = new StringBuilder();
        private boolean inWord;
        private char quote;

        int step(String s, int i) {
            char c = s.charAt(i);
            return switch (quote) {
                case '\'' -> inSingle(c);
                case '"' -> inDouble(s, i, c);
                case '`' -> inBackQuote(c);
                default -> unquoted(s, i, c);
            };
        }

        private int unquoted(String s, int i, char c) {
            if (Character.isWhitespace(c)) {
                end();
                return 1;
            }
            inWord = true;
            if (c == '\\') {
                appendEscaped(s, i);
                return 2;
            }
            quote = c == '\'' || c == '"' || c == '`' ? c : 0;
            word.append(c == '\'' || c == '"' ? "" : String.valueOf(c));
            return 1;
        }

        private int inSingle(char c) {
            if (c == '\'') {
                quote = 0;
            } else {
                word.append(c);
            }
            return 1;
        }

        private int inDouble(String s, int i, char c) {
            if (c == '"') {
                quote = 0;
                return 1;
            }
            if (c == '\\' && i + 1 < s.length() && "$`\"\\\n".indexOf(s.charAt(i + 1)) >= 0) {
                appendEscaped(s, i);
                return 2;
            }
            word.append(c);
            return 1;
        }

        private int inBackQuote(char c) {
            word.append(c);
            quote = c == '`' ? 0 : quote;
            return 1;
        }

        private void appendEscaped(String s, int i) {
            if (i + 1 < s.length() && s.charAt(i + 1) != '\n') {
                word.append(s.charAt(i + 1));
            }
        }

        void end() {
            if (inWord) {
                result.add(word.toString());
                word.setLength(0);
                inWord = false;
            }
        }
    }
}
