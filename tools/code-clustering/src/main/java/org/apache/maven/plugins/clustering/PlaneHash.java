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
    private static final byte[] SPACE = {' '};

    private PlaneHash() { }

    static String sha256(String text) {
        return HexFormat.of().formatHex(digest().digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * SHA-256 of the tokens joined with single spaces, from their UTF-8 bytes: the value of
     * {@code sha256(String.join(" ", tokens))} without building the joined text (spaces are one byte in UTF-8).
     */
    static String sha256(byte[][] tokens) {
        MessageDigest digest = digest();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) digest.update(SPACE);
            digest.update(tokens[i]);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** The UTF-8 bytes of each token. */
    static byte[][] utf8(List<String> tokens) {
        byte[][] bytes = new byte[tokens.size()][];
        for (int i = 0; i < bytes.length; i++) bytes[i] = tokens.get(i).getBytes(StandardCharsets.UTF_8);
        return bytes;
    }

    /** SimHash-64 over the shingles of {@value #SHINGLE} consecutive tokens (16 hex digits). */
    static String simhash(List<String> tokens) {
        return simhash(utf8(tokens));
    }

    /**
     * SimHash-64 from the UTF-8 bytes of the tokens: each shingle's FNV-1a hash runs over its tokens' bytes with a space
     * between them, the hash of the shingle's joined text. A bit is set when more shingle hashes have it set than not
     * (weight +1 per set bit, -1 per clear bit, set when positive); the set bits of all 64 positions are counted at once
     * in bit-sliced counters ({@code planes[j]} holds bit {@code j} of every position's count).
     */
    static String simhash(byte[][] tokens) {
        int last = Math.max(1, tokens.length - SHINGLE + 1);
        long[] planes = new long[Integer.SIZE];
        for (int i = 0; i < last; i++) count(planes, shingle(tokens, i, Math.min(tokens.length, i + SHINGLE)));
        long value = 0L;
        for (int bit = 0; bit < Long.SIZE; bit++) if (2L * ones(planes, bit) > last) value |= 1L << bit;
        return HexFormat.of().toHexDigits(value);
    }

    /** Adds the set bits of {@code word} to the bit-sliced counters: a ripple-carry add of 64 one-bit numbers. */
    private static void count(long[] planes, long word) {
        long carry = word;
        for (int j = 0; carry != 0; j++) {
            long next = planes[j] & carry;
            planes[j] ^= carry;
            carry = next;
        }
    }

    /** How many counted words had {@code bit} set. */
    private static long ones(long[] planes, int bit) {
        long count = 0;
        for (int j = 0; j < planes.length; j++) count |= ((planes[j] >>> bit) & 1L) << j;
        return count;
    }

    static int hamming(String left, String right) {
        return Long.bitCount(Long.parseUnsignedLong(left, 16) ^ Long.parseUnsignedLong(right, 16));
    }

    /** FNV-1a of the tokens {@code from} to {@code to} (exclusive) joined with single spaces. */
    private static long shingle(byte[][] tokens, int from, int to) {
        long hash = FNV_OFFSET;
        for (int t = from; t < to; t++) {
            if (t > from) hash = fnv(hash, SPACE[0]);
            for (byte b : tokens[t]) hash = fnv(hash, b);
        }
        return hash;
    }

    private static long fnv(long hash, byte b) {
        return (hash ^ (b & 0xffL)) * FNV_PRIME;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
