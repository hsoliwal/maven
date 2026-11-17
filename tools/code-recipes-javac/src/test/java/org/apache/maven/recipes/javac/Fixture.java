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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One compile of the tests: source files with their exact bytes, extra javac options, and where the cases come from.
 *
 * @param name the fixture name, also its directory
 * @param origin where the cases come from (own, or the rewrite-java-tck test they are copied from)
 * @param files relative path to exact file bytes, in the order javac gets them
 * @param options extra javac options
 * @param managerCharset the charset given to {@code getStandardFileManager} for in-process compiles, as
 *     maven-compiler-plugin does; empty gives none, so javac reads {@code -encoding} from the options
 */
record Fixture(
        String name, String origin, Map<String, byte[]> files, List<String> options, Optional<Charset> managerCharset) {
    /** A fixture of UTF-8 text files without extra options. */
    static Fixture of(String name, String origin, String... pathsAndTexts) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        java.util.stream.IntStream.range(0, pathsAndTexts.length / 2)
                .forEach(i ->
                        files.put(pathsAndTexts[2 * i], pathsAndTexts[2 * i + 1].getBytes(StandardCharsets.UTF_8)));
        return of(name, origin, files);
    }

    /** A fixture of the given file bytes without extra options, read as UTF-8. */
    static Fixture of(String name, String origin, Map<String, byte[]> files) {
        return new Fixture(name, origin, files, List.of(), Optional.of(StandardCharsets.UTF_8));
    }

    Fixture withOptions(String... extra) {
        return new Fixture(name, origin, files, List.of(extra), managerCharset);
    }

    Fixture withManagerCharset(Optional<Charset> charset) {
        return new Fixture(name, origin, files, options, charset);
    }

    Fixture named(String newName) {
        return new Fixture(newName, origin, files, options, managerCharset);
    }

    /** Writes the sources under {@code dir/src} and returns that directory. */
    Path write(Path dir) {
        Path src = dir.resolve("src");
        files.forEach((path, bytes) -> write(src.resolve(path), bytes));
        return src;
    }

    private static void write(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
