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
package org.apache.maven.impl;

import java.util.stream.IntStream;

/**
 * Wildcard match of one coordinate token: {@code *} matches any sequence of characters and {@code ?} any
 * one character. There is no escape and no separator rule.
 * <p>
 * Adapted from the {@code match} method of {@code PatternIncludesArtifactFilter} in Apache Maven Common
 * Artifact Filters (Apache-2.0, commit a11a231759800e38a5c46822f1cc04b867bc5670): the text before the first
 * {@code *} must match the start of the value, the text after the last {@code *} its end, and the parts
 * between stars are found in order, each at its leftmost place. A {@code null} value is matched as the
 * empty string, as there.
 */
final class CoordinateGlob {
    private final String token;

    private final String[] parts;

    CoordinateGlob(String token) {
        this.token = token;
        this.parts = token.split("\\*", -1);
    }

    static boolean hasWildcard(String token) {
        return token.indexOf('*') >= 0 || token.indexOf('?') >= 0;
    }

    boolean matches(String value) {
        String text = value != null ? value : "";
        if (parts.length == 1) {
            return text.length() == token.length() && regionMatches(text, 0, token);
        }
        String head = parts[0];
        String tail = parts[parts.length - 1];
        int end = text.length() - tail.length();
        return end >= head.length()
                && regionMatches(text, 0, head)
                && regionMatches(text, end, tail)
                && middleMatches(text, 1, head.length(), end);
    }

    /**
     * Finds the parts between the first and the last star, from {@code index} on, in order and without
     * overlap, between {@code from} and {@code end}.
     */
    private boolean middleMatches(String text, int index, int from, int end) {
        if (index >= parts.length - 1) {
            return true;
        }
        String part = parts[index];
        int found = IntStream.rangeClosed(from, end - part.length())
                .filter(start -> regionMatches(text, start, part))
                .findFirst()
                .orElse(-1);
        return found >= 0 && middleMatches(text, index + 1, found + part.length(), end);
    }

    private static boolean regionMatches(String text, int offset, String part) {
        return IntStream.range(0, part.length())
                .allMatch(i -> part.charAt(i) == '?' || part.charAt(i) == text.charAt(offset + i));
    }

    @Override
    public String toString() {
        return token;
    }
}
