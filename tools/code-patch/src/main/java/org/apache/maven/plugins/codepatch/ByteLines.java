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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lines as bytes. A line ends after its {@code \n} and keeps it, so a {@code \r\n} line keeps both bytes and the last
 * line of a file without a final newline has no terminator: joining the lines gives back the exact input.
 */
final class ByteLines {
    private static final int SHOWN = 60;

    private ByteLines() { }

    static List<byte[]> split(byte[] bytes) {
        List<byte[]> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                lines.add(Arrays.copyOfRange(bytes, start, i + 1));
                start = i + 1;
            }
        }
        if (start < bytes.length) lines.add(Arrays.copyOfRange(bytes, start, bytes.length));
        return lines;
    }

    static boolean terminated(byte[] line) {
        return line.length > 0 && line[line.length - 1] == '\n';
    }

    /** The line as header text: UTF-8, without its terminator. */
    static String text(byte[] line) {
        int end = line.length;
        if (end > 0 && line[end - 1] == '\n') end--;
        if (end > 0 && line[end - 1] == '\r') end--;
        return new String(line, 0, end, StandardCharsets.UTF_8);
    }

    /** The line for a message: quoted, {@code \r \n \t} and other control characters escaped, long lines cut. */
    static String show(byte[] line) {
        if (line == null) return "end of file";
        String text = new String(line, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder("\"");
        text.codePoints().limit(SHOWN).forEach(c -> out.append(escape(c)));
        if (text.length() > SHOWN) out.append("...");
        return out.append('"').toString();
    }

    private static String escape(int c) {
        return switch (c) {
            case '\r' -> "\\r";
            case '\n' -> "\\n";
            case '\t' -> "\\t";
            case '"' -> "\\\"";
            default -> c < ' ' || c == 0x7f ? String.format("\\x%02x", c) : new String(Character.toChars(c));
        };
    }
}
