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

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The arguments of the javac plugin, {@code key=value} words after its name:
 * <ul>
 *   <li>{@code out}: the directory of the tables (required);</li>
 *   <li>{@code minTokens}, {@code radius}, {@code fileThreshold}: as for {@link CodeClustering} (same defaults);</li>
 *   <li>{@code roots}: source roots separated by the platform path separator; the key may repeat. A file's table path
 *       is relative to the longest root that holds it; without one, to the directory its package declaration names
 *       (the usual source root), else to its own directory;</li>
 *   <li>{@code generated}: the directory annotation processors write sources to; its files get their own tables in
 *       {@code out/generated}, with paths relative to it;</li>
 *   <li>{@code full}: {@code true} when the compilation compiles every source of the root, so the tables are replaced
 *       instead of merged (see {@link InlineTables}).</li>
 * </ul>
 * A path value may be a {@code file:} URI, which holds no white space ({@link Path#toUri()}); a {@code roots} value
 * that is a URI names one root. javac splits the plugin argument at white space, so a word that does not start with
 * one of the keys and {@code =} continues the previous value after one space: {@code out=C:/My Builds/x} keeps its
 * space, but runs of white space become one space; give such paths as URIs.
 */
record InlineOptions(Path out, int minTokens, int radius, double fileThreshold, List<Path> roots,
        Optional<Path> generated, boolean full) {
    private static final Set<String> KEYS = Set.of("out", "minTokens", "radius", "fileThreshold", "roots", "generated",
            "full");
    private static final String URI_SCHEME = "file:";

    /** Parses the words; throws {@link IllegalArgumentException} without {@code out}, or for an unknown word. */
    static InlineOptions parse(String... args) {
        Map<String, List<String>> values = values(args);
        Path out = last(values, "out").filter(v -> !v.isBlank()).map(InlineOptions::path)
                .orElseThrow(() -> new IllegalArgumentException("no out=<directory> argument"));
        return new InlineOptions(out,
                Integer.parseInt(last(values, "minTokens").orElse(Integer.toString(CodeClustering.MIN_TOKENS))),
                Integer.parseInt(last(values, "radius").orElse(Integer.toString(CodeClustering.RADIUS))),
                Double.parseDouble(last(values, "fileThreshold").orElse(Double.toString(CodeClustering.FILE_THRESHOLD))),
                roots(values.getOrDefault("roots", List.of())), last(values, "generated").map(InlineOptions::path),
                last(values, "full").map(Boolean::parseBoolean).orElse(false));
    }

    /** Every value of every key, in order; a continuation word is added to the last value after one space. */
    static Map<String, List<String>> values(String... args) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        List<String> current = null;
        for (String word : args) {
            Optional<String> key = key(word);
            if (key.isEmpty() && current == null) throw new IllegalArgumentException("unknown argument " + word);
            if (key.isPresent()) {
                current = values.computeIfAbsent(key.get(), k -> new ArrayList<>());
                current.add(word.substring(key.get().length() + 1));
            } else {
                current.set(current.size() - 1, current.get(current.size() - 1) + " " + word);
            }
        }
        return values;
    }

    private static Optional<String> key(String word) {
        int eq = word.indexOf('=');
        return eq > 0 && KEYS.contains(word.substring(0, eq)) ? Optional.of(word.substring(0, eq)) : Optional.empty();
    }

    private static Optional<String> last(Map<String, List<String>> values, String key) {
        List<String> given = values.getOrDefault(key, List.of());
        return given.isEmpty() ? Optional.empty() : Optional.of(given.get(given.size() - 1));
    }

    private static List<Path> roots(List<String> values) {
        return values.stream().flatMap(v -> v.startsWith(URI_SCHEME) ? Stream.of(v) : Stream.of(v.split(File.pathSeparator)))
                .filter(root -> !root.isBlank()).map(InlineOptions::path).collect(Collectors.toList());
    }

    /** A file path, or a {@code file:} URI, made absolute and normalized. */
    static Path path(String value) {
        Path path = value.startsWith(URI_SCHEME) ? Path.of(URI.create(value)) : Path.of(value);
        return path.toAbsolutePath().normalize();
    }
}
