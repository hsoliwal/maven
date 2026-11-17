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

import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The options of {@code -Xplugin:MavenRecipes}.
 *
 * <p>javac splits the text after the plugin name on runs of whitespace and passes the pieces, without reading quotes
 * ({@code Arguments.getPluginOpts}, JDK 21). A piece that does not start with a known {@code key=} therefore continues
 * the value before it and is joined back with one space, so a path with single spaces arrives whole. A value may be
 * quoted ({@code "..."} or {@code '...'}) or a {@code file:} URI. Only the URI form keeps runs of spaces or tabs: a
 * census path that was joined from pieces is written only into an existing directory, so a collapsed run of spaces
 * never creates a new one.
 *
 * @param census where {@code LST_CENSUS.tsv} is written when the compilation finishes, if anywhere
 * @param root the directory source paths are made relative to
 * @param buildDir the build directory: sources under it are generated and get no tree, as rewrite-maven-plugin skips
 *     every source root under the project's build directory
 * @param check whether each conversion also compares an identity digest of javac's trees ({@code check=true})
 * @param censusJoined whether javac split the census path at whitespace and it was joined back with single spaces
 */
public record PluginOptions(Optional<Path> census, Path root, Path buildDir, boolean check, boolean censusJoined) {
    private static final String CENSUS = "census";
    private static final String ROOT = "root";
    private static final String BUILD_DIR = "buildDir";
    private static final String CHECK = "check";
    private static final Pattern KEY =
            Pattern.compile("^(" + CENSUS + "|" + ROOT + "|" + BUILD_DIR + "|" + CHECK + ")=.*", Pattern.DOTALL);
    private static final String DEFAULT_BUILD_DIR = "target";

    /**
     * Reads {@code key=value} arguments.
     *
     * @param args the arguments javac passes to {@code Plugin.init}
     * @return the options
     * @throws IllegalArgumentException for an unknown key, an argument without {@code =}, an empty value or a value
     *     that is no path, so a typo does not silently drop the census; the plugin turns it into a notice
     */
    public static PluginOptions parse(String... args) {
        List<String> options = joined(args);
        Map<String, String> values = values(options);
        Path root = path(ROOT, values.getOrDefault(ROOT, System.getProperty("user.dir")));
        Path buildDir = Optional.ofNullable(values.get(BUILD_DIR))
                .map(value -> path(BUILD_DIR, value))
                .orElse(root.resolve(DEFAULT_BUILD_DIR));
        Optional<Path> census = Optional.ofNullable(values.get(CENSUS)).map(PluginOptions::censusPath);
        return new PluginOptions(census, root, buildDir, check(values), wasJoined(CENSUS, options, args));
    }

    /** The arguments with every piece that starts no option appended to the one before it. */
    private static List<String> joined(String... args) {
        List<String> options = new ArrayList<>();
        Arrays.stream(args).filter(arg -> !arg.isBlank()).forEach(arg -> {
            if (KEY.matcher(arg).matches() || options.isEmpty()) {
                options.add(arg);
            } else {
                options.set(options.size() - 1, options.get(options.size() - 1) + " " + arg);
            }
        });
        return options;
    }

    /** Whether the option was joined from several of javac's pieces. */
    private static boolean wasJoined(String key, List<String> options, String... args) {
        String prefix = key + "=";
        return options.stream()
                .filter(option -> option.startsWith(prefix))
                .anyMatch(option -> Arrays.stream(args).noneMatch(option::equals));
    }

    private static boolean check(Map<String, String> values) {
        String value = values.getOrDefault(CHECK, "false");
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException(CHECK + "='" + value + "' is neither true nor false");
        }
        return Boolean.parseBoolean(value);
    }

    private static Map<String, String> values(List<String> options) {
        Map<String, String> values = new LinkedHashMap<>();
        options.forEach(option -> {
            if (!KEY.matcher(option).matches()) {
                throw new IllegalArgumentException("unknown option '" + option + "' (expected " + CENSUS + "=<file>, "
                        + ROOT + "=<dir>, " + BUILD_DIR + "=<dir> or " + CHECK + "=true)");
            }
            int equals = option.indexOf('=');
            values.put(option.substring(0, equals), unquoted(option.substring(equals + 1)));
        });
        return values;
    }

    private static String unquoted(String value) {
        boolean quoted = value.length() >= 2
                && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                && value.charAt(value.length() - 1) == value.charAt(0);
        return quoted ? value.substring(1, value.length() - 1) : value;
    }

    private static Path censusPath(String value) {
        return value.startsWith("file:") ? path(CENSUS, value) : Path.of(nonEmpty(CENSUS, value));
    }

    /** An absolute, normalized path from a path or a {@code file:} URI. */
    private static Path path(String key, String value) {
        try {
            Path path = value.startsWith("file:") ? Path.of(URI.create(value)) : Path.of(nonEmpty(key, value));
            return path.toAbsolutePath().normalize();
        } catch (IllegalArgumentException | FileSystemNotFoundException e) {
            throw new IllegalArgumentException(key + "='" + value + "' is no path: " + e.getMessage(), e);
        }
    }

    private static String nonEmpty(String key, String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + "= needs a path");
        }
        return value;
    }
}
