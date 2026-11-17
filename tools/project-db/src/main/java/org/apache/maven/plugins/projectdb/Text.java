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
package org.apache.maven.plugins.projectdb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small text atoms shared by the parsers and the codec. */
final class Text {
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w:.-]+)=\"([^\"]*)\"");
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-9;]*[A-Za-z]");

    private Text() {}

    /** The lines of a UTF-8 file without line terminators; malformed bytes become replacement characters. */
    static List<String> lines(Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return List.of(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
    }

    /** The attributes of the first XML start tag on a line, unescaped, in document order. */
    static Map<String, String> attributes(String line) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(line);
        while (matcher.find()) values.putIfAbsent(matcher.group(1), unescapeXml(matcher.group(2)));
        return values;
    }

    static String unescapeXml(String value) {
        return value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#10;", " ").replace("&#13;", "").replace("&amp;", "&");
    }

    static String stripAnsi(String line) {
        return ANSI.matcher(line).replaceAll("");
    }

    /** Escapes tab, newline, carriage return and backslash for one TSV field. */
    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 == value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            out.append(next == 't' ? '\t' : next == 'n' ? '\n' : next == 'r' ? '\r' : next);
        }
        return out.toString();
    }

    /** At most {@code limit} characters, for chunk texts built from free-form messages. */
    static String clip(String value, int limit) {
        String flat = value.strip().replaceAll("\\s+", " ");
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "...";
    }

    /** {@code group:artifact} of a Maven coordinate {@code group:artifact:type[:classifier]:version[:scope]}. */
    static String ga(String coordinates) {
        String[] parts = coordinates.split(":");
        return parts.length >= 2 ? parts[0] + ":" + parts[1] : coordinates;
    }
}
