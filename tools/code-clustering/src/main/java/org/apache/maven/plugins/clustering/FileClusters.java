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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * Clusters files rather than single atoms: each file is the set of logic hashes of its methods; two files are related
 * when the Jaccard similarity of their sets reaches the threshold; related files are joined transitively (union-find)
 * into families. A family is a set of files that repeat most of each other's logic under other names, the usual
 * candidates for one shared owner. An inverted index from logic hash to files finds the pairs without comparing every
 * file with every other; a hash held by more than {@link #COMMON} files (accessors, trivial overrides) proposes no
 * pairs. Writes FILE_PAIRS.tsv and FILE_CLUSTERS.tsv.
 */
public final class FileClusters {
    static final int COMMON = 64;

    private FileClusters() { }

    /** Writes the pair and family tables; returns the summary fragment. */
    public static String write(Path dir, List<Atom> atoms, int minTokens, double threshold) throws IOException {
        Map<String, Set<String>> sets = new TreeMap<>();
        for (Atom a : atoms) {
            if (a.kind().equals("METHOD") && a.tokens() >= minTokens) sets.computeIfAbsent(a.path(), k -> new TreeSet<>()).add(a.logic());
        }
        Map<String, List<String>> index = new HashMap<>();
        sets.forEach((path, logic) -> logic.forEach(h -> index.computeIfAbsent(h, k -> new ArrayList<>()).add(path)));
        Map<String, Integer> shared = new TreeMap<>();
        for (List<String> paths : index.values()) {
            if (paths.size() < 2 || paths.size() > COMMON) continue;
            for (int i = 0; i < paths.size(); i++) {
                for (int j = i + 1; j < paths.size(); j++) shared.merge(paths.get(i) + '\t' + paths.get(j), 1, Integer::sum);
            }
        }
        StringBuilder pairs = new StringBuilder("left\tright\tshared\tleft_methods\tright_methods\tjaccard\n");
        Map<String, String> parent = new HashMap<>();
        shared.forEach((key, count) -> {
            String[] lr = key.split("\t");
            int left = sets.get(lr[0]).size();
            int right = sets.get(lr[1]).size();
            double jaccard = (double) count / (left + right - count);
            if (jaccard < threshold) return;
            pairs.append(String.join("\t", lr[0], lr[1], Integer.toString(count), Integer.toString(left),
                    Integer.toString(right), String.format("%.3f", jaccard))).append('\n');
            parent.put(find(parent, lr[0]), find(parent, lr[1]));
        });
        Map<String, Set<String>> families = new TreeMap<>();
        for (String path : new ArrayList<>(parent.keySet())) families.computeIfAbsent(find(parent, path), k -> new TreeSet<>()).add(path);
        StringBuilder out = new StringBuilder("family\tfiles\tmembers\n");
        int id = 0;
        int members = 0;
        for (Set<String> family : families.values()) {
            out.append(++id).append('\t').append(family.size()).append('\t').append(String.join("|", family)).append('\n');
            members += family.size();
        }
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("FILE_PAIRS.tsv"), pairs, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("FILE_CLUSTERS.tsv"), out, StandardCharsets.UTF_8);
        return "FILES " + id + "/" + members;
    }

    private static String find(Map<String, String> parent, String path) {
        String root = path;
        while (parent.containsKey(root) && !parent.get(root).equals(root)) root = parent.get(root);
        parent.putIfAbsent(root, root);
        String step = path;
        while (!step.equals(root)) {
            String next = parent.get(step);
            parent.put(step, root);
            step = next;
        }
        return root;
    }
}
