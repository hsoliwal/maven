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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** compile_commands.json (the Clang JSON compilation database): entries with an {@code arguments} array. */
public final class CompileDatabase {
    private static final Pattern SOURCE = Pattern.compile("(?i).*\\.(c|cc|cp|cpp|cxx|c\\+\\+|m|mm|cu)$");

    /**
     * One translation unit.
     *
     * @param directory absolute working directory of the compile
     * @param arguments the compiler argument list, compiler first
     * @param file absolute source file
     * @param output object file as given to {@code -o}, empty when unknown
     */
    public record Entry(String directory, List<String> arguments, String file, String output) {
        /** Copies the list. */
        public Entry {
            arguments = List.copyOf(arguments);
        }
    }

    private CompileDatabase() {}

    /** The database text, one entry per line. */
    public static String render(List<Entry> entries) {
        return JsonText.writeLines(entries.stream().map(CompileDatabase::object).toList());
    }

    /** The entries of a database file as parsed JSON objects. */
    public static List<?> read(Path file) throws IOException {
        Object parsed = JsonText.parse(Files.readString(file, StandardCharsets.UTF_8));
        if (!(parsed instanceof List<?> list)) {
            throw new IOException("compile database is not a JSON array: " + file);
        }
        return list;
    }

    /** Keeps the entries whose {@code file} is a C, C++, Objective-C or CUDA source (ninja -t compdb lists links too). */
    public static String sourcesOnly(String json) {
        Object parsed = JsonText.parse(json);
        List<?> entries = parsed instanceof List<?> list ? list : List.of();
        return JsonText.writeLines(entries.stream().filter(e -> e instanceof Map<?, ?> map
                && map.get("file") instanceof String file && isSource(file)).toList());
    }

    /** True for a source file extension. */
    public static boolean isSource(String file) {
        return SOURCE.matcher(file).matches();
    }

    private static Map<String, Object> object(Entry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("directory", entry.directory());
        map.put("arguments", entry.arguments());
        map.put("file", entry.file());
        if (!entry.output().isEmpty()) {
            map.put("output", entry.output());
        }
        return map;
    }
}
