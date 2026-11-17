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
package org.apache.maven.recipes.javac;

/**
 * A source text with its unicode escapes translated as javac's reader does (JLS 3.3: a backslash preceded by an even
 * number of backslashes, one or more {@code u}, four hex digits), and the source index of every translated char, so
 * what is found in the translated text can be cut from the text as written.
 */
final class UnicodeText {
    private static final int HEX_DIGITS = 4;
    private static final int HEX = 16;

    private final String source;
    private final String chars;
    private final int[] offsets;

    private UnicodeText(String source, String chars, int[] offsets) {
        this.source = source;
        this.chars = chars;
        this.offsets = offsets;
    }

    static UnicodeText of(String source) {
        StringBuilder chars = new StringBuilder(source.length());
        int[] offsets = new int[source.length() + 1];
        int i = 0;
        while (i < source.length()) {
            offsets[chars.length()] = i;
            int end = escapeEnd(source, i);
            chars.append(
                    end < 0 ? source.charAt(i) : (char) Integer.parseInt(source.substring(end - HEX_DIGITS, end), HEX));
            i = end < 0 ? i + 1 : end;
        }
        offsets[chars.length()] = source.length();
        return new UnicodeText(source, chars.toString(), offsets);
    }

    /** The translated text. */
    String chars() {
        return chars;
    }

    /** The text as written behind the translated chars {@code [start, end)}. */
    String source(int start, int end) {
        return source.substring(offsets[start], offsets[end]);
    }

    /** The index after the unicode escape that starts at {@code i}, or -1 when none starts there. */
    private static int escapeEnd(String text, int i) {
        if (text.charAt(i) != '\\' || backslashesBefore(text, i) % 2 != 0) {
            return -1;
        }
        int digits = i + 1;
        while (digits < text.length() && text.charAt(digits) == 'u') {
            digits++;
        }
        if (digits == i + 1 || digits + HEX_DIGITS > text.length()) {
            return -1;
        }
        boolean hex = text.substring(digits, digits + HEX_DIGITS).chars().allMatch(c -> Character.digit(c, HEX) >= 0);
        return hex ? digits + HEX_DIGITS : -1;
    }

    private static int backslashesBefore(String text, int i) {
        int count = 0;
        while (i - count > 0 && text.charAt(i - count - 1) == '\\') {
            count++;
        }
        return count;
    }
}
