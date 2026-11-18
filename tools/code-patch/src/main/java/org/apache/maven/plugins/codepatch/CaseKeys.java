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
package org.apache.maven.plugins.codepatch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntUnaryOperator;
import java.util.stream.Stream;

/**
 * The target keys of one plan on a case-insensitive base volume, where two keys that differ only in letter case, or
 * whose directories do ({@code d/Foo.txt} and {@code d/foo.txt}, {@code NewDir/a.txt} and {@code newdir/b.txt}), name
 * one file or one directory: the second such key is refused, so apply cannot stop halfway on it. The volume is probed
 * once, with a name inside the base directory, else the base directory's own name or an ancestor's: it is
 * case-insensitive when the other letter case of that name finds the same file. Without a name that has letters it is
 * taken as case-insensitive, the side that refuses.
 */
final class CaseKeys {
    private final Path base;
    private final Map<String, String> spellings = new HashMap<>();
    private Boolean insensitive;

    CaseKeys(Path base) {
        this.base = base;
    }

    /** Records {@code path} and its directories; refuses it when one of them collides ignoring case with a recorded one. */
    void add(String path) throws PatchException {
        if (!insensitive()) return;
        for (String prefix : prefixes(path)) {
            String earlier = spellings.putIfAbsent(folded(prefix), prefix);
            if (earlier != null && !earlier.equals(prefix)) {
                throw new PatchException(PathNames.show(path) + ": " + PathNames.show(prefix) + " and "
                        + PathNames.show(earlier) + " differ only in letter case, which this case-insensitive volume"
                        + " does not tell apart (one file or directory); refused");
            }
        }
    }

    private boolean insensitive() {
        if (insensitive == null) insensitive = probe(base);
        return insensitive;
    }

    /** True when the volume of {@code base} finds a name by its other letter case (no probe name: true). */
    static boolean probe(Path base) {
        Stream<Path> up = Stream.iterate(base.toAbsolutePath(), Objects::nonNull, Path::getParent);
        try (Stream<Path> inside = Files.list(base)) {
            return answer(Stream.concat(inside.filter(Files::exists), up));
        } catch (IOException e) {
            return answer(up);
        }
    }

    /** Whether the other letter case of the first name with letters finds the same file; true without such a name. */
    private static boolean answer(Stream<Path> names) {
        Optional<Path> named = names.filter(p -> other(p).isPresent()).findFirst();
        return named.map(p -> same(p, p.resolveSibling(other(p).get()))).orElse(true);
    }

    /** The name of {@code path} in its other letter case (upper case, or lower case when it is upper case already). */
    private static Optional<String> other(Path path) {
        if (path.getFileName() == null) return Optional.empty();
        String name = path.getFileName().toString();
        String upper = mapped(name, Character::toUpperCase);
        String other = upper.equals(name) ? mapped(name, Character::toLowerCase) : upper;
        return other.equals(name) ? Optional.empty() : Optional.of(other);
    }

    private static boolean same(Path path, Path other) {
        try {
            return Files.exists(other) && Files.isSameFile(path, other);
        } catch (IOException e) {
            return false;
        }
    }

    /** {@code d}, then {@code d/x}: each directory of the path from the top, then the path. */
    private static List<String> prefixes(String path) {
        List<String> prefixes = new ArrayList<>();
        for (int slash = path.indexOf('/'); slash > 0; slash = path.indexOf('/', slash + 1)) {
            prefixes.add(path.substring(0, slash));
        }
        prefixes.add(path);
        return prefixes;
    }

    /** One spelling for all letter cases of a name, code point by code point (as file systems fold names). */
    private static String folded(String name) {
        return mapped(mapped(name, Character::toUpperCase), Character::toLowerCase);
    }

    private static String mapped(String name, IntUnaryOperator caseMapping) {
        return name.codePoints().map(caseMapping)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }
}
