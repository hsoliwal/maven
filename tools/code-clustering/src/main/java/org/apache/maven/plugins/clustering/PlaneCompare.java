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
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * Compares the atoms of the current sources with a baseline atom table, for example one written before a refactoring.
 * An atom is identified by path, kind and symbol. Its state says how far it moved from the baseline:
 * <ul>
 *   <li>UNCHANGED: same exact hash;</li>
 *   <li>REFORMATTED: same logic hash. Only layout, comments or the names it declares changed;</li>
 *   <li>LITERALS_CHANGED: same structure hash, different logic. Literals or outside names changed;</li>
 *   <li>CHANGED: none of the three hashes is the same;</li>
 *   <li>MOVED: no baseline atom with this identity, but a removed baseline atom has the same logic hash. The code
 *       moved to another file or symbol;</li>
 *   <li>ADDED and REMOVED: new atoms, and baseline atoms that no longer exist and did not move;</li>
 *   <li>ACCEPTED: the atom changed, but the receipts file lists its {@code path#symbol}. Its new behaviour was
 *       proven another way, for example by tests.</li>
 * </ul>
 * Writes COMPARE.tsv and returns a summary. The caller decides which states fail.
 */
public final class PlaneCompare {
    /** How far an atom moved from the baseline. */
    public enum State { UNCHANGED, REFORMATTED, LITERALS_CHANGED, CHANGED, MOVED, ADDED, REMOVED, ACCEPTED }

    private PlaneCompare() { }

    /** The outcome: count per state and the rows that are in one of the failing states. */
    public record Result(Map<State, Integer> counts, List<String> failing) {
        public String summary() {
            return "compared: " + counts.entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                    .collect(Collectors.joining(" "));
        }
    }

    public static Result compare(List<Atom> baseline, List<Atom> current, Set<String> receipts, Set<State> failOn,
            Path out) throws IOException {
        Map<String, Atom> before = new LinkedHashMap<>();
        baseline.forEach(a -> before.put(id(a), a));
        Map<String, Atom> now = new LinkedHashMap<>();
        current.forEach(a -> now.put(id(a), a));
        Map<String, Atom> removedByLogic = new LinkedHashMap<>();
        before.forEach((key, a) -> {
            if (!now.containsKey(key)) removedByLogic.putIfAbsent(a.kind() + ":" + a.logic(), a);
        });
        Set<String> movedFrom = new HashSet<>();
        Map<State, Integer> counts = new EnumMap<>(State.class);
        List<String> failing = new java.util.ArrayList<>();
        StringBuilder rows = new StringBuilder("state\tpath\tkind\tsymbol\tbaseline_path\tbaseline_symbol\n");
        for (Atom a : current) {
            Atom old = before.get(id(a));
            State state;
            if (old == null) {
                old = removedByLogic.remove(a.kind() + ":" + a.logic());
                state = old == null ? State.ADDED : State.MOVED;
                if (old != null) movedFrom.add(id(old));
            } else {
                state = a.exact().equals(old.exact()) ? State.UNCHANGED
                        : a.logic().equals(old.logic()) ? State.REFORMATTED
                        : a.structure().equals(old.structure()) ? State.LITERALS_CHANGED : State.CHANGED;
            }
            if ((state == State.CHANGED || state == State.LITERALS_CHANGED || state == State.ADDED)
                    && receipts.contains(a.path() + "#" + a.symbol())) state = State.ACCEPTED;
            row(rows, counts, failing, failOn, state, a, old);
        }
        for (Map.Entry<String, Atom> e : before.entrySet()) {
            if (!now.containsKey(e.getKey()) && !movedFrom.contains(e.getKey())) {
                Atom gone = e.getValue();
                State state = receipts.contains(gone.path() + "#" + gone.symbol()) ? State.ACCEPTED : State.REMOVED;
                row(rows, counts, failing, failOn, state, null, gone);
            }
        }
        Files.createDirectories(out);
        Files.writeString(out.resolve("COMPARE.tsv"), rows, StandardCharsets.UTF_8);
        return new Result(counts, failing);
    }

    private static void row(StringBuilder rows, Map<State, Integer> counts, List<String> failing, Set<State> failOn,
            State state, Atom a, Atom old) {
        String line = String.join("\t", state.name(), a == null ? "" : a.path(), a == null ? old.kind() : a.kind(),
                a == null ? "" : a.symbol(), old == null ? "" : old.path(), old == null ? "" : old.symbol());
        rows.append(line).append('\n');
        counts.merge(state, 1, Integer::sum);
        if (failOn.contains(state)) failing.add(line);
    }

    private static String id(Atom a) {
        return a.path() + "\t" + a.kind() + "\t" + a.symbol();
    }

    /** Receipts: one {@code path#symbol} per line; blank lines and lines starting with {@code #} are ignored. */
    public static Set<String> readReceipts(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) return Set.of();
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream().map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#")).collect(Collectors.toSet());
    }

    /** Parses a comma-separated list of state names; blank means none. */
    public static Set<State> states(String list) {
        Set<State> states = java.util.EnumSet.noneOf(State.class);
        if (list == null) return states;
        for (String name : list.split(",")) {
            if (!name.isBlank()) states.add(State.valueOf(name.strip()));
        }
        return states;
    }
}
