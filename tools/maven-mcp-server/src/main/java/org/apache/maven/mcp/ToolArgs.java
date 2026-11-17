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
package org.apache.maven.mcp;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Typed access to the arguments of one tool call. A value of the wrong type is an {@link IllegalArgumentException}
 * naming the argument, which the server returns as a tool error.
 */
final class ToolArgs {

    private final Map<String, Object> values;

    ToolArgs(Map<String, Object> values) {
        this.values = values == null ? Map.of() : values;
    }

    /** A string, number or boolean argument as text; empty when absent. */
    Optional<String> string(String name) {
        Object value = values.get(name);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return Optional.of(value.toString());
        }
        throw new IllegalArgumentException(name + " must be a string");
    }

    String required(String name) {
        return string(name)
                .filter(text -> !text.isEmpty())
                .orElseThrow(() -> new IllegalArgumentException(name + " is required"));
    }

    boolean flag(String name, boolean absent) {
        Object value = values.get(name);
        if (value == null) {
            return absent;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        throw new IllegalArgumentException(name + " must be true or false");
    }

    int number(String name, int absent, int min, int max) {
        Object value = values.get(name);
        if (value == null) {
            return absent;
        }
        long number = value instanceof Number ? ((Number) value).longValue() : Long.MIN_VALUE;
        if (number < min || number > max) {
            throw new IllegalArgumentException(name + " must be an integer from " + min + " to " + max);
        }
        return (int) number;
    }

    List<String> strings(String name) {
        Object value = values.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException(name + " must be an array of strings");
        }
        return ((List<?>) value).stream().map(item -> text(name, item)).collect(Collectors.toList());
    }

    /** An object of string, number or boolean values, sorted by key so the command line is deterministic. */
    Map<String, String> properties(String name) {
        Object value = values.get(name);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        Map<String, String> sorted = new TreeMap<>();
        ((Map<?, ?>) value).forEach((key, item) -> sorted.put(String.valueOf(key), scalar(name, item)));
        return sorted;
    }

    private static String text(String name, Object item) {
        if (item instanceof String) {
            return (String) item;
        }
        throw new IllegalArgumentException(name + " must contain only strings");
    }

    private static String scalar(String name, Object item) {
        if (item instanceof String || item instanceof Number || item instanceof Boolean) {
            return item.toString();
        }
        throw new IllegalArgumentException(name + " values must be strings, numbers or booleans");
    }
}
