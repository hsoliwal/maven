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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.apache.maven.plugins.clustering.AtomFrontEnd;

/**
 * The front end's configuration, from system properties (for example {@code -D} on the Maven command line) or the
 * environment variable named in brackets:
 * <ul>
 *   <li>{@code clustering.clang.enabled} ({@code CLUSTERING_CLANG_ENABLED}): {@code false} turns the front end off;</li>
 *   <li>{@code clustering.clang.executable} ({@code CLANG}): the clang to run, a path or a name on PATH. Default:
 *       {@code clang} on PATH, then the LLVM installation folders. Without clang the front end takes no file;</li>
 *   <li>{@code clustering.clang.compileCommands}: compile_commands.json files or folders that hold one, separated by
 *       commas, asked before the database found next to each file;</li>
 *   <li>{@code clustering.clang.flags} ({@code CLUSTERING_CLANG_FLAGS}): compiler flags for files that no database
 *       covers, split like a command line. Without them such files go to the lexical front end;</li>
 *   <li>{@code clustering.clang.stubIncludes}: a folder for empty stand-ins of headers clang cannot find (see
 *       {@link MissingIncludes}); off by default;</li>
 *   <li>{@code clustering.clang.threads}: clang processes at a time, default half the processors;</li>
 *   <li>{@code clustering.clang.timeoutSeconds}: the longest one file may take, default 300.</li>
 * </ul>
 */
record ClangSettings(Optional<Path> clang, List<Path> databases, List<String> flags, Optional<Path> stubs,
        int threads, Duration timeout, boolean windows) {
    private static final List<String> INSTALLED = List.of("C:/Program Files/LLVM/bin/clang.exe",
            "/opt/homebrew/opt/llvm/bin/clang", "/usr/local/opt/llvm/bin/clang", "/usr/bin/clang", "/usr/local/bin/clang");

    ClangSettings {
        databases = List.copyOf(databases);
        flags = List.copyOf(flags);
    }

    /** Settings from system properties, then environment variables. */
    static ClangSettings configured() {
        return from(System::getProperty, System::getenv);
    }

    static ClangSettings from(UnaryOperator<String> properties, UnaryOperator<String> environment) {
        Lookup look = new Lookup(properties, environment);
        boolean windows = File.separatorChar == '\\';
        boolean enabled = !"false".equalsIgnoreCase(look.get("clustering.clang.enabled", "CLUSTERING_CLANG_ENABLED"));
        Optional<Path> clang = enabled ? clang(look.get("clustering.clang.executable", "CLANG"), environment, windows)
                : Optional.empty();
        return new ClangSettings(clang, paths(look.get("clustering.clang.compileCommands", "")),
                CommandWords.split(look.get("clustering.clang.flags", "CLUSTERING_CLANG_FLAGS"), windows),
                paths(look.get("clustering.clang.stubIncludes", "")).stream().findFirst(),
                number(look.get("clustering.clang.threads", ""), Math.max(1, Runtime.getRuntime().availableProcessors() / 2)),
                Duration.ofSeconds(number(look.get("clustering.clang.timeoutSeconds", ""), 300)), windows);
    }

    /** The default command for a file no database covers: clang, the configured flags, the file's language. */
    Optional<CompileCommand> defaults(Path file) {
        if (flags.isEmpty() || clang.isEmpty()) return Optional.empty();
        List<String> words = Stream.concat(Stream.of(clang.get().toString()), flags.stream()).toList();
        CompileCommand command = new CompileCommand(file.toAbsolutePath().getParent(), file.toAbsolutePath(), words,
                Optional.empty());
        boolean header = CompileDatabases.HEADERS.contains(AtomFrontEnd.extension(file));
        return Optional.of(header ? command.forHeader(siblingLanguage(file)) : command);
    }

    /** A header with default flags is C when its folder holds C sources and no C++ ones, else C++. */
    private static String siblingLanguage(Path header) {
        try (Stream<Path> siblings = Files.list(header.toAbsolutePath().getParent())) {
            List<String> exts = siblings.map(AtomFrontEnd::extension).toList();
            boolean c = exts.contains("c");
            boolean cxx = exts.stream().anyMatch(e -> e.equals("cc") || e.equals("cpp") || e.equals("cxx"));
            return CompileDatabases.headerLanguage(header, Path.of(c && !cxx ? "x.c" : "x.cpp"));
        } catch (IOException | RuntimeException unreadable) {
            return CompileDatabases.headerLanguage(header, Path.of("x.cpp"));
        }
    }

    private static Optional<Path> clang(String configured, UnaryOperator<String> environment, boolean windows) {
        if (!configured.isEmpty()) return executable(configured, environment, windows);
        return executable("clang", environment, windows)
                .or(() -> INSTALLED.stream().map(Path::of).filter(Files::isRegularFile).findFirst());
    }

    /** A path to a file, or a name found on PATH (with {@code .exe} on Windows). */
    static Optional<Path> executable(String value, UnaryOperator<String> environment, boolean windows) {
        try {
            if (value.contains("/") || value.contains("\\")) {
                return Optional.of(Path.of(value)).filter(Files::isRegularFile);
            }
            String path = Optional.ofNullable(environment.apply("PATH")).orElse("");
            List<String> names = windows && !value.toLowerCase(Locale.ROOT).endsWith(".exe") ? List.of(value + ".exe", value)
                    : List.of(value);
            return Arrays.stream(path.split(File.pathSeparator)).filter(dir -> !dir.isBlank())
                    .flatMap(dir -> names.stream().map(name -> Path.of(dir.replace("\"", ""), name)))
                    .filter(Files::isRegularFile).findFirst();
        } catch (InvalidPathException unusable) {
            return Optional.empty();
        }
    }

    private static List<Path> paths(String list) {
        return Arrays.stream(list.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Path::of).toList();
    }

    private static int number(String value, int otherwise) {
        try {
            return value.isBlank() ? otherwise : Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException notANumber) {
            return otherwise;
        }
    }

    /** A system property, else an environment variable, else empty. */
    private record Lookup(UnaryOperator<String> properties, UnaryOperator<String> environment) {
        String get(String property, String variable) {
            String value = properties.apply(property);
            if (value == null && !variable.isEmpty()) value = environment.apply(variable);
            return value == null ? "" : value;
        }
    }
}
