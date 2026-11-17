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
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * The tables of the javac plugin, merged across compilations. Besides the tables of {@link CodeClustering#cluster}
 * ({@code ATOM_HASHES.tsv}, {@code CLUSTERS.tsv}, {@code PLANE_SUMMARY.tsv}, {@code FILE_PAIRS.tsv},
 * {@code FILE_CLUSTERS.tsv}) it writes {@code JNI.tsv} ({@link NativeMethods#HEADER}) and {@code ATOM_SOURCES.tsv}
 * (path, source file), which records where every table path comes from.
 * <p>
 * A compilation may compile only some files (an incremental build). Its units replace the rows of their paths; the
 * rows of the previous tables are kept for every path that this compilation did not compile and whose source file
 * still exists; rows whose source was deleted, and rows of a table without {@code ATOM_SOURCES.tsv} (written by the
 * {@code cluster} goal), are dropped. The clusters and file families are then computed again over the merged atoms.
 * A full compilation of the same sources therefore gives the tables {@code cluster} gives for that source root.
 */
final class InlineTables {
    static final String ATOMS = "ATOM_HASHES.tsv";
    static final String JNI = "JNI.tsv";
    static final String SOURCES = "ATOM_SOURCES.tsv";
    private static final String SOURCES_HEADER = "path\tsource";
    private static final char TAB = '\t';

    private InlineTables() { }

    /** What one compilation recorded, by table path. */
    static final class Units {
        private final Map<String, List<Atom>> atoms = new LinkedHashMap<>();
        private final Map<String, Path> sources = new LinkedHashMap<>();
        private final Map<String, Set<String>> natives = new LinkedHashMap<>();

        /** A parsed unit; parsing the same path again replaces it. */
        void parsed(String path, Path file, List<Atom> found) {
            atoms.put(path, List.copyOf(found));
            sources.put(path, file);
            natives.put(path, new LinkedHashSet<>());
        }

        /** Native methods of an attributed class of the unit at {@code path}; a repeated method is kept once. */
        void natives(String path, List<NativeMethods.NativeMethod> found) {
            found.forEach(n -> natives.computeIfAbsent(path, k -> new LinkedHashSet<>()).add(n.row()));
        }

        boolean isEmpty() {
            return sources.isEmpty();
        }
    }

    /** Merges the units into the tables in {@code options.out()} and writes every table; returns the summary line. */
    static String write(InlineOptions options, Units units) throws IOException {
        Path out = options.out();
        Map<String, Path> sources = kept(out, units);
        List<Atom> atoms = merged(previousAtoms(out, sources.keySet()), units.atoms.values());
        List<String> natives = merged(previousRows(out.resolve(JNI), NativeMethods.HEADER, sources.keySet()),
                units.natives.values());
        sources.putAll(units.sources);
        PlaneTables.writeAtoms(out.resolve(ATOMS), atoms);
        String summary = PlaneTables.writeClusters(out, atoms, options.minTokens(), options.radius()) + " | "
                + FileClusters.write(out, atoms, options.minTokens(), options.fileThreshold());
        writeRows(out.resolve(JNI), NativeMethods.HEADER, natives);
        writeRows(out.resolve(SOURCES), SOURCES_HEADER, sources.entrySet().stream()
                .map(e -> e.getKey() + TAB + e.getValue()).collect(Collectors.toList()));
        return summary + " | JNI " + natives.size() + " native methods | " + units.sources.size() + " units compiled";
    }

    /** The previous paths this compilation did not compile whose source file still exists. */
    private static Map<String, Path> kept(Path out, Units units) throws IOException {
        Map<String, Path> kept = new LinkedHashMap<>();
        for (String row : previousRows(out.resolve(SOURCES), SOURCES_HEADER, null)) {
            String[] cells = row.split(String.valueOf(TAB), 2);
            Path source = Path.of(cells[1]);
            if (!units.sources.containsKey(cells[0]) && Files.isRegularFile(source)) kept.put(cells[0], source);
        }
        return kept;
    }

    private static List<Atom> previousAtoms(Path out, Set<String> kept) throws IOException {
        Path table = out.resolve(ATOMS);
        if (kept.isEmpty() || !Files.isRegularFile(table)) return List.of();
        return PlaneTables.readAtoms(table).stream().filter(a -> kept.contains(a.path())).collect(Collectors.toList());
    }

    /** The rows of a table under {@code header} whose first cell is kept (all rows when {@code kept} is null). */
    private static List<String> previousRows(Path table, String header, Set<String> kept) throws IOException {
        if (!Files.isRegularFile(table)) return List.of();
        List<String> rows = Files.readAllLines(table, StandardCharsets.UTF_8);
        if (rows.isEmpty() || !rows.get(0).equals(header)) return List.of();
        return rows.subList(1, rows.size()).stream().filter(r -> r.indexOf(TAB) > 0)
                .filter(r -> kept == null || kept.contains(r.substring(0, r.indexOf(TAB)))).collect(Collectors.toList());
    }

    /** The kept rows and the new ones, in path order (stable: the order within a file is kept). */
    private static <T> List<T> merged(List<T> previous, Collection<? extends Collection<T>> compiled) {
        List<T> all = new ArrayList<>(previous);
        compiled.forEach(all::addAll);
        all.sort(Comparator.comparing(InlineTables::pathOf));
        return all;
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
