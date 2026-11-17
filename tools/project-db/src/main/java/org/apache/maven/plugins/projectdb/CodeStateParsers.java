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
package org.apache.maven.plugins.projectdb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.synexia.maven.polyglot.ModuleStateView;

/**
 * Readers of code-state data: the code-clustering tables (atoms with their source lines, and clusters of duplicated
 * atoms) and the polyglot task state recorded by any backend of the polyglot plugin (read through its owner).
 */
final class CodeStateParsers {
    private static final List<String> SOURCE_ROOTS = List.of("src/main/java/", "src/test/java/", "");

    private CodeStateParsers() {}

    /** One clustering atom: where it is and its exact-plane hash. */
    record Atom(String key, String kind, String symbol, String exact, String file, int line, int endLine) {}

    /** ATOM_HASHES.tsv atoms, then CLUSTERS.tsv duplicate clusters with every member's file and line. */
    static List<Chunk> clustering(Path root, Source source) throws IOException {
        Map<String, Atom> atoms = atoms(root, source);
        List<Chunk> chunks = new ArrayList<>();
        for (Atom atom : atoms.values()) {
            chunks.add(Chunk.of("atom", source.module(), atom.file(), atom.line(), atom.kind() + " " + atom.symbol()
                    + " in " + atom.file() + " lines " + atom.line() + "-" + atom.endLine(),
                    Map.of("atom", atom.key(), "exact", atom.exact())));
        }
        if (source.files().size() > 1) chunks.addAll(clusters(source, atoms));
        return chunks;
    }

    static Map<String, Atom> atoms(Path root, Source source) throws IOException {
        List<String> rows = Text.lines(source.files().get(0));
        Map<String, Atom> atoms = new java.util.LinkedHashMap<>();
        for (String row : rows.subList(Math.min(1, rows.size()), rows.size())) {
            String[] c = row.split("\t", -1);
            if (c.length < 8) continue;
            int line = c.length >= 10 ? Integer.parseInt(c[8]) : 0;
            int end = c.length >= 10 ? Integer.parseInt(c[9]) : 0;
            String key = c[0] + "#" + c[2];
            atoms.putIfAbsent(key, new Atom(key, c[1], c[2], c[4], sourceFile(root, source.module(), c[0]), line, end));
        }
        return atoms;
    }

    private static List<Chunk> clusters(Source source, Map<String, Atom> atoms) throws IOException {
        List<String> rows = Text.lines(source.files().get(1));
        Map<String, List<String[]>> groups = new java.util.LinkedHashMap<>();
        for (int i = 1; i < rows.size(); i++) {
            String[] c = rows.get(i).split("\t", -1);
            if (c.length >= 6) groups.computeIfAbsent(c[5], ignored -> new ArrayList<>()).add(c);
        }
        List<Chunk> chunks = new ArrayList<>();
        for (List<String[]> group : groups.values()) {
            List<Atom> members = java.util.Arrays.stream(group.get(0)[5].split("\\|")).map(atoms::get)
                    .filter(java.util.Objects::nonNull).toList();
            if (!members.isEmpty()) chunks.add(cluster(source, group, members));
        }
        return chunks;
    }

    /** One chunk per member set: the same atoms clustered on several planes are one duplicate, planes listed. */
    private static Chunk cluster(Source source, List<String[]> rows, List<Atom> members) {
        String where = members.stream().map(a -> a.symbol() + " at " + a.file() + ":" + a.line())
                .collect(Collectors.joining("; "));
        String planes = rows.stream().map(row -> row[0]).collect(Collectors.joining(", "));
        String[] c = rows.get(0);
        String text = "duplicated code: " + c[3] + " " + c[2] + " atoms share the " + planes + " hash"
                + (rows.size() > 1 ? "es" : "") + " (" + c[4] + " tokens): " + where;
        Atom first = members.get(0);
        return Chunk.of("duplicate", source.module(), first.file(), first.line(), text,
                Map.of("plane", c[0], "planes", planes, "cluster", c[0] + "-" + c[1], "members", c[3]));
    }

    /** The project-relative source file of an atom path given relative to a source root. */
    private static String sourceFile(Path root, String module, String atomPath) {
        Path base = root.resolve(".".equals(module) ? "" : module);
        for (String sourceRoot : SOURCE_ROOTS) {
            Path candidate = base.resolve(sourceRoot + atomPath);
            if (Files.isRegularFile(candidate)) return Sources.relative(root, candidate);
        }
        return (".".equals(module) ? "" : module + "/") + atomPath;
    }

    /** Polyglot task receipts of every backend in the module, read through the polyglot plugin. */
    static List<Chunk> polyglot(Path root, Source source) throws IOException {
        Path module = root.resolve(".".equals(source.module()) ? "" : source.module());
        Map<String, Integer> seen = new HashMap<>();
        List<Chunk> chunks = new ArrayList<>();
        for (ModuleStateView.TaskState state : ModuleStateView.read(module)) {
            String file = Sources.relative(root, module.resolve(state.source()));
            int line = seen.merge(file, 1, Integer::sum);
            chunks.add(Chunk.of("polyglot", source.module(), file, line, "polyglot task " + state.task()
                    + " recorded by the " + state.backend() + " backend in module " + source.module() + ": input "
                    + state.input() + " output " + state.output(), Map.of("task", state.task(),
                    "backend", state.backend(), "input", state.input(), "output", state.output())));
        }
        return chunks;
    }
}
