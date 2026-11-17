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

import java.util.ArrayList;
import java.util.List;

/**
 * Splits the {@code command} string of a compilation database entry into words, without a shell, the way clang's
 * own database reader does: Windows rules on Windows (a backslash is literal unless a quote follows it), POSIX
 * shell quoting elsewhere (single quotes, double quotes with backslash escapes, backslash outside quotes).
 */
final class CommandWords {
    private final String s;
    private final List<String> words = new ArrayList<>();
    private final StringBuilder word = new StringBuilder();
    private boolean inWord;
    private boolean quoted;
    private int i;

    private CommandWords(String s) {
        this.s = s;
    }

    static List<String> split(String command, boolean windows) {
        CommandWords w = new CommandWords(command);
        while (w.i < w.s.length()) {
            if (windows) w.windowsStep();
            else w.posixStep();
        }
        w.end();
        return List.copyOf(w.words);
    }

    private void posixStep() {
        char c = s.charAt(i++);
        if (Character.isWhitespace(c)) end();
        else if (c == '\\') add(i < s.length() ? s.charAt(i++) : '\\');
        else if (c == '\'') single();
        else if (c == '"') posixDouble();
        else add(c);
    }

    private void single() {
        inWord = true;
        int close = s.indexOf('\'', i);
        int stop = close < 0 ? s.length() : close;
        word.append(s, i, stop);
        i = Math.min(stop + 1, s.length());
    }

    private void posixDouble() {
        inWord = true;
        while (i < s.length() && s.charAt(i) != '"') {
            char c = s.charAt(i++);
            if (c == '\\' && i < s.length() && "\"\\$`\n".indexOf(s.charAt(i)) >= 0) c = s.charAt(i++);
            word.append(c);
        }
        i++;
    }

    private void windowsStep() {
        char c = s.charAt(i++);
        if (c == '\\') backslashes();
        else if (c == '"') windowsQuote();
        else if (!quoted && Character.isWhitespace(c)) end();
        else add(c);
    }

    /** 2n backslashes and a quote: n backslashes, the quote toggles; 2n+1: n backslashes and a literal quote. */
    private void backslashes() {
        int n = 1;
        while (i < s.length() && s.charAt(i) == '\\') {
            n++;
            i++;
        }
        boolean beforeQuote = i < s.length() && s.charAt(i) == '"';
        word.append("\\".repeat(beforeQuote ? n / 2 : n));
        inWord = true;
        if (beforeQuote && n % 2 == 1) add(s.charAt(i++));
    }

    /** A quote toggles quoting; two quotes inside quotes are one literal quote. */
    private void windowsQuote() {
        inWord = true;
        if (quoted && i < s.length() && s.charAt(i) == '"') add(s.charAt(i++));
        else quoted = !quoted;
    }

    private void add(char c) {
        word.append(c);
        inWord = true;
    }

    private void end() {
        if (inWord) words.add(word.toString());
        word.setLength(0);
        inWord = false;
    }
}
