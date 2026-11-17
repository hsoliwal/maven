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
package org.apache.maven.agent.events;

import java.util.Collection;
import java.util.Optional;

/**
 * JSON text of the agent files: string escaping, bounded strings and an ordered object builder. Writing only; the
 * files are read by agents and tools, never parsed back here.
 */
public final class JsonText {
    /** Marker appended to a string cut by {@link #clip(String, int)}. */
    public static final String ELLIPSIS = "...";

    private JsonText() {}

    /** The JSON string literal of {@code value}; {@code null} gives the literal {@code null}. */
    public static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        value.chars().forEach(c -> escape(out, (char) c));
        return out.append('"').toString();
    }

    /** Escapes quote, backslash and control characters; surrogates as backslash-u so any reader gets valid UTF-8. */
    private static void escape(StringBuilder out, char c) {
        switch (c) {
            case '"' -> out.append("\\\"");
            case '\\' -> out.append("\\\\");
            case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r");
            case '\t' -> out.append("\\t");
            default -> {
                if (c >= 0x20 && c != 0x7f && !Character.isSurrogate(c) && c != 0x2028 && c != 0x2029) {
                    out.append(c);
                } else {
                    out.append(String.format("\\u%04x", (int) c));
                }
            }
        }
    }

    /**
     * {@code value} with at most {@code max} characters: a longer value keeps its first {@code max - 3} characters
     * (never half of a surrogate pair) followed by {@link #ELLIPSIS}. {@code null} stays {@code null}.
     */
    public static String clip(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int end = Math.max(0, max - ELLIPSIS.length());
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end) + ELLIPSIS;
    }

    /** {@code value}, or {@code null} for an empty string, so unknown fields are written as JSON null. */
    public static String orNull(String value) {
        return Optional.ofNullable(value).filter(text -> !text.isEmpty()).orElse(null);
    }

    /** Appends {@code value}: null, numbers and booleans as literals, {@link Obj} as is, collections as arrays. */
    static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Obj object) {
            out.append(object.text());
        } else if (value instanceof Integer || value instanceof Long || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Collection<?> items) {
            out.append('[');
            items.stream().reduce(true, (first, item) -> separate(out, first, item), (a, b) -> a);
            out.append(']');
        } else {
            out.append(quote(value.toString()));
        }
    }

    private static boolean separate(StringBuilder out, boolean first, Object item) {
        if (!first) {
            out.append(',');
        }
        append(out, item);
        return false;
    }

    /** An ordered JSON object: fields keep insertion order and a field is written once per {@link #put}. */
    public static final class Obj {
        private final StringBuilder body = new StringBuilder();

        /** Adds {@code key} with {@code value} (see {@link JsonText#append}); returns this object. */
        public Obj put(String key, Object value) {
            if (body.length() > 0) {
                body.append(',');
            }
            body.append(quote(key)).append(':');
            append(body, value);
            return this;
        }

        /** Adds every field of {@code other} after the fields already present. */
        public Obj putAll(Obj other) {
            if (other.body.length() > 0) {
                body.append(body.length() > 0 ? "," : "").append(other.body);
            }
            return this;
        }

        /** The object as JSON text. */
        public String text() {
            return "{" + body + "}";
        }

        @Override
        public String toString() {
            return text();
        }
    }
}
