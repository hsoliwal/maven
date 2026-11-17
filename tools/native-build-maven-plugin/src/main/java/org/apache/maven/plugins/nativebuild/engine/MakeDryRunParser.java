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

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Reads compile commands from {@code make -nw} output: follows the Entering/Leaving directory lines, joins
 * backslash-continued lines, splits each line into commands and words without a shell, and keeps the compiler calls
 * that compile ({@code -c}) one source file. Dependency-file options (-MD, -MMD, -MP, -MF, -MT, -MQ) are dropped:
 * they name files the language server must not write.
 */
public final class MakeDryRunParser {
    private static final Pattern DIRECTORY = Pattern.compile(
            "^(?:.*[/\\\\])?[\\w.+-]*make(?:\\.exe)?(?:\\[\\d+])?: (Entering|Leaving) directory [`'‘](.*)['’]\\s*$");
    private static final Pattern ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=.*");
    private static final Pattern COMPILER = Pattern.compile(
            "(?:.*-)?(?:cc|gcc|g\\+\\+|c\\+\\+|clang|clang\\+\\+|cl|clang-cl|icx|icpx)(?:-\\d+(?:\\.\\d+)*)?");
    private static final Set<String> FLAGS = Set.of("-MD", "-MMD", "-MP");
    private static final Set<String> FLAGS_WITH_VALUE = Set.of("-MF", "-MT", "-MQ");

    private MakeDryRunParser() {}

    /** Compiler names: the usual ones, versioned or target-prefixed, plus the stems of configured compilers. */
    public static Predicate<String> compilers(Collection<String> configured) {
        Set<String> extra = Set.copyOf(configured.stream().map(WindowsPaths::stem).toList());
        return name -> COMPILER.matcher(name.toLowerCase(Locale.ROOT)).matches() || extra.contains(name);
    }

    /** The compile entries in {@code output}; {@code start} is the directory make started in. */
    public static List<CompileDatabase.Entry> parse(String output, Path start, Predicate<String> compiler) {
        List<CompileDatabase.Entry> entries = new ArrayList<>();
        Deque<Path> directories = new ArrayDeque<>(List.of(start.toAbsolutePath().normalize()));
        output.replace("\r\n", "\n").replace("\\\n", "").lines().forEach(line -> {
            Matcher m = DIRECTORY.matcher(line);
            if (m.matches()) {
                directory(directories, m);
            } else {
                PosixWords.commands(line).forEach(c -> entry(c, directories.peek(), compiler).ifPresent(entries::add));
            }
        });
        return entries;
    }

    private static void directory(Deque<Path> directories, Matcher m) {
        if ("Entering".equals(m.group(1))) {
            directories.push(Path.of(m.group(2)).toAbsolutePath().normalize());
        } else if (directories.size() > 1) {
            directories.pop();
        }
    }

    private static Optional<CompileDatabase.Entry> entry(String command, Path directory, Predicate<String> compiler) {
        List<String> words = PosixWords.words(command);
        int first = (int) words.stream().takeWhile(w -> ASSIGNMENT.matcher(w).matches()).count();
        if (first >= words.size()) {
            return Optional.empty();
        }
        List<String> argv = clean(words.subList(first, words.size()));
        String tool = WindowsPaths.stem(argv.get(0));
        if (!compiler.test(tool) || !(argv.contains("-c") || argv.contains("/c"))) {
            return Optional.empty();
        }
        return source(argv).map(file -> new CompileDatabase.Entry(WindowsPaths.mixed(directory), argv,
                WindowsPaths.mixed(directory.resolve(file)), after(argv, "-o")));
    }

    private static List<String> clean(List<String> words) {
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            String word = i == 0 ? words.get(0).replaceFirst("^[@+-]+", "") : words.get(i);
            if (FLAGS_WITH_VALUE.contains(word)) {
                i++;
            } else if (!FLAGS.contains(word)) {
                kept.add(word);
            }
        }
        return kept;
    }

    private static Optional<String> source(List<String> argv) {
        return IntStream.iterate(argv.size() - 1, i -> i >= 1, i -> i - 1)
                .filter(i -> CompileDatabase.isSource(argv.get(i)) && !"-o".equals(argv.get(i - 1)))
                .mapToObj(argv::get).findFirst();
    }

    private static String after(List<String> argv, String flag) {
        int index = argv.indexOf(flag);
        return index >= 0 && index + 1 < argv.size() ? argv.get(index + 1) : "";
    }
}
