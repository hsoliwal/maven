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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.plugins.codepatch.FilePatch.Hunk;
import org.apache.maven.plugins.codepatch.FilePatch.Line;

/**
 * Reads one hunk: the {@code @@} header (numbers that do not fit an int are refused), then exactly as many old-side
 * and new-side lines as the header counts, with the {@code \ No newline at end of file} markers. A marker removes the
 * terminator of the line before it and closes that line's side: a later line of the same side is refused, because it
 * would be joined to the unterminated one.
 */
final class HunkReader {
    private static final Pattern HEADER = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");
    private static final int OLD = 0;
    private static final int NEW = 1;
    private final PatchLines lines;
    private final String header;
    private final int[] left = new int[2];
    private final boolean[] closed = new boolean[2];
    private final List<Line> body = new ArrayList<>();

    private HunkReader(PatchLines lines, String header, int oldCount, int newCount) {
        this.lines = lines;
        this.header = header;
        left[OLD] = oldCount;
        left[NEW] = newCount;
    }

    /** Reads the hunk whose header is the line at the read position; the position ends after its last line. */
    static Hunk read(PatchLines lines) throws PatchException {
        String header = lines.text(0);
        int[] n = numbers(lines, header);
        lines.take();
        HunkReader reader = new HunkReader(lines, header, n[1], n[3]);
        reader.body();
        return new Hunk(n[0], n[1], n[2], n[3], reader.body);
    }

    /** oldStart, oldCount, newStart, newCount; an omitted count is 1. */
    private static int[] numbers(PatchLines lines, String header) throws PatchException {
        Matcher m = HEADER.matcher(header);
        if (!m.matches()) throw lines.refuse("unreadable hunk header: " + header);
        try {
            return new int[] {Integer.parseInt(m.group(1)), count(m.group(2)), Integer.parseInt(m.group(3)), count(m.group(4))};
        } catch (NumberFormatException e) {
            throw lines.refuse("hunk header numbers out of range: " + header);
        }
    }

    private static int count(String group) {
        return group == null ? 1 : Integer.parseInt(group);
    }

    private void body() throws PatchException {
        while (left[OLD] > 0 || left[NEW] > 0) {
            if (!lines.more()) throw lines.refuse("hunk " + header + " ends before its last line");
            byte[] raw = lines.take();
            if (raw[0] == '\\') {
                marker();
            } else {
                add(line(raw));
            }
        }
        if (lines.more() && lines.peek()[0] == '\\') {
            lines.take();
            marker();
        }
    }

    /** A hunk line; a bare line terminator is an empty context line whose leading space was stripped. */
    private Line line(byte[] raw) throws PatchException {
        if (!ByteLines.terminated(raw)) throw lines.refuse("hunk " + header + ": the patch ends inside a line");
        if (raw.length == 1) return new Line(' ', raw);
        char kind = (char) raw[0];
        if (kind != ' ' && kind != '-' && kind != '+') {
            throw lines.refuse("hunk " + header + ": unexpected line " + ByteLines.show(raw));
        }
        return new Line(kind, Arrays.copyOfRange(raw, 1, raw.length));
    }

    private void add(Line line) throws PatchException {
        boolean old = line.kind() != '+';
        boolean now = line.kind() != '-';
        if (old && closed[OLD] || now && closed[NEW]) {
            throw lines.refuse("hunk " + header + ": \\ No newline at end of file is not on the last line of its side");
        }
        if (old) left[OLD]--;
        if (now) left[NEW]--;
        if (left[OLD] < 0 || left[NEW] < 0) throw lines.refuse("hunk " + header + " has more lines than its header counts");
        body.add(line);
    }

    private void marker() throws PatchException {
        if (body.isEmpty() || !ByteLines.terminated(body.get(body.size() - 1).bytes())) {
            throw lines.refuse("hunk " + header + ": misplaced \\ No newline at end of file");
        }
        Line last = body.remove(body.size() - 1);
        body.add(new Line(last.kind(), Arrays.copyOf(last.bytes(), last.bytes().length - 1)));
        if (last.kind() != '+') closed[OLD] = true;
        if (last.kind() != '-') closed[NEW] = true;
    }
}
