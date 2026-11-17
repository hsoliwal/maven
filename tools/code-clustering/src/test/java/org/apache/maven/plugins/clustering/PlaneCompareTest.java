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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.plugins.clustering.PlaneCompare.State;

/**
 * Baseline comparison: a refactoring of the fixture where one method only renames a local (REFORMATTED), one changes
 * a literal (LITERALS_CHANGED), one changes behaviour under a receipt (ACCEPTED), one moves to a new class (MOVED),
 * and one is deleted (REMOVED).
 */
final class PlaneCompareTest {
    private static final String A2 = String.join("\n", "package p;", "", "class A {",
            "    int sum(int[] values) {", "        int t = 0;", "        for (int v : values) { t += v; }",
            "        return t;", "    }",
            "    int start(int[] values) {", "        int total = 2;", "        for (int v : values) {",
            "            total += v;", "        }", "        return total;", "    }",
            "    String name(Map<String, String> m) {",
            "        return m.getOrDefault(\"name\", \"none\").strip().toLowerCase();", "    }", "}", "");
    private static final String C = String.join("\n", "package r;", "", "class C {", "    int add(int[] xs) {",
            "        int acc = 0;", "        for (int x : xs) {", "            acc += x;", "        }",
            "        return acc;", "    }", "}", "");
    private static int failures;

    private PlaneCompareTest() { }

    static int failures() {
        return failures;
    }

    static void run(Path dir, Path baselineSources, String b) throws Exception {
        Path baseline = dir.resolve("baseline/ATOM_HASHES.tsv");
        PlaneTables.writeAtoms(baseline, AtomPlanes.scan(List.of(baselineSources)));
        Path now = dir.resolve("now");
        for (String sub : List.of("p", "q", "r")) Files.createDirectories(now.resolve(sub));
        Files.writeString(now.resolve("p/A.java"), A2, StandardCharsets.UTF_8);
        Files.writeString(now.resolve("q/B.java"), b, StandardCharsets.UTF_8);
        Files.writeString(now.resolve("r/C.java"), C, StandardCharsets.UTF_8);
        Path receipts = dir.resolve("receipts.txt");
        Files.writeString(receipts, "# proven by tests\np/A.java#A.name(Map<String, String>)\n", StandardCharsets.UTF_8);
        PlaneCompare.Result result = PlaneCompare.compare(PlaneTables.readAtoms(baseline), AtomPlanes.scan(List.of(now)),
                PlaneCompare.readReceipts(receipts), Set.of(State.REMOVED), dir.resolve("out"));
        Map<State, Integer> c = result.counts();
        check("compare " + result.summary(), c.get(State.UNCHANGED) == 2 && c.get(State.REFORMATTED) == 1
                && c.get(State.LITERALS_CHANGED) == 1 && c.get(State.CHANGED) == 1 && c.get(State.ACCEPTED) == 1
                && c.get(State.ADDED) == 1 && c.get(State.MOVED) == 1 && c.get(State.REMOVED) == 1);
        String rows = Files.readString(dir.resolve("out/COMPARE.tsv"));
        check("moved row", rows.contains("MOVED\tr/C.java\tMETHOD\tC.add(int[])\tp/A.java\tA.add(int[])"));
        check("removed row fails", result.failing().size() == 1 && result.failing().get(0).startsWith("REMOVED\t\tMETHOD\t\tp/A.java\tA.sumLogged(int[])"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        System.setProperty("clustering.failOn", "CHANGED");
        int gate = CodeClustering.execute(new String[] {"compare", baseline.toString(), dir.resolve("cli").toString(), now.toString()}, out);
        System.clearProperty("clustering.failOn");
        int report = CodeClustering.execute(new String[] {"compare", baseline.toString(), dir.resolve("cli").toString(), now.toString()}, out);
        check("cli compare gate exits 3, report exits 0: " + gate + report, gate == 3 && report == 0);
        check("unknown state rejected", rejects("NOPE"));
    }

    private static boolean rejects(String state) {
        try {
            PlaneCompare.states(state);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.err.println((ok ? "PASS " : "FAIL ") + name);
    }
}
