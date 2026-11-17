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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * Matches the atoms of one source set (A) against another (B), for example a module against the library that may
 * already own its code. Every atom of A gets the strongest plane on which B has an atom of the same kind: EXACT, LOGIC,
 * STRUCTURE, NEAR (SimHash within the radius) or ABSENT. {@code JOIN.tsv} has one row per atom of A;
 * {@code COVERAGE.tsv} rolls the rows up per file of A: how many of its atoms B holds, and the file of B that holds most
 * of the exact and logic matches. A file whose atoms are all in one file of B is a candidate to be replaced by it; one
 * whose matches spread over several files of B is a candidate to be condensed. Rows are proposals: "These files may
 * be similar. Please confirm via hash or diff."
 */
public final class PlaneJoin {
    private static final String[] ORDER = {"EXACT", "LOGIC", "STRUCTURE", "NEAR", "ABSENT"};

    private PlaneJoin() { }

    /** Writes JOIN.tsv and COVERAGE.tsv; returns the count of atoms per plane, in order. */
    public static String join(List<Atom> a, List<Atom> b, Path dir, int radius) throws IOException {
        Map<String, Atom> exact = index(b, Atom::exact);
        Map<String, Atom> logic = index(b, Atom::logic);
        Map<String, Atom> structure = index(b, Atom::structure);
        StringBuilder rows = new StringBuilder("path\tkind\tsymbol\tplane\tmatch_path\tmatch_symbol\n");
        Map<String, int[]> counts = new TreeMap<>();
        Map<String, Map<String, Integer>> owners = new HashMap<>();
        int[] total = new int[ORDER.length];
        for (Atom atom : a) {
            Match match = best(atom, exact, logic, structure, b, radius);
            total[match.plane()]++;
            counts.computeIfAbsent(atom.path(), k -> new int[ORDER.length])[match.plane()]++;
            if (match.plane() <= 1) owners.computeIfAbsent(atom.path(), k -> new HashMap<>()).merge(match.atom().path(), 1, Integer::sum);
            rows.append(String.join("\t", atom.path(), atom.kind(), atom.symbol(), ORDER[match.plane()],
                    match.atom() == null ? "" : match.atom().path(), match.atom() == null ? "" : match.atom().symbol())).append('\n');
        }
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("JOIN.tsv"), rows, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("COVERAGE.tsv"), coverage(counts, owners), StandardCharsets.UTF_8);
        StringBuilder line = new StringBuilder("joined " + a.size() + " atoms:");
        for (int i = 0; i < ORDER.length; i++) line.append(' ').append(ORDER[i]).append(' ').append(total[i]);
        return line.toString();
    }

    private record Match(int plane, Atom atom) { }

    private static Match best(Atom atom, Map<String, Atom> exact, Map<String, Atom> logic, Map<String, Atom> structure,
            List<Atom> b, int radius) {
        String key = atom.kind() + ":";
        if (exact.containsKey(key + atom.exact())) return new Match(0, exact.get(key + atom.exact()));
        if (logic.containsKey(key + atom.logic())) return new Match(1, logic.get(key + atom.logic()));
        if (structure.containsKey(key + atom.structure())) return new Match(2, structure.get(key + atom.structure()));
        Atom near = null;
        int nearest = radius + 1;
        for (Atom other : b) {
            if (!other.kind().equals(atom.kind())) continue;
            int distance = PlaneHash.hamming(atom.simhash(), other.simhash());
            if (distance < nearest) {
                nearest = distance;
                near = other;
            }
        }
        return near == null ? new Match(4, null) : new Match(3, near);
    }

    private static Map<String, Atom> index(List<Atom> atoms, Function<Atom, String> key) {
        Map<String, Atom> index = new LinkedHashMap<>();
        for (Atom atom : atoms) index.putIfAbsent(atom.kind() + ":" + key.apply(atom), atom);
        return index;
    }

    private static String coverage(Map<String, int[]> counts, Map<String, Map<String, Integer>> owners) {
        StringBuilder out = new StringBuilder("path\tatoms\texact\tlogic\tstructure\tnear\tabsent\ttop_match\ttop_share\tproposal\n");
        counts.forEach((path, c) -> {
            int atoms = c[0] + c[1] + c[2] + c[3] + c[4];
            Map.Entry<String, Integer> top = owners.getOrDefault(path, Map.of()).entrySet().stream()
                    .max(Map.Entry.comparingByValue()).orElse(null);
            double share = top == null ? 0.0 : (double) top.getValue() / atoms;
            out.append(String.join("\t", path, Integer.toString(atoms), Integer.toString(c[0]), Integer.toString(c[1]),
                    Integer.toString(c[2]), Integer.toString(c[3]), Integer.toString(c[4]), top == null ? "" : top.getKey(),
                    String.format("%.2f", share), proposal(c, atoms, share))).append('\n');
        });
        return out.toString();
    }

    private static String proposal(int[] c, int atoms, double share) {
        int strong = c[0] + c[1];
        if (strong == atoms && share == 1.0) return "ALL_IN_ONE_MATCH";
        if (strong == atoms) return "ALL_SPREAD";
        if (strong >= 0.8 * atoms) return "MOSTLY_MATCHED";
        return "OWN_CODE";
    }
}
