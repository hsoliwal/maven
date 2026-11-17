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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * A source file as bytes, because clang's offsets count bytes. Lines are 1-based; text between two offsets is
 * decoded as UTF-8.
 */
final class SourceText {
    private final byte[] bytes;
    private final int[] lineStarts;

    private SourceText(byte[] bytes) {
        this.bytes = bytes;
        int[] starts = new int[16];
        int count = 1;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != '\n') continue;
            if (count == starts.length) starts = Arrays.copyOf(starts, count * 2);
            starts[count++] = i + 1;
        }
        this.lineStarts = Arrays.copyOf(starts, count);
    }

    static SourceText of(byte[] bytes) {
        return new SourceText(bytes);
    }

    static SourceText read(Path file) throws IOException {
        return new SourceText(Files.readAllBytes(file));
    }

    int length() {
        return bytes.length;
    }

    /** The 1-based line of a byte offset. */
    int line(int offset) {
        int i = Arrays.binarySearch(lineStarts, offset);
        return i >= 0 ? i + 1 : -i - 1;
    }

    String text(int from, int to) {
        return new String(bytes, from, to - from, StandardCharsets.UTF_8);
    }

    /**
     * The end of a macro call whose name ends at {@code nameEnd}: just after the closing parenthesis of its argument
     * list, or {@code nameEnd} when no argument list follows. Strings and character literals are skipped.
     */
    int macroCallEnd(int nameEnd) {
        int i = nameEnd;
        while (i < bytes.length && (bytes[i] == ' ' || bytes[i] == '\t' || bytes[i] == '\r' || bytes[i] == '\n')) i++;
        if (i >= bytes.length || bytes[i] != '(') return nameEnd;
        int depth = 0;
        for (; i < bytes.length; i++) {
            if (bytes[i] == '"' || bytes[i] == '\'') i = closingQuote(i);
            else if (bytes[i] == '(') depth++;
            else if (bytes[i] == ')' && --depth == 0) return i + 1;
        }
        return nameEnd;
    }

    private int closingQuote(int open) {
        byte quote = bytes[open];
        int i = open + 1;
        while (i < bytes.length && bytes[i] != quote && bytes[i] != '\n') i += bytes[i] == '\\' ? 2 : 1;
        return Math.min(i, bytes.length - 1);
    }
}
