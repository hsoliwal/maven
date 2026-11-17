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
package org.apache.maven.plugins.codepatch;

import static org.apache.maven.plugins.codepatch.Checks.check;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.plugins.clustering.PlaneCompare;
import org.apache.maven.plugins.clustering.PlaneCompare.State;

/**
 * verify on a Java file: a patch that only renames a local is REFORMATTED and passes the refactoring gate; a patch
 * that adds a statement is CHANGED, fails a gate on CHANGED, and only reports when failOn is empty; a method rename
 * makes the method MOVED and its TYPE atom LITERALS_CHANGED (as the README says), so the gate fails it. No source
 * changes.
 */
final class VerifyTest {
    static final String CALC = String.join("\n", "package p;", "", "class Calc {", "    int sum(int[] values) {",
            "        int total = 0;", "        for (int v : values) {", "            total += v;", "        }",
            "        return total;", "    }", "}", "");
    static final String RENAME = String.join("\n", "diff --git a/src/p/Calc.java b/src/p/Calc.java",
            "--- a/src/p/Calc.java", "+++ b/src/p/Calc.java", "@@ -2,9 +2,9 @@", " ", " class Calc {",
            "     int sum(int[] values) {", "-        int total = 0;", "+        int acc = 0;", "         for (int v : values) {",
            "-            total += v;", "+            acc += v;", "         }", "-        return total;", "+        return acc;", "     }", "");
    private static final String BEHAVIOUR = String.join("\n", "diff --git a/src/p/Calc.java b/src/p/Calc.java",
            "--- a/src/p/Calc.java", "+++ b/src/p/Calc.java", "@@ -6,4 +6,5 @@", "         for (int v : values) {",
            "             total += v;", "         }", "+        total *= 2;", "         return total;", "");
    private static final String MEMBER = String.join("\n", "diff --git a/src/p/Calc.java b/src/p/Calc.java",
            "--- a/src/p/Calc.java", "+++ b/src/p/Calc.java", "@@ -4 +4 @@", "-    int sum(int[] values) {",
            "+    int add(int[] values) {", "");
    private static final Set<State> GATE = Set.of(State.CHANGED, State.LITERALS_CHANGED, State.ADDED, State.REMOVED);

    private VerifyTest() { }

    static void run(Path dir) throws Exception {
        Path base = Fixtures.tree(dir.resolve("tree"), Map.of("src/p/Calc.java", CALC));
        Path out = base.resolve("target/patch");
        Map<String, String> original = Fixtures.snapshot(base);
        rename(base, out, Fixtures.write(dir.resolve("rename.patch"), RENAME));
        behaviour(base, out, Fixtures.write(dir.resolve("behaviour.patch"), BEHAVIOUR));
        member(base, out, Fixtures.write(dir.resolve("member.patch"), MEMBER));
        check("verify changes no source file", Fixtures.snapshot(base).equals(original));
    }

    private static void rename(Path base, Path out, Path patch) throws Exception {
        CodePatch.Verification kept = CodePatch.verify(base, out, List.of(patch), GATE);
        String rows = Files.readString(kept.report());
        check("rename-only patch is REFORMATTED: " + kept.result().summary(), kept.result().failing().isEmpty()
                && kept.result().counts().getOrDefault(State.REFORMATTED, 0) == 2
                && rows.contains("REFORMATTED\tsrc/p/Calc.java\tMETHOD\tCalc.sum(int[])\tsrc/p/Calc.java\tCalc.sum(int[])"));
    }

    private static void behaviour(Path base, Path out, Path patch) throws Exception {
        CodePatch.Verification gated = CodePatch.verify(base, out, List.of(patch), Set.of(State.CHANGED));
        check("behaviour change is CHANGED and fails failOn=CHANGED: " + gated.result().summary(),
                gated.result().failing().stream().anyMatch(r -> r.startsWith("CHANGED\tsrc/p/Calc.java\tMETHOD\tCalc.sum(int[])")));
        PlaneCompare.Result report = CodePatch.verify(base, out, List.of(patch), PlaneCompare.states("")).result();
        check("empty failOn only reports", report.failing().isEmpty() && report.counts().getOrDefault(State.CHANGED, 0) > 0);
    }

    private static void member(Path base, Path out, Path patch) throws Exception {
        CodePatch.Verification v = CodePatch.verify(base, out, List.of(patch), GATE);
        String rows = Files.readString(v.report());
        check("method rename: METHOD MOVED, TYPE LITERALS_CHANGED, gate fails: " + v.result().summary(),
                rows.contains("MOVED\tsrc/p/Calc.java\tMETHOD\tCalc.add(int[])\tsrc/p/Calc.java\tCalc.sum(int[])")
                        && rows.contains("LITERALS_CHANGED\tsrc/p/Calc.java\tTYPE\tCalc") && !v.result().failing().isEmpty());
    }
}
