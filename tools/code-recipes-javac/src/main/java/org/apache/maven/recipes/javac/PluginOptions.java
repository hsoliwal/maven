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

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The options of {@code -Xplugin:MavenRecipes}.
 *
 * @param census where {@code LST_CENSUS.tsv} is written when the compilation finishes, if anywhere
 * @param root the directory source paths are made relative to
 */
public record PluginOptions(Optional<Path> census, Path root) {
    private static final String CENSUS = "census";
    private static final String ROOT = "root";

    /**
     * Reads {@code key=value} arguments; an unknown key or an argument without {@code =} fails, so a typo does not
     * silently drop the census.
     *
     * @param args the arguments javac passes to {@code Plugin.init}
     * @return the options
     */
    public static PluginOptions parse(String... args) {
        Map<String, String> values = Arrays.stream(args)
                .filter(arg -> !arg.isBlank())
                .map(PluginOptions::split)
                .collect(Collectors.toMap(pair -> pair[0], pair -> pair[1], (first, second) -> second));
        Path root = Path.of(values.getOrDefault(ROOT, System.getProperty("user.dir")))
                .toAbsolutePath()
                .normalize();
        return new PluginOptions(Optional.ofNullable(values.get(CENSUS)).map(Path::of), root);
    }

    private static String[] split(String arg) {
        int equals = arg.indexOf('=');
        String key = equals < 0 ? arg : arg.substring(0, equals);
        if (equals < 0 || !(CENSUS.equals(key) || ROOT.equals(key))) {
            throw new IllegalArgumentException(
                    MavenRecipesPlugin.NAME + ": unknown option '" + arg + "' (expected census=<file> or root=<dir>)");
        }
        return new String[] {key, arg.substring(equals + 1)};
    }
}
