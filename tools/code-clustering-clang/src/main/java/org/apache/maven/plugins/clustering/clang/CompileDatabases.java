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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.maven.plugins.clustering.AtomFrontEnd;

/**
 * Finds the compile command of a file. The configured databases are asked first, then the nearest
 * {@code compile_commands.json} or {@code build/compile_commands.json} in the file's folder or a parent folder, as
 * clangd looks for it. A file with its own entry uses it. A header without one borrows the entry of the source file
 * that is closest to it, as clangd does: the same base name first ({@code foo.h} and {@code foo.c}), then the
 * longest common folder path, then database order; it is parsed as a C, C++, Objective-C or Objective-C++ header
 * after that source file. A file with neither gets the configured default flags, if any.
 */
final class CompileDatabases {
    static final Set<String> HEADERS = Set.of("h", "hh", "hpp", "hxx", "h++", "inl", "ipp", "tcc");
    private static final Set<String> CXX_HEADERS = Set.of("hh", "hpp", "hxx", "h++", "ipp", "tcc");

    private final List<Path> explicit;
    private final boolean windows;
    private final Consumer<String> warnings;
    private final Map<Path, Optional<CompileDatabase>> loaded = new HashMap<>();
    private final Map<Path, Optional<Path>> nearest = new HashMap<>();

    CompileDatabases(List<Path> explicit, boolean windows, Consumer<String> warnings) {
        this.explicit = List.copyOf(explicit);
        this.windows = windows;
        this.warnings = warnings;
    }

    /** The command for the file: its own entry, else (for a header) a borrowed one. */
    synchronized Optional<CompileCommand> forFile(Path file) {
        List<CompileDatabase> databases = databases(file);
        Optional<CompileCommand> own = databases.stream().map(d -> d.exact(file, windows)).flatMap(Optional::stream)
                .findFirst();
        if (own.isPresent() || !HEADERS.contains(AtomFrontEnd.extension(file))) return own;
        return databases.stream().flatMap(d -> d.entries().stream())
                .filter(c -> !HEADERS.contains(AtomFrontEnd.extension(c.file()))).max(closeTo(file))
                .map(c -> c.forHeader(headerLanguage(file, c.file())));
    }

    /** The language to parse a header as, after the source file whose entry it borrows. */
    static String headerLanguage(Path header, Path donor) {
        String ext = AtomFrontEnd.extension(donor);
        boolean cxx = CXX_HEADERS.contains(AtomFrontEnd.extension(header));
        if (ext.equals("mm")) return "objective-c++-header";
        if (ext.equals("m")) return cxx ? "objective-c++-header" : "objective-c-header";
        return ext.equals("c") && !cxx ? "c-header" : "c++-header";
    }

    private Comparator<CompileCommand> closeTo(Path header) {
        String stem = stem(header);
        return Comparator.<CompileCommand>comparingInt(c -> stem(c.file()).equals(stem) ? 1 : 0)
                .thenComparingInt(c -> common(c.file().getParent(), header.toAbsolutePath().getParent()));
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private int common(Path a, Path b) {
        int n = 0;
        while (a != null && b != null && n < Math.min(a.getNameCount(), b.getNameCount())
                && same(a.getName(n).toString(), b.getName(n).toString())) n++;
        return a != null && b != null && Objects.equals(a.getRoot(), b.getRoot()) ? n : 0;
    }

    private boolean same(String a, String b) {
        return windows ? a.equalsIgnoreCase(b) : a.equals(b);
    }

    private List<CompileDatabase> databases(Path file) {
        List<CompileDatabase> databases = new ArrayList<>();
        explicit.stream().map(this::load).flatMap(Optional::stream).forEach(databases::add);
        find(file.toAbsolutePath().getParent()).flatMap(this::load).ifPresent(databases::add);
        return databases;
    }

    /** The database for a configured path: a compile_commands.json, or a folder that holds one. */
    private Optional<CompileDatabase> load(Path path) {
        Path json = Files.isDirectory(path) ? path.resolve("compile_commands.json") : path;
        return loaded.computeIfAbsent(json.toAbsolutePath().normalize(), this::read);
    }

    private Optional<CompileDatabase> read(Path json) {
        if (!Files.isRegularFile(json)) return Optional.empty();
        try {
            return Optional.of(CompileDatabase.read(json, windows));
        } catch (IOException | RuntimeException unreadable) {
            warnings.accept("compile_commands.json not read: " + json + ": " + unreadable.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Path> find(Path dir) {
        if (dir == null) return Optional.empty();
        Optional<Path> known = nearest.get(dir);
        if (known != null) return known;
        Optional<Path> here = List.of(dir.resolve("compile_commands.json"), dir.resolve("build/compile_commands.json"))
                .stream().filter(Files::isRegularFile).findFirst();
        Optional<Path> found = here.isPresent() ? here : find(dir.getParent());
        nearest.put(dir, found);
        return found;
    }
}
