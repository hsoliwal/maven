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
package org.apache.maven.plugins.clustering.clang;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One compile_commands.json (the JSON compilation database written by CMake, Meson, Ninja, Bear and the native build
 * plugin): entries with {@code directory}, {@code file} and either {@code arguments} or a {@code command} string.
 * Relative directories are taken from the database's folder, relative files from the entry's directory. A later
 * entry for the same file replaces an earlier one.
 */
final class CompileDatabase {
    private static final JsonFactory FACTORY = new JsonFactory();

    private final Map<String, CompileCommand> byFile;

    private CompileDatabase(Map<String, CompileCommand> byFile) {
        this.byFile = byFile;
    }

    static CompileDatabase read(Path json, boolean windows) throws IOException {
        Map<String, CompileCommand> byFile = new LinkedHashMap<>();
        try (JsonParser p = FACTORY.createParser(json.toFile())) {
            if (p.nextToken() != JsonToken.START_ARRAY) throw new IOException(json + ": not a compilation database");
            while (p.nextToken() == JsonToken.START_OBJECT) {
                entry(p, json.toAbsolutePath().getParent(), windows).ifPresent(c -> byFile.put(key(c.file(), windows), c));
            }
        }
        return new CompileDatabase(byFile);
    }

    /** The entry that compiles this file. */
    Optional<CompileCommand> exact(Path file, boolean windows) {
        return Optional.ofNullable(byFile.get(key(file, windows)));
    }

    /** All entries, in file order. */
    List<CompileCommand> entries() {
        return List.copyOf(byFile.values());
    }

    /** A file's identity in the database: absolute, normalized, lower case on Windows. */
    static String key(Path file, boolean windows) {
        String key = file.toAbsolutePath().normalize().toString().replace('\\', '/');
        return windows ? key.toLowerCase(Locale.ROOT) : key;
    }

    private static Optional<CompileCommand> entry(JsonParser p, Path base, boolean windows) throws IOException {
        Map<String, String> fields = new HashMap<>();
        List<String> arguments = new ArrayList<>();
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            JsonToken value = p.nextToken();
            if (value == JsonToken.START_ARRAY && name.equals("arguments")) strings(p, arguments);
            else if (value.isScalarValue()) fields.put(name, p.getText());
            else p.skipChildren();
        }
        List<String> words = arguments.isEmpty() ? CommandWords.split(fields.getOrDefault("command", ""), windows)
                : arguments;
        return command(base, fields, words);
    }

    private static Optional<CompileCommand> command(Path base, Map<String, String> fields, List<String> words) {
        if (!fields.containsKey("file") || words.isEmpty()) return Optional.empty();
        Path directory = base.resolve(fields.getOrDefault("directory", ".")).normalize();
        Path file = directory.resolve(fields.get("file")).normalize();
        return Optional.of(new CompileCommand(directory, file, words, Optional.empty()));
    }

    private static void strings(JsonParser p, List<String> into) throws IOException {
        while (p.nextToken() != JsonToken.END_ARRAY) {
            if (p.currentToken().isScalarValue()) into.add(p.getText());
            else p.skipChildren();
        }
    }
}
