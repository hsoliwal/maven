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
package org.apache.maven.plugins.clustering;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** The hash functions of the planes: SHA-256 for the exact, logic and structure planes, SimHash-64 for near ones. */
final class PlaneHash {
    private static final int SHINGLE = 3;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private PlaneHash() { }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** SimHash-64 over the shingles of {@value #SHINGLE} consecutive tokens (16 hex digits). */
    static String simhash(List<String> tokens) {
        int[] weights = new int[Long.SIZE];
        int last = Math.max(1, tokens.size() - SHINGLE + 1);
        for (int i = 0; i < last; i++) {
            long hash = fnv(String.join(" ", tokens.subList(i, Math.min(tokens.size(), i + SHINGLE))));
            for (int bit = 0; bit < Long.SIZE; bit++) weights[bit] += ((hash >>> bit) & 1L) == 1L ? 1 : -1;
        }
        long value = 0L;
        for (int bit = 0; bit < Long.SIZE; bit++) if (weights[bit] > 0) value |= 1L << bit;
        return String.format("%016x", value);
    }

    static int hamming(String left, String right) {
        return Long.bitCount(Long.parseUnsignedLong(left, 16) ^ Long.parseUnsignedLong(right, 16));
    }

    private static long fnv(String text) {
        long hash = FNV_OFFSET;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xffL;
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
