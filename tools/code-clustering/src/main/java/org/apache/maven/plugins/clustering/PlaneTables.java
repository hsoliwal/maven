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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    /** Writes CLUSTERS.tsv and PLANE_SUMMARY.tsv; returns the summary line. */
    public static String writeClusters(Path dir, List<Atom> atoms, int floor, int radius) throws IOException {
        List<Atom> sized = atoms.stream().filter(a -> a.tokens() >= floor).collect(Collectors.toList());
        Map<String, List<List<Atom>>> planes = new LinkedHashMap<>();
        planes.put("EXACT", groups(sized, Atom::exact));
        planes.put("LOGIC", groups(sized, Atom::logic));
        planes.put("STRUCTURE", groups(sized, Atom::structure));
        planes.put("SIMHASH", near(sized, radius));
        StringBuilder clusters = new StringBuilder("plane\tcluster\tkind\tmembers\ttokens\tatoms\n");
        StringBuilder summary = new StringBuilder("plane\tclusters\tclustered_atoms\n");
        StringBuilder line = new StringBuilder("atoms " + atoms.size() + " (" + sized.size() + " with " + floor + "+ tokens)");
        planes.forEach((plane, found) -> {
            int id = 0;
            for (List<Atom> group : found) clusters.append(row(plane, ++id, group));
            int members = found.stream().mapToInt(List::size).sum();
            summary.append(plane).append(TAB).append(found.size()).append(TAB).append(members).append('\n');
            line.append(" | ").append(plane).append(' ').append(found.size()).append('/').append(members);
        });
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("CLUSTERS.tsv"), clusters, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("PLANE_SUMMARY.tsv"), summary, StandardCharsets.UTF_8);
        return line.toString();
    }

    private static String row(String plane, int id, List<Atom> group) {
        String members = group.stream().map(a -> a.path() + "#" + a.symbol()).collect(Collectors.joining("|"));
        return plane + TAB + id + TAB + group.get(0).kind() + TAB + group.size() + TAB + group.get(0).tokens() + TAB
                + members + '\n';
    }

    private static List<List<Atom>> groups(List<Atom> atoms, Function<Atom, String> key) {
        Map<String, List<Atom>> byKey = new TreeMap<>();
        for (Atom a : atoms) byKey.computeIfAbsent(a.kind() + ":" + key.apply(a), k -> new ArrayList<>()).add(a);
        return byKey.values().stream().filter(g -> g.size() > 1).collect(Collectors.toList());
    }

    /** SimHash groups: four 16-bit bands propose candidates; a member stays within the radius of the group's first. */
    private static List<List<Atom>> near(List<Atom> atoms, int radius) {
        Map<String, List<Atom>> bands = new TreeMap<>();
        for (Atom a : atoms) {
            for (int band = 0; band < 4; band++)
                bands.computeIfAbsent(a.kind() + band + a.simhash().substring(band * 4, band * 4 + 4), k -> new ArrayList<>()).add(a);
        }
        Map<Atom, List<Atom>> grouped = new LinkedHashMap<>();
        Map<Atom, Atom> leader = new LinkedHashMap<>();
        for (List<Atom> bucket : bands.values()) {
            for (Atom a : bucket) {
                Atom first = leader.computeIfAbsent(bucket.get(0), k -> k);
                if (a != first && !leader.containsKey(a) && PlaneHash.hamming(first.simhash(), a.simhash()) <= radius) {
                    leader.put(a, first);
                    grouped.computeIfAbsent(first, k -> new ArrayList<>(List.of(k))).add(a);
                }
            }
        }
        return new ArrayList<>(grouped.values());
    }
}
