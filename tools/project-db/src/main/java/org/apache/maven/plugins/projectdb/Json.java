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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal JSON for build events and the MCP adapter: objects (insertion order kept), arrays, strings, numbers (Long
 * or Double), booleans and null. Malformed input is an {@link IllegalArgumentException}.
 */
final class Json {
    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        Json json = new Json(text);
        Object value = json.value();
        json.space();
        if (json.at != text.length()) throw new IllegalArgumentException("trailing JSON at " + json.at);
        return value;
    }

    /** The scalar fields of a JSON object line as text (nested values as compact JSON); empty if not an object. */
    static Map<String, String> flatObject(String line) {
        try {
            Object value = parse(line);
            if (!(value instanceof Map<?, ?> object)) return Map.of();
            Map<String, String> fields = new TreeMap<>();
            object.forEach((k, v) -> fields.put(String.valueOf(k), v instanceof String s ? s : write(v)));
            return fields;
        } catch (IllegalArgumentException malformed) {
            return Map.of();
        }
    }

    private Object value() {
        space();
        if (at >= text.length()) throw new IllegalArgumentException("unexpected end of JSON");
        char c = text.charAt(at);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (text.startsWith("true", at)) return literal("true", Boolean.TRUE);
        if (text.startsWith("false", at)) return literal("false", Boolean.FALSE);
        if (text.startsWith("null", at)) return literal("null", null);
        return number();
    }

    private Object literal(String word, Object value) {
        at += word.length();
        return value;
    }

    private Map<String, Object> object() {
        Map<String, Object> object = new LinkedHashMap<>();
        expect('{');
        if (peek() == '}') return close(object);
        do {
            space();
            String key = string();
            expect(':');
            object.put(key, value());
        } while (comma());
        expect('}');
        return object;
    }

    private Map<String, Object> close(Map<String, Object> object) {
        at++;
        return object;
    }

    private List<Object> array() {
        List<Object> array = new ArrayList<>();
        expect('[');
        if (peek() == ']') {
            at++;
            return array;
        }
        do {
            array.add(value());
        } while (comma());
        expect(']');
        return array;
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (at < text.length() && text.charAt(at) != '"') {
            char c = text.charAt(at++);
            out.append(c == '\\' ? escaped() : c);
        }
        expect('"');
        return out.toString();
    }

    private char escaped() {
        if (at >= text.length()) throw new IllegalArgumentException("unterminated JSON escape");
        char c = text.charAt(at++);
        if (c != 'u') return switch (c) { case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r'; case 'b' -> '\b';
            case 'f' -> '\f'; default -> c; };
        if (at + 4 > text.length()) throw new IllegalArgumentException("short JSON unicode escape");
        char code = (char) Integer.parseInt(text.substring(at, at + 4), 16);
        at += 4;
        return code;
    }

    private Object number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
        String number = text.substring(start, at);
        if (number.isEmpty()) throw new IllegalArgumentException("unexpected JSON at " + start);
        return number.contains(".") || number.contains("e") || number.contains("E")
                ? (Object) Double.valueOf(number) : (Object) Long.valueOf(number);
    }

    private boolean comma() {
        space();
        if (peek() != ',') return false;
        at++;
        return true;
    }

    private void expect(char c) {
        space();
        if (at >= text.length() || text.charAt(at) != c) throw new IllegalArgumentException("expected " + c + " at " + at);
        at++;
    }

    private char peek() {
        space();
        return at < text.length() ? text.charAt(at) : '\0';
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
    }

    /** Compact JSON; map keys in the map's own order. */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value instanceof Map<?, ?> map) {
            writeMap(map, out);
        } else if (value instanceof List<?> list) {
            writeList(list, out);
        } else if (value instanceof String s) {
            quote(s, out);
        } else {
            out.append(value);
        }
    }

    private static void writeMap(Map<?, ?> map, StringBuilder out) {
        out.append('{');
        int index = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (index++ > 0) out.append(',');
            quote(String.valueOf(entry.getKey()), out);
            out.append(':');
            write(entry.getValue(), out);
        }
        out.append('}');
    }

    private static void writeList(List<?> list, StringBuilder out) {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) out.append(',');
            write(list.get(i), out);
        }
        out.append(']');
    }

    private static void quote(String value, StringBuilder out) {
        out.append('"');
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c == '\n') out.append("\\n");
            else if (c == '\t') out.append("\\t");
            else if (c == '\r') out.append("\\r");
            else if (c < 0x20) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        out.append('"');
    }
}
