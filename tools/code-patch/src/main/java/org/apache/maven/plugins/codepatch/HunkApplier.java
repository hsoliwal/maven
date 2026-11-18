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

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import org.apache.maven.plugins.codepatch.FilePatch.Hunk;
import org.apache.maven.plugins.codepatch.FilePatch.Line;

/**
 * Applies hunks to a file's bytes exactly: every hunk at the line its header names, every context and removed line
 * equal byte for byte, line terminators included. No fuzz, no offset search, no whitespace or line-end tolerance.
 * Unchanged lines keep their bytes; added lines get the bytes the patch has. A line is never written after a line
 * without a terminator, so two lines are never joined into one.
 */
final class HunkApplier {
    private HunkApplier() { }

    /** One hunk of a file, for refusals that name the file, the hunk and its header. */
    private record At(String path, int index, int of, Hunk hunk) {
        PatchException refuse(String why) {
            return new PatchException(path + ": hunk " + (index + 1) + " of " + of + " (" + hunk.header() + ") " + why);
        }
    }

    /** The output: refuses a line written after a line that has no terminator. */
    private static final class Output {
        private final ByteArrayOutputStream bytes;
        private int written;
        private boolean open;

        Output(int size) {
            bytes = new ByteArrayOutputStream(size);
        }

        void write(At at, byte[] line) throws PatchException {
            if (open && line.length > 0) {
                throw at.refuse("writes a line after line " + written + ", which has no line terminator");
            }
            bytes.writeBytes(line);
            written++;
            open = !ByteLines.terminated(line);
        }
    }

    /** The after-image of {@code before}; {@code path} names the file in a refusal. */
    static byte[] apply(String path, byte[] before, List<Hunk> hunks) throws PatchException {
        List<byte[]> lines = ByteLines.split(before);
        Output out = new Output(before.length + 64);
        int copied = 0;
        At at = null;
        for (int h = 0; h < hunks.size(); h++) {
            at = new At(path, h, hunks.size(), hunks.get(h));
            int start = start(at, copied, lines.size());
            for (byte[] line : lines.subList(copied, start)) out.write(at, line);
            copied = hunk(at, lines, start, out);
        }
        for (byte[] line : lines.subList(copied, lines.size())) out.write(at, line);
        return out.bytes.toByteArray();
    }

    /** The index of the first file line the hunk reads; a hunk with no old lines starts after line {@code oldStart}. */
    private static int start(At at, int copied, int size) throws PatchException {
        Hunk hunk = at.hunk();
        int start = hunk.oldCount() == 0 ? hunk.oldStart() : hunk.oldStart() - 1;
        if (start < copied || start > size) {
            throw at.refuse("starts at line " + hunk.oldStart() + ", but "
                    + (start < copied ? "the previous hunk ends at line " + copied : "the file has " + size + " lines"));
        }
        return start;
    }

    /** Writes one hunk's result and returns the index of the first file line after it. */
    private static int hunk(At at, List<byte[]> lines, int start, Output out) throws PatchException {
        int next = start;
        for (Line line : at.hunk().lines()) {
            if (line.kind() == '+') {
                out.write(at, line.bytes());
            } else {
                byte[] actual = matched(at, lines, next++, line);
                if (line.kind() == ' ') out.write(at, actual);
            }
        }
        return next;
    }

    /** The file line at {@code index}, refused unless it equals the context or removed line byte for byte. */
    private static byte[] matched(At at, List<byte[]> lines, int index, Line line) throws PatchException {
        byte[] actual = index < lines.size() ? lines.get(index) : null;
        if (actual == null || !Arrays.equals(actual, line.bytes())) {
            throw at.refuse("does not match at line " + (index + 1) + ": expected " + ByteLines.show(line.bytes())
                    + " but found " + ByteLines.show(actual));
        }
        return actual;
    }
}
