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

import java.util.Arrays;
import java.util.List;

/**
 * The lines of a patch as bytes with a read position. A leading UTF-8 byte order mark is dropped, so it cannot hide
 * the first header line. Refusals name the patch and the line at the read position, or a line marked earlier.
 */
final class PatchLines {
    private static final byte[] BOM = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
    private final String source;
    private final List<byte[]> lines;
    private int next;

    PatchLines(byte[] patch, String source) {
        this.source = source;
        boolean bom = patch.length >= BOM.length && Arrays.equals(patch, 0, BOM.length, BOM, 0, BOM.length);
        this.lines = ByteLines.split(bom ? Arrays.copyOfRange(patch, BOM.length, patch.length) : patch);
    }

    boolean more() {
        return next < lines.size();
    }

    /** The bytes of the line at the read position. */
    byte[] peek() {
        return lines.get(next);
    }

    /** The header text of the line {@code ahead} lines after the read position, or null past the end. */
    String text(int ahead) {
        return next + ahead < lines.size() ? ByteLines.text(lines.get(next + ahead)) : null;
    }

    /** The line at the read position; the position moves to the next line. */
    byte[] take() {
        return lines.get(next++);
    }

    void skip(int count) {
        next += count;
    }

    /** The 1-based number of the line at the read position (the last line past the end). */
    int number() {
        return Math.min(next + 1, lines.size());
    }

    PatchException refuse(String message) {
        return refuse(number(), message);
    }

    /** A refusal that names line {@code number} (from {@link #number()}) instead of the read position. */
    PatchException refuse(int number, String message) {
        return new PatchException(source + " line " + number + ": " + message);
    }
}
