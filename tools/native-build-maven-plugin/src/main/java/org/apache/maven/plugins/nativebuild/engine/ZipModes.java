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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Unix file modes in a zip's central directory, which {@code java.util.zip} cannot write: each entry's "version made
 * by" host becomes Unix (3) and its external attributes carry {@code S_IFREG | mode}, as Info-ZIP and
 * commons-compress write them, so {@code unzip} restores the executable bit. The archive is patched in place after
 * {@link java.util.zip.ZipOutputStream} wrote it; nothing else changes. Zip64 archives are left as they are.
 */
public final class ZipModes {
    private static final int CEN = 0x02014b50;
    private static final int END = 0x06054b50;
    private static final int REGULAR = 0100000;

    private ZipModes() {}

    /** Sets each entry's mode to {@code modes.applyAsInt(name)}; false for a Zip64 or unreadable archive. */
    public static boolean apply(Path zip, ToIntFunction<String> modes) throws IOException {
        byte[] bytes = Files.readAllBytes(zip);
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int end = end(b);
        if (end < 0 || (b.getShort(end + 10) & 0xffff) == 0xffff || b.getInt(end + 16) == -1) {
            return false;
        }
        entries(b, end).forEach(at -> {
            b.put(at + 5, (byte) 3);
            b.putInt(at + 38, (REGULAR | (modes.applyAsInt(name(b, at)) & 07777)) << 16);
        });
        Files.write(zip, bytes);
        return true;
    }

    /** Each entry's Unix mode (permission bits), for entries made on Unix. */
    public static Map<String, Integer> read(Path zip) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(zip)).order(ByteOrder.LITTLE_ENDIAN);
        int end = end(b);
        Map<String, Integer> modes = new TreeMap<>();
        if (end >= 0) {
            entries(b, end).filter(at -> (b.get(at + 5) & 0xff) == 3)
                    .forEach(at -> modes.put(name(b, at), (b.getInt(at + 38) >>> 16) & 07777));
        }
        return modes;
    }

    private static Stream<Integer> entries(ByteBuffer b, int end) {
        int count = b.getShort(end + 10) & 0xffff;
        return Stream.iterate(b.getInt(end + 16), at -> at + 46 + (b.getShort(at + 28) & 0xffff)
                + (b.getShort(at + 30) & 0xffff) + (b.getShort(at + 32) & 0xffff)).limit(count)
                .takeWhile(at -> at + 46 <= b.limit() && b.getInt(at) == CEN);
    }

    private static String name(ByteBuffer b, int at) {
        byte[] name = new byte[b.getShort(at + 28) & 0xffff];
        b.get(at + 46, name);
        return new String(name, StandardCharsets.UTF_8);
    }

    private static int end(ByteBuffer b) {
        int last = b.limit() - 22;
        return IntStream.iterate(last, at -> at >= Math.max(0, last - 65_535), at -> at - 1)
                .filter(at -> b.getInt(at) == END).findFirst().orElse(-1);
    }
}
