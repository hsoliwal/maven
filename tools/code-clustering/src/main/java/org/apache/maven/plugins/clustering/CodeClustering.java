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
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * Command line of the code clustering facility, usable without Maven:
 * <pre>
 * CodeClustering cluster &lt;out-dir&gt; &lt;source-root&gt;...   atoms, planes and clusters of the sources
 * CodeClustering join &lt;A/ATOM_HASHES.tsv&gt; &lt;B/ATOM_HASHES.tsv&gt; &lt;out-dir&gt;   match A against B
 * CodeClustering compare &lt;baseline.tsv&gt; &lt;out-dir&gt; &lt;source-root&gt;...   state of each atom against a baseline
 * </pre>
 * {@code compare} writes COMPARE.tsv; it reads {@code clustering.failOn} (comma-separated states) and
 * {@code clustering.receipts} (a file of {@code path#symbol} lines) and exits 3 when an atom is in a failing state.
 * {@code cluster} writes ATOM_HASHES.tsv, CLUSTERS.tsv, PLANE_SUMMARY.tsv, FILE_PAIRS.tsv and FILE_CLUSTERS.tsv;
 * {@code join} writes JOIN.tsv and COVERAGE.tsv. Options (system properties): {@code clustering.minTokens} (default
 * 12), {@code clustering.radius} (SimHash Hamming radius, default 3), {@code clustering.fileThreshold} (Jaccard
 * similarity of two files' method logic sets, default 0.5). Exit status: 0 written, 2 usage, 1 failure.
 */
public final class CodeClustering {
    static final int MIN_TOKENS = Integer.getInteger("clustering.minTokens", 12);
    static final int RADIUS = Integer.getInteger("clustering.radius", 3);
    static final double FILE_THRESHOLD = Double.parseDouble(System.getProperty("clustering.fileThreshold", "0.5"));

    private CodeClustering() { }

    public static void main(String[] args) {
        System.exit(execute(args, System.out));
    }

    public static int execute(String[] args, PrintStream out) {
        try {
            if (args.length >= 3 && args[0].equals("cluster")) {
                out.println(cluster(Path.of(args[1]), paths(args, 2), MIN_TOKENS, RADIUS));
                return 0;
            }
            if (args.length == 4 && args[0].equals("join")) {
                List<Atom> a = PlaneTables.readAtoms(Path.of(args[1]));
                List<Atom> b = PlaneTables.readAtoms(Path.of(args[2]));
                out.println(PlaneJoin.join(a, b, Path.of(args[3]), RADIUS));
                return 0;
            }
            if (args.length >= 4 && args[0].equals("compare")) {
                String receipts = System.getProperty("clustering.receipts");
                PlaneCompare.Result result = PlaneCompare.compare(PlaneTables.readAtoms(Path.of(args[1])),
                        AtomPlanes.scan(paths(args, 3)), PlaneCompare.readReceipts(receipts == null ? null : Path.of(receipts)),
                        PlaneCompare.states(System.getProperty("clustering.failOn", "")), Path.of(args[2]));
                out.println(result.summary());
                result.failing().forEach(out::println);
                return result.failing().isEmpty() ? 0 : 3;
            }
            out.println("usage: CodeClustering cluster <out-dir> <source-root>... | join <A.tsv> <B.tsv> <out-dir>"
                    + " | compare <baseline.tsv> <out-dir> <source-root>...");
            return 2;
        } catch (IOException | RuntimeException e) {
            out.println("code clustering failed: " + e);
            return 1;
        }
    }

    /** Scans the roots, writes the atom table, the atom clusters and the file families; returns the summary line. */
    public static String cluster(Path out, List<Path> roots, int minTokens, int radius) throws IOException {
        return cluster(out, roots, minTokens, radius, FILE_THRESHOLD);
    }

    /** As {@link #cluster(Path, List, int, int)} with the Jaccard threshold of a file family. */
    public static String cluster(Path out, List<Path> roots, int minTokens, int radius, double fileThreshold)
            throws IOException {
        List<Atom> atoms = AtomPlanes.scan(roots);
        PlaneTables.writeAtoms(out.resolve("ATOM_HASHES.tsv"), atoms);
        return PlaneTables.writeClusters(out, atoms, minTokens, radius) + " | "
                + FileClusters.write(out, atoms, minTokens, fileThreshold);
    }

    private static List<Path> paths(String[] args, int from) {
        List<Path> paths = new ArrayList<>();
        for (int i = from; i < args.length; i++) paths.add(Path.of(args[i]));
        return paths;
    }
}
