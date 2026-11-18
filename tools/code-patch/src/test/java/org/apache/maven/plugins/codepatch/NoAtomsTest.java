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
import org.apache.maven.plugins.clustering.PlaneCompare.State;

/**
 * Changed files that give no atoms (a properties file here) are listed in COMPARE.tsv and in the log as NO_ATOMS, and
 * {@code failOn=NO_ATOMS} fails on them, so the refactoring gate cannot pass them silently. verify also reads bad
 * {@code failOn} values and unreadable patches apart: only the first is a {@code patch.failOn} refusal.
 */
final class NoAtomsTest {
    private static final String PATCH = VerifyTest.RENAME + "diff --git a/conf/app.properties b/conf/app.properties\n"
            + "--- a/conf/app.properties\n+++ b/conf/app.properties\n@@ -1 +1 @@\n-a=1\n+a=2\n";
    private static final Set<State> GATE = Set.of(State.CHANGED, State.LITERALS_CHANGED, State.ADDED, State.REMOVED);

    private NoAtomsTest() { }

    static void run(Path dir) throws Exception {
        Path base = Fixtures.tree(dir.resolve("tree"), Map.of("src/p/Calc.java", VerifyTest.CALC, "conf/app.properties", "a=1\n"));
        Path out = base.resolve("target/patch");
        Path patch = Fixtures.write(dir.resolve("mixed.patch"), PATCH);
        CodePatch.Verification v = CodePatch.verify(base, out, List.of(patch), GATE);
        String rows = Files.readString(v.report());
        check("COMPARE.tsv lists the changed file without atoms",
                rows.contains("NO_ATOMS\tconf/app.properties\tFILE\t\tconf/app.properties\t\n"));
        check("the Java atoms are still compared", rows.contains("REFORMATTED\tsrc/p/Calc.java\tMETHOD\tCalc.sum(int[])"));
        mojo(base, out, patch);
        failOnApart(base, out, dir);
    }

    private static void mojo(Path base, Path out, Path patch) throws Exception {
        Mojos.Run report = Mojos.run(new VerifyMojo(), base, out, List.of(patch), "failOn", "");
        check("verify logs the file without atoms: " + report.outcome(), report.outcome().equals("OK")
                && report.logged().contains("warn ") && report.logged().contains("conf/app.properties"));
        Mojos.Run gated = Mojos.run(new VerifyMojo(), base, out, List.of(patch), "failOn", "NO_ATOMS");
        check("failOn=NO_ATOMS fails on it: " + gated.outcome(), gated.outcome().startsWith("MojoFailureException")
                && gated.outcome().contains("conf/app.properties"));
        Mojos.Run gate = Mojos.run(new VerifyMojo(), base, out, List.of(patch), "failOn", "CHANGED,LITERALS_CHANGED");
        check("a gate without NO_ATOMS passes and still warns: " + gate.outcome(), gate.outcome().equals("OK")
                && gate.logged().contains("conf/app.properties"));
    }

    /** An unknown failOn state is a patch.failOn refusal; an unreadable hunk header is a patch refusal, not failOn. */
    private static void failOnApart(Path base, Path out, Path dir) throws Exception {
        Path bad = Fixtures.write(dir.resolve("bad.patch"), "--- a/conf/app.properties\n+++ b/conf/app.properties\n"
                + "@@ -99999999999 +1 @@\n-a=1\n+a=2\n");
        Mojos.Run parse = Mojos.run(new VerifyMojo(), base, out, List.of(bad), "failOn", "");
        check("unreadable hunk header is not reported as patch.failOn: " + parse.outcome(),
                parse.outcome().startsWith("MojoFailureException") && parse.outcome().contains("hunk header")
                        && !parse.outcome().contains("patch.failOn"));
        Mojos.Run state = Mojos.run(new VerifyMojo(), base, out, List.of(bad), "failOn", "BOGUS");
        check("unknown failOn state is a patch.failOn refusal: " + state.outcome(),
                state.outcome().startsWith("MojoFailureException") && state.outcome().contains("patch.failOn")
                        && state.outcome().contains("BOGUS"));
    }
}
