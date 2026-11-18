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

import org.apache.maven.plugins.clustering.AtomPlanes.Atom;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Writes and reads the atom table, and groups duplicates. {@code ATOM_HASHES.tsv}: one row per atom.
 * {@code CLUSTERS.tsv}: atoms of the same kind that share the exact, logic or structure value, or whose SimHash values
 * lie within a Hamming radius (the radius is checked between every member and the group's first member). Atoms smaller
 * than a token floor (accessors, one-liners) are left out of the clusters, not of the table. {@code PLANE_SUMMARY.tsv}:
 * clusters and clustered atoms per plane. Every cluster is a proposal: "These files may be similar. Please confirm via
 * hash or diff."
 */
public final class PlaneTables {
    static final String HEADER = "path\tkind\tsymbol\ttokens\texact\tlogic\tstructure\tsimhash\tline\tend_line";
    private static final char TAB = '\t';
    /** Columns of a table written before the line columns were added. */
    private static final int OLD_COLUMNS = 8;
    private static final List<String> PLANES = List.of("EXACT", "LOGIC", "STRUCTURE", "SIMHASH");
    /** SimHash bands: four of 16 bits each. */
    private static final int BANDS = 4;

    private PlaneTables() { }

    /** Writes the atom table row by row (the javac plugin writes it while javac still holds its trees). */
    public static void writeAtoms(Path file, List<Atom> atoms) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write(HEADER + '\n');
            for (Atom a : atoms) {
                out.write(String.join("\t", a.path(), a.kind(), a.symbol(), Integer.toString(a.tokens()), a.exact(),
                        a.logic(), a.structure(), a.simhash(), Integer.toString(a.line()), Integer.toString(a.endLine()))
                        + '\n');
            }
        }
    }

    /** Reads an atom table; a table without the line columns (8 columns) gives atoms with lines 0. */
    public static List<Atom> readAtoms(Path file) throws IOException {
        List<Atom> atoms = new ArrayList<>();
        List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String row : rows.subList(Math.min(1, rows.size()), rows.size())) {
            String[] c = row.split(String.valueOf(TAB), -1);
            if (c.length < OLD_COLUMNS) continue;
            boolean lines = c.length >= OLD_COLUMNS + 2;
            atoms.add(new Atom(c[0], c[1], c[2], Integer.parseInt(c[3]), c[4], c[5], c[6], c[7],
                    lines ? Integer.parseInt(c[8]) : 0, lines ? Integer.parseInt(c[9]) : 0));
        }
        return atoms;
    }

    /**
     * Writes CLUSTERS.tsv and PLANE_SUMMARY.tsv; returns the summary line. One plane at a time, its rows streamed to
     * the file, so only one plane's groups are held (the javac plugin writes at the end of the build's compilation).
     */
    public static String writeClusters(Path dir, List<Atom> atoms, int floor, int radius) throws IOException {
        List<Atom> sized = atoms.stream().filter(a -> a.tokens() >= floor).collect(Collectors.toList());
        StringBuilder summary = new StringBuilder("plane\tclusters\tclustered_atoms\n");
        StringBuilder line = new StringBuilder("atoms " + atoms.size() + " (" + sized.size() + " with " + floor + "+ tokens)");
        Files.createDirectories(dir);
        try (BufferedWriter out = Files.newBufferedWriter(dir.resolve("CLUSTERS.tsv"), StandardCharsets.UTF_8)) {
            out.write("plane\tcluster\tkind\tmembers\ttokens\tatoms\n");
            for (String plane : PLANES) tally(plane, writePlane(out, plane, clusters(plane, sized, radius)), summary, line);
        }
        Files.writeString(dir.resolve("PLANE_SUMMARY.tsv"), summary, StandardCharsets.UTF_8);
        return line.toString();
    }

    private static List<List<Atom>> clusters(String plane, List<Atom> sized, int radius) {
        if (plane.equals("SIMHASH")) return near(sized, radius);
        return groups(sized, plane.equals("EXACT") ? Atom::exact : plane.equals("LOGIC") ? Atom::logic : Atom::structure);
    }

    /** Writes one plane's rows; returns its cluster and member counts. */
    private static int[] writePlane(BufferedWriter out, String plane, List<List<Atom>> found) throws IOException {
        int id = 0;
        for (List<Atom> group : found) out.write(row(plane, ++id, group));
        return new int[] {found.size(), found.stream().mapToInt(List::size).sum()};
    }

    private static void tally(String plane, int[] counts, StringBuilder summary, StringBuilder line) {
        summary.append(plane).append(TAB).append(counts[0]).append(TAB).append(counts[1]).append('\n');
        line.append(" | ").append(plane).append(' ').append(counts[0]).append('/').append(counts[1]);
    }

    private static String row(String plane, int id, List<Atom> group) {
        String members = group.stream().map(a -> a.path() + "#" + a.symbol()).collect(Collectors.joining("|"));
        return plane + TAB + id + TAB + group.get(0).kind() + TAB + group.size() + TAB + group.get(0).tokens() + TAB
                + members + '\n';
    }

    /**
     * Atoms of the same kind and key, groups of two or more, in the order of the strings {@code kind:key} (atoms of a
     * group in their list order), compared without building those strings.
     */
    private static List<List<Atom>> groups(List<Atom> atoms, Function<Atom, String> key) {
        Comparator<Atom> order = Comparator.comparing(a -> Joined.of(a.kind(), ':', key.apply(a)), CharSequence::compare);
        List<Atom> sorted = new ArrayList<>(atoms);
        sorted.sort(order);
        List<List<Atom>> found = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= sorted.size(); i++) {
            if (i < sorted.size() && order.compare(sorted.get(start), sorted.get(i)) == 0) continue;
            if (i - start > 1) found.add(new ArrayList<>(sorted.subList(start, i)));
            start = i;
        }
        return found;
    }

    /**
     * SimHash groups: four 16-bit bands propose candidates (buckets of {@code kind + band + 4 hex digits}, in the order
     * of those strings, compared without building them); a member stays within the radius of the group's first.
     */
    private static List<List<Atom>> near(List<Atom> atoms, int radius) {
        Comparator<Integer> key = Comparator.comparing(e -> band(atoms, e), CharSequence::compare);
        Integer[] entries = IntStream.range(0, atoms.size() * BANDS).boxed().toArray(Integer[]::new);
        Arrays.sort(entries, key.thenComparing(Comparator.naturalOrder()));
        Map<Atom, List<Atom>> grouped = new LinkedHashMap<>();
        Map<Atom, Atom> leader = new HashMap<>();
        int start = 0;
        for (int i = 1; i <= entries.length; i++) {
            if (i < entries.length && key.compare(entries[start], entries[i]) == 0) continue;
            bucket(atoms, Arrays.asList(entries).subList(start, i), radius, leader, grouped);
            start = i;
        }
        return new ArrayList<>(grouped.values());
    }

    /** The bucket key of an entry ({@code atom index * BANDS + band}): kind, band digit, the band's 4 hex digits. */
    private static Joined band(List<Atom> atoms, int entry) {
        Atom a = atoms.get(entry / BANDS);
        int band = entry % BANDS;
        return new Joined(a.kind(), (char) ('0' + band), a.simhash(), band * 4, 4);
    }

    /** One bucket: each atom not yet grouped joins the group of the bucket's first when within the radius. */
    private static void bucket(List<Atom> atoms, List<Integer> entries, int radius, Map<Atom, Atom> leader,
            Map<Atom, List<Atom>> grouped) {
        Atom first = leader.computeIfAbsent(atoms.get(entries.get(0) / BANDS), k -> k);
        for (Integer entry : entries) {
            Atom a = atoms.get(entry / BANDS);
            if (a != first && !leader.containsKey(a) && PlaneHash.hamming(first.simhash(), a.simhash()) <= radius) {
                leader.put(a, first);
                grouped.computeIfAbsent(first, k -> new ArrayList<>(List.of(k))).add(a);
            }
        }
    }

    /** {@code head + separator + text.substring(from, from + length)} as a char sequence, without copying. */
    private record Joined(String head, char separator, String text, int from, int length) implements CharSequence {
        static Joined of(String head, char separator, String text) {
            return new Joined(head, separator, text, 0, text.length());
        }

        @Override
        public int length() {
            return head.length() + 1 + length;
        }

        @Override
        public char charAt(int i) {
            if (i < head.length()) return head.charAt(i);
            return i == head.length() ? separator : text.charAt(from + i - head.length() - 1);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return toString().subSequence(start, end);
        }

        @Override
        public String toString() {
            return head + separator + text.substring(from, from + length);
        }
    }
}
