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

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * The tables of the javac plugin, merged across compilations. Besides the tables of {@link CodeClustering#cluster}
 * ({@code ATOM_HASHES.tsv}, {@code CLUSTERS.tsv}, {@code PLANE_SUMMARY.tsv}, {@code FILE_PAIRS.tsv},
 * {@code FILE_CLUSTERS.tsv}) it writes {@code JNI.tsv} ({@link NativeMethods#HEADER}) and {@code ATOM_SOURCES.tsv}
 * (path, source file), which records where every table path comes from.
 * <p>
 * A full compilation ({@code full=true}) compiles every source of its roots: its units replace the tables. Any other
 * compilation may compile only some files (an incremental build): its units replace the rows of their source files,
 * and the rows of the previous tables are kept for every source file it did not compile that still exists. Rows are
 * kept by source file, not by table path, so a file whose path changed (a new package declaration) is filed once.
 * The clusters and file families are then computed again over the merged atoms.
 * <p>
 * Paths never collide: files of one compilation that would share a table path (default-package files of the same
 * name in two directories, without {@code roots}) are each filed under their absolute path, and so is a kept file
 * whose path a compiled file now takes. Each collision is logged as a warning.
 */
final class InlineTables {
    static final String ATOMS = "ATOM_HASHES.tsv";
    static final String JNI = "JNI.tsv";
    static final String SOURCES = "ATOM_SOURCES.tsv";
    private static final List<String> TABLES = List.of(ATOMS, "CLUSTERS.tsv", "PLANE_SUMMARY.tsv", "FILE_PAIRS.tsv",
            "FILE_CLUSTERS.tsv", JNI, SOURCES);
    private static final String SOURCES_HEADER = "path\tsource";
    private static final char TAB = '\t';
    private static final System.Logger LOG = System.getLogger(InlineTables.class.getName());

    private InlineTables() { }

    /** What one compilation recorded, by source file. */
    static final class Units {
        private final Map<Path, String> paths = new LinkedHashMap<>();
        private final Map<Path, List<Atom>> atoms = new LinkedHashMap<>();
        private final Map<Path, Set<String>> natives = new LinkedHashMap<>();

        /** A parsed unit; parsing the same file again replaces it. */
        void parsed(String path, Path file, List<Atom> found) {
            paths.put(file, path);
            atoms.put(file, List.copyOf(found));
            natives.put(file, new LinkedHashSet<>());
        }

        /** Native methods of an attributed class of the unit in {@code file}; a repeated method is kept once. */
        void natives(Path file, List<NativeMethods.NativeMethod> found) {
            found.forEach(n -> natives.computeIfAbsent(file, k -> new LinkedHashSet<>()).add(n.row()));
        }

        boolean isEmpty() {
            return paths.isEmpty();
        }

        /** The table path of each parsed file; files that share a path are filed under their absolute paths. */
        Map<Path, String> paths() {
            Map<String, Long> uses = paths.values().stream().collect(Collectors.groupingBy(p -> p, Collectors.counting()));
            Map<Path, String> filed = new LinkedHashMap<>();
            paths.forEach((file, path) -> filed.put(file, uses.get(path) > 1 ? collision(path, file) : path));
            return filed;
        }
    }

    /** A previous table path kept by a compilation that did not compile its source, and the path it is filed under. */
    private record Kept(String path, Path source) { }

    /** Merges the units into the tables in {@code out} (replaces them when full) and writes them; returns a summary. */
    static String write(InlineOptions options, Path out, Units units) throws IOException {
        Map<Path, String> paths = units.paths();
        Map<String, Kept> kept = options.full() ? Map.of() : kept(out, paths);
        List<Atom> atoms = merged(previousAtoms(out, kept), refiled(paths, units.atoms, InlineTables::filed));
        List<String> natives = merged(previousRows(out.resolve(JNI), NativeMethods.HEADER, kept),
                refiled(paths, units.natives, InlineTables::filed));
        Map<String, Path> sources = new LinkedHashMap<>();
        kept.values().forEach(k -> sources.put(k.path(), k.source()));
        paths.forEach((file, path) -> sources.put(path, file));
        return writeTables(options, out, atoms, natives, sources) + " | " + paths.size() + " units compiled";
    }

    /** Removes the tables from {@code dir}, for a full compilation that had none of their sources. */
    static void clear(Path dir) throws IOException {
        for (String table : TABLES) Files.deleteIfExists(dir.resolve(table));
    }

    private static String writeTables(InlineOptions options, Path out, List<Atom> atoms, List<String> natives,
            Map<String, Path> sources) throws IOException {
        PlaneTables.writeAtoms(out.resolve(ATOMS), atoms);
        String summary = PlaneTables.writeClusters(out, atoms, options.minTokens(), options.radius()) + " | "
                + FileClusters.write(out, atoms, options.minTokens(), options.fileThreshold());
        writeRows(out.resolve(JNI), NativeMethods.HEADER, natives);
        writeRows(out.resolve(SOURCES), SOURCES_HEADER, sources.entrySet().stream()
                .map(e -> e.getKey() + TAB + e.getValue()).collect(Collectors.toList()));
        return summary + " | JNI " + natives.size() + " native methods";
    }

    /**
     * The previous table paths whose source this compilation did not compile and still exists, with the path each is
     * filed under: its own, or its source's absolute path when a compiled file takes it.
     */
    private static Map<String, Kept> kept(Path out, Map<Path, String> compiled) throws IOException {
        Set<String> taken = new HashSet<>(compiled.values());
        Map<String, Kept> kept = new LinkedHashMap<>();
        for (String row : previousRows(out.resolve(SOURCES), SOURCES_HEADER, null)) {
            String path = row.substring(0, row.indexOf(TAB));
            Path source = Path.of(row.substring(row.indexOf(TAB) + 1));
            if (compiled.containsKey(source) || !Files.isRegularFile(source)) continue;
            kept.put(path, new Kept(taken.contains(path) ? collision(path, source) : path, source));
        }
        return kept;
    }

    private static List<Atom> previousAtoms(Path out, Map<String, Kept> kept) throws IOException {
        Path table = out.resolve(ATOMS);
        if (kept.isEmpty() || !Files.isRegularFile(table)) return List.of();
        return PlaneTables.readAtoms(table).stream().filter(a -> kept.containsKey(a.path()))
                .map(a -> filed(a, kept.get(a.path()).path())).collect(Collectors.toList());
    }

    /** The rows of a table under {@code header} whose path is kept, filed under its kept path (all rows: null). */
    private static List<String> previousRows(Path table, String header, Map<String, Kept> kept) throws IOException {
        if (!Files.isRegularFile(table)) return List.of();
        List<String> rows = Files.readAllLines(table, StandardCharsets.UTF_8);
        if (rows.isEmpty() || !rows.get(0).equals(header)) return List.of();
        return rows.subList(1, rows.size()).stream().filter(r -> r.indexOf(TAB) > 0)
                .filter(r -> kept == null || kept.containsKey(pathOf(r)))
                .map(r -> kept == null ? r : filed(r, kept.get(pathOf(r)).path())).collect(Collectors.toList());
    }

    /** The kept rows and the new ones, in path order (stable: the order within a file is kept). */
    private static <T> List<T> merged(List<T> previous, Collection<? extends Collection<T>> compiled) {
        List<T> all = new ArrayList<>(previous);
        compiled.forEach(all::addAll);
        all.sort(Comparator.comparing(InlineTables::pathOf));
        return all;
    }

    /** The rows of each compiled file, filed under the file's table path. */
    private static <T> List<List<T>> refiled(Map<Path, String> paths, Map<Path, ? extends Collection<T>> rows,
            BiFunction<T, String, T> file) {
        return paths.entrySet().stream().map(e -> rows.get(e.getKey()).stream().map(r -> file.apply(r, e.getValue()))
                .collect(Collectors.toList())).collect(Collectors.toList());
    }

    private static Atom filed(Atom atom, String path) {
        return atom.path().equals(path) ? atom : new Atom(path, atom.kind(), atom.symbol(), atom.tokens(), atom.exact(),
                atom.logic(), atom.structure(), atom.simhash(), atom.line(), atom.endLine());
    }

    private static String filed(String row, String path) {
        return path + row.substring(row.indexOf(TAB));
    }

    /** Logs that {@code file} shares {@code path} with another file; returns the file's absolute path, its new path. */
    private static String collision(String path, Path file) {
        String absolute = file.toString().replace('\\', '/');
        LOG.log(Level.WARNING, "code clustering: " + file + " shares the table path " + path + " with another file;"
                + " its atoms are filed under " + absolute);
        return absolute;
    }

    private static String pathOf(Object row) {
        if (row instanceof Atom atom) return atom.path();
        String text = row.toString();
        return text.substring(0, text.indexOf(TAB));
    }

    private static void writeRows(Path table, String header, List<String> rows) throws IOException {
        StringBuilder text = new StringBuilder(header).append('\n');
        rows.stream().sorted(Comparator.comparing(InlineTables::pathOf)).forEach(r -> text.append(r).append('\n'));
        Files.createDirectories(table.toAbsolutePath().getParent());
        Files.writeString(table, text, StandardCharsets.UTF_8);
    }
}
