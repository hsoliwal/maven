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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The arguments of the javac plugin, {@code key=value} words after its name:
 * <ul>
 *   <li>{@code out}: the directory of the tables (required);</li>
 *   <li>{@code minTokens}, {@code radius}, {@code fileThreshold}: as for {@link CodeClustering} (same defaults);</li>
 *   <li>{@code roots}: source roots separated by the platform path separator. A file's table path is relative to the
 *       longest root that holds it; without one, to the directory its package declaration names (the usual source
 *       root), else to its own directory.</li>
 * </ul>
 * javac splits the plugin argument at white space, so a word that does not start with one of these keys and
 * {@code =} continues the previous value after one space: {@code out=C:/My Builds/x} keeps its space.
 */
record InlineOptions(Path out, int minTokens, int radius, double fileThreshold, List<Path> roots) {
    private static final Set<String> KEYS = Set.of("out", "minTokens", "radius", "fileThreshold", "roots");

    /** Parses the words; throws {@link IllegalArgumentException} without {@code out}, or for an unknown word. */
    static InlineOptions parse(String... args) {
        Map<String, String> values = values(args);
        String out = values.get("out");
        if (out == null || out.isBlank()) throw new IllegalArgumentException("no out=<directory> argument");
        return new InlineOptions(Path.of(out).toAbsolutePath().normalize(),
                Integer.parseInt(values.getOrDefault("minTokens", Integer.toString(CodeClustering.MIN_TOKENS))),
                Integer.parseInt(values.getOrDefault("radius", Integer.toString(CodeClustering.RADIUS))),
                Double.parseDouble(values.getOrDefault("fileThreshold", Double.toString(CodeClustering.FILE_THRESHOLD))),
                roots(values.getOrDefault("roots", "")));
    }

    static Map<String, String> values(String... args) {
        Map<String, String> values = new LinkedHashMap<>();
        String key = null;
        for (String word : args) {
            int eq = word.indexOf('=');
            String named = eq > 0 ? word.substring(0, eq) : "";
            if (KEYS.contains(named)) {
                key = named;
                values.put(key, word.substring(eq + 1));
            } else if (key != null) {
                values.merge(key, " " + word, String::concat);
            } else {
                throw new IllegalArgumentException("unknown argument " + word);
            }
        }
        return values;
    }

    private static List<Path> roots(String value) {
        List<Path> roots = new ArrayList<>();
        for (String root : value.split(File.pathSeparator)) {
            if (!root.isBlank()) roots.add(Path.of(root).toAbsolutePath().normalize());
        }
        return roots;
    }
}
