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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The JSON this plugin reads and writes (compile databases, CMake presets): objects become ordered maps, arrays
 * lists, numbers {@link BigDecimal}, {@code null} Java null. Strict RFC 8259 syntax; no comments.
 */
public final class JsonText {
    private JsonText() {}

    /** Parses one JSON value; trailing content other than whitespace is an error. */
    public static Object parse(String text) {
        Reader reader = new Reader(text);
        Object value = reader.value();
        reader.skip();
        if (reader.pos != text.length()) {
            throw reader.error("trailing content");
        }
        return value;
    }

    /** Compact JSON of maps, lists, strings, numbers, booleans and null. */
    public static String write(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().map(e -> quote(String.valueOf(e.getKey())) + ":" + write(e.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(JsonText::write).collect(Collectors.joining(",", "[", "]"));
        }
        return value instanceof String text ? quote(text) : String.valueOf(value);
    }

    /** A JSON array with one element per line, as compile databases are usually laid out. */
    public static String writeLines(List<?> elements) {
        return elements.isEmpty() ? "[]\n" : elements.stream().map(e -> "  " + write(e))
                .collect(Collectors.joining(",\n", "[\n", "\n]\n"));
    }

    /** A JSON string literal. */
    public static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        text.chars().forEach(c -> out.append(escape((char) c)));
        return out.append('"').toString();
    }

    private static String escape(char c) {
        return switch (c) {
            case '"' -> "\\\"";
            case '\\' -> "\\\\";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c);
        };
    }

    private static final class Reader {
        private final String text;
        private int pos;

        Reader(String text) {
            this.text = text;
        }

        Object value() {
            skip();
            char c = peek();
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            if (empty('}')) {
                return map;
            }
            do {
                String key = string();
                expect(':');
                map.put(key, value());
            } while (more('}'));
            return map;
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            if (empty(']')) {
                return list;
            }
            do {
                list.add(value());
            } while (more(']'));
            return list;
        }

        /** Consumes the opening bracket, and the closing one too when the container is empty. */
        private boolean empty(char close) {
            pos++;
            skip();
            boolean empty = peek() == close;
            pos += empty ? 1 : 0;
            return empty;
        }

        private boolean more(char close) {
            skip();
            char c = peek();
            pos++;
            if (c != ',' && c != close) {
                throw error("expected ',' or '" + close + "'");
            }
            return c == ',';
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            for (char c = next(); c != '"'; c = next()) {
                if (c < 0x20) {
                    throw error("control character in string");
                }
                out.append(c == '\\' ? unescape() : c);
            }
            return out.toString();
        }

        private char unescape() {
            char e = next();
            return switch (e) {
                case '"', '\\', '/' -> e;
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> hex();
                default -> throw error("bad escape \\" + e);
            };
        }

        private char hex() {
            if (pos + 4 > text.length()) {
                throw error("short \\u escape");
            }
            char value = (char) Integer.parseInt(text.substring(pos, pos + 4), 16);
            pos += 4;
            return value;
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, pos)) {
                throw error("expected " + word);
            }
            pos += word.length();
            return value;
        }

        private BigDecimal number() {
            int start = pos;
            while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            try {
                return new BigDecimal(text.substring(start, pos));
            } catch (NumberFormatException bad) {
                throw error("bad number");
            }
        }

        private void expect(char c) {
            skip();
            if (next() != c) {
                throw error("expected '" + c + "'");
            }
        }

        private char next() {
            char c = peek();
            pos++;
            return c;
        }

        private char peek() {
            if (pos >= text.length()) {
                throw error("unexpected end");
            }
            return text.charAt(pos);
        }

        void skip() {
            while (pos < text.length() && " \t\r\n".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("invalid JSON at offset " + pos + ": " + message);
        }
    }
}
