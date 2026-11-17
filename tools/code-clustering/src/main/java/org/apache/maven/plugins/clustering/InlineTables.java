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

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * The tables of the javac plugin, merged across compilations. Besides the tables of {@link CodeClustering#cluster}
 * ({@code ATOM_HASHES.tsv}, {@code CLUSTERS.tsv}, {@code PLANE_SUMMARY.tsv}, {@code FILE_PAIRS.tsv},
 * {@code FILE_CLUSTERS.tsv}) it writes {@code JNI.tsv} ({@link NativeMethods#HEADER}) and {@code ATOM_SOURCES.tsv}:
 * path (the table path), source (the source file, relative to the tables' directory, so the tables survive a moved
 * workspace) and root_path (the file's path relative to its root).
 * <p>
 * A full compilation ({@code full=true}) compiles every source of its roots: its units replace the tables. Any other
 * compilation may compile only some files (an incremental build): its units replace the rows of their source files,
 * and the rows of the previous tables are kept for every source file it did not compile that still exists. Rows are
 * kept by source file, not by table path, so a file whose path changed (a new package declaration) is filed once.
 * The clusters and file families are then computed again over the merged atoms.
 * <p>
 * Paths never collide. When files share a root path (default-package files of the same name in two roots), each is
 * filed under {@code @<n>/<root path>}, {@code n} the position of its root in {@code roots} from 0, or, outside every
 * root, under its path relative to the tables' directory. The decision covers the compiled and the kept files
 * together, so a file's table path does not depend on which compilation wrote it. Each collision is logged as a
 * warning.
 */
final class InlineTables {
    static final String ATOMS = "ATOM_HASHES.tsv";
    static final String JNI = "JNI.tsv";
    static final String SOURCES = "ATOM_SOURCES.tsv";
    private static final List<String> TABLES = List.of(ATOMS, "CLUSTERS.tsv", "PLANE_SUMMARY.tsv", "FILE_PAIRS.tsv",
            "FILE_CLUSTERS.tsv", JNI, SOURCES);
    private static final String SOURCES_HEADER = "path\tsource\troot_path";
    /** The columns every version of {@code ATOM_SOURCES.tsv} starts with (the first had no root_path). */
    private static final String SOURCES_COLUMNS = "path\tsource";
    private static final char TAB = '\t';
    private static final System.Logger LOG = System.getLogger(InlineTables.class.getName());

    private InlineTables() { }

    /** What one compilation recorded, by source file, for the tables of one set of roots. */
    static final class Units {
        private final List<Path> roots;
        private final Map<Path, String> paths = new LinkedHashMap<>();
        private final Map<Path, List<Atom>> atoms = new LinkedHashMap<>();
        private final Map<Path, Set<String>> natives = new LinkedHashMap<>();

        Units(List<Path> roots) {
            this.roots = List.copyOf(roots);
        }

        /** A parsed unit and its root path; parsing the same file again replaces it. */
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
    }

    /** A source file of the tables: its root path, the table path it is filed under now, and the one it had. */
    private record Source(Path file, String rootPath, String filed, String previous) { }

    /** Merges the units into the tables in {@code out} (replaces them when full) and writes them; returns a summary. */
    static String write(InlineOptions options, Path out, Units units) throws IOException {
        List<Source> sources = filing(out, units, options.full() ? List.of() : kept(out, units));
        Map<String, String> keptPaths = sources.stream().filter(s -> s.previous() != null)
                .collect(Collectors.toMap(Source::previous, Source::filed, (a, b) -> a));
        Map<Path, String> compiled = sources.stream().filter(s -> s.previous() == null)
                .collect(Collectors.toMap(Source::file, Source::filed, (a, b) -> a, LinkedHashMap::new));
        List<Atom> atoms = merged(previousAtoms(out, keptPaths), refiled(compiled, units.atoms, InlineTables::filed));
        List<String> natives = merged(previousRows(out.resolve(JNI), NativeMethods.HEADER, keptPaths),
                refiled(compiled, units.natives, InlineTables::filed));
        List<String> rows = sources.stream().map(s -> s.filed() + TAB + portable(out, s.file()) + TAB + s.rootPath())
                .collect(Collectors.toList());
        return writeTables(options, out, atoms, natives, rows) + " | " + compiled.size() + " units compiled";
    }

    /**
     * Drops the rows of source files that no longer exist, merging what remains as an incremental compilation of no
     * file would; empty when every source still exists (nothing written).
     */
    static Optional<String> prune(InlineOptions options, Path out, List<Path> roots) throws IOException {
        boolean deleted = rows(out.resolve(SOURCES), SOURCES_COLUMNS).stream()
                .anyMatch(r -> !Files.isRegularFile(source(out, r)));
        if (!deleted) return Optional.empty();
        InlineOptions merging = new InlineOptions(out, options.minTokens(), options.radius(), options.fileThreshold(),
                roots, Optional.empty(), false);
        return Optional.of(write(merging, out, new Units(roots)));
    }

    /** Removes the tables from {@code dir}, for a full compilation that had none of their sources. */
    static void clear(Path dir) throws IOException {
        for (String table : TABLES) Files.deleteIfExists(dir.resolve(table));
    }

    private static String writeTables(InlineOptions options, Path out, List<Atom> atoms, List<String> natives,
            List<String> sources) throws IOException {
        PlaneTables.writeAtoms(out.resolve(ATOMS), atoms);
        String summary = PlaneTables.writeClusters(out, atoms, options.minTokens(), options.radius()) + " | "
                + FileClusters.write(out, atoms, options.minTokens(), options.fileThreshold());
        writeRows(out.resolve(JNI), NativeMethods.HEADER, natives);
        writeRows(out.resolve(SOURCES), SOURCES_HEADER, sources);
        return summary + " | JNI " + natives.size() + " native methods";
    }

    /**
     * The previous sources this compilation did not compile and that still exist; root path from the roots when one
     * holds the file, else as recorded.
     */
    private static List<Source> kept(Path out, Units units) throws IOException {
        return rows(out.resolve(SOURCES), SOURCES_COLUMNS).stream().map(r -> kept(out, units, r))
                .flatMap(Optional::stream).collect(Collectors.toList());
    }

    private static Optional<Source> kept(Path out, Units units, String row) {
        String[] columns = row.split(String.valueOf(TAB), -1);
        Path file = source(out, row);
        if (units.paths.containsKey(file) || !Files.isRegularFile(file)) return Optional.empty();
        String recorded = columns.length > 2 ? columns[2] : columns[0];
        String rootPath = UnitPaths.root(file, units.roots).map(r -> slashes(r.relativize(file))).orElse(recorded);
        return Optional.of(new Source(file, rootPath, null, columns[0]));
    }

    /** Every source, compiled ones first, with its table path: its root path unless another source shares that. */
    private static List<Source> filing(Path out, Units units, List<Source> kept) {
        List<Source> all = new ArrayList<>();
        units.paths.forEach((file, path) -> all.add(new Source(file, path, null, null)));
        all.addAll(kept);
        Map<String, Long> uses = all.stream().collect(Collectors.groupingBy(Source::rootPath, Collectors.counting()));
        return all.stream().map(s -> new Source(s.file(), s.rootPath(),
                uses.get(s.rootPath()) > 1 ? collision(out, units.roots, s) : s.rootPath(), s.previous()))
                .collect(Collectors.toList());
    }

    /** The source column of an {@code ATOM_SOURCES.tsv} row, resolved against the tables' directory. */
    private static Path source(Path out, String row) {
        String[] columns = row.split(String.valueOf(TAB), -1);
        return out.resolve(columns[1]).toAbsolutePath().normalize();
    }

    private static List<Atom> previousAtoms(Path out, Map<String, String> refiled) throws IOException {
        Path table = out.resolve(ATOMS);
        if (refiled.isEmpty() || !Files.isRegularFile(table)) return List.of();
        return PlaneTables.readAtoms(table).stream().filter(a -> refiled.containsKey(a.path()))
                .map(a -> filed(a, refiled.get(a.path()))).collect(Collectors.toList());
    }

    /** The rows of a table under {@code header} whose path is kept, filed under its new path. */
    private static List<String> previousRows(Path table, String header, Map<String, String> refiled) throws IOException {
        return rows(table, header).stream().filter(r -> refiled.containsKey(pathOf(r)))
                .map(r -> filed(r, refiled.get(pathOf(r)))).collect(Collectors.toList());
    }

    /** The rows of a table whose header starts with {@code columns}; none without the table. */
    private static List<String> rows(Path table, String columns) throws IOException {
        if (!Files.isRegularFile(table)) return List.of();
        List<String> rows = Files.readAllLines(table, StandardCharsets.UTF_8);
        if (rows.isEmpty() || !rows.get(0).startsWith(columns)) return List.of();
        return rows.subList(1, rows.size()).stream().filter(r -> r.indexOf(TAB) > 0).collect(Collectors.toList());
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

    /** Logs that a source shares its root path with another; returns its table path, {@code @<n>/...} or portable. */
    private static String collision(Path out, List<Path> roots, Source source) {
        String path = UnitPaths.root(source.file(), roots).map(r -> "@" + roots.indexOf(r) + "/" + source.rootPath())
                .orElseGet(() -> portable(out, source.file()));
        LOG.log(Level.WARNING, "code clustering: " + source.file() + " shares the table path " + source.rootPath()
                + " with another file; its atoms are filed under " + path);
        return path;
    }

    /** The file relative to the tables' directory, else (another drive) its absolute path; {@code /} separators. */
    private static String portable(Path out, Path file) {
        try {
            return slashes(out.toAbsolutePath().normalize().relativize(file));
        } catch (IllegalArgumentException e) {
            return slashes(file);
        }
    }

    private static String slashes(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String pathOf(Object row) {
        if (row instanceof Atom atom) return atom.path();
        String text = row.toString();
        return text.substring(0, text.indexOf(TAB));
    }

    /** Writes the header and the rows in path order, streaming (no copy of the whole table in memory). */
    private static void writeRows(Path table, String header, List<String> rows) throws IOException {
        Files.createDirectories(table.toAbsolutePath().getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(table, StandardCharsets.UTF_8)) {
            writer.write(header + '\n');
            for (String row : rows.stream().sorted(Comparator.comparing(InlineTables::pathOf)).collect(Collectors.toList())) {
                writer.write(row + '\n');
            }
        }
    }
}
