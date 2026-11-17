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
import java.util.Optional;

/**
 * A rollback that stops after it restored some files (a later file cannot be replaced) leaves STATUS ROLLING_BACK,
 * and a second rollback finishes the job: it accepts the files that already have their before content.
 */
final class RollbackResumeTest {
    private static final String PATCH = "--- a/a/first.txt\n+++ b/a/first.txt\n@@ -1 +1 @@\n-1\n+one\n"
            + "--- a/b/second.txt\n+++ b/b/second.txt\n@@ -1 +1 @@\n-2\n+two\n";

    private RollbackResumeTest() { }

    static void run(Path dir) throws Exception {
        Path base = Fixtures.tree(dir.resolve("tree"), Map.of("a/first.txt", "1\n", "b/second.txt", "2\n"));
        Path out = base.resolve("target/patch");
        Path patch = Fixtures.write(dir.resolve("resume.patch"), PATCH);
        Map<String, String> original = Fixtures.snapshot(base);
        String id = applied(base, out, patch);
        Optional<Blocks.Undo> block = Blocks.noReplace(base.resolve("a/first.txt"));
        if (block.isPresent()) {
            interrupt(base, out, id, block.get());
            resume(base, out, id, original);
        } else {
            unblocked(base, out, id);
        }
        diedBeforeLastStatus(base, out, patch, id, original);
    }

    private static void unblocked(Path base, Path out, String id) throws Exception {
        Links.skip("interrupted rollback resumes", "cannot block a replace here");
        CodePatch.rollback(base, out, id);
    }

    /** Every file restored but STATUS still ROLLING_BACK (the process died): apply refuses, rollback finishes. */
    private static void diedBeforeLastStatus(Path base, Path out, Path patch, String id, Map<String, String> original)
            throws Exception {
        Files.writeString(CodePatch.receipt(out, id).resolve("STATUS"), "ROLLING_BACK\n");
        Checks.refuses("apply refused while the receipt is ROLLING_BACK", () -> CodePatch.apply(base, out, List.of(patch), id),
                "ROLLING_BACK receipt", "roll it back first");
        check("rollback of a fully restored ROLLING_BACK receipt completes: "
                + Checks.outcome(() -> CodePatch.rollback(base, out, id)), Fixtures.snapshot(base).equals(original)
                && Files.readString(CodePatch.receipt(out, id).resolve("STATUS")).strip().equals("ROLLED_BACK"));
    }

    private static String applied(Path base, Path out, Path patch) throws Exception {
        String id = CodePatch.plan(base, out, List.of(patch)).id();
        CodePatch.apply(base, out, List.of(patch), id);
        return id;
    }

    private static void interrupt(Path base, Path out, String id, Blocks.Undo block) throws Exception {
        String outcome;
        try (block) {
            outcome = Checks.outcome(() -> CodePatch.rollback(base, out, id));
        }
        String status = Files.readString(CodePatch.receipt(out, id).resolve("STATUS")).strip();
        check("rollback stopped at a/first.txt: " + outcome, !outcome.equals("done"));
        check("interrupted rollback leaves STATUS ROLLING_BACK (has " + status + ")", status.equals("ROLLING_BACK"));
        check("interrupted rollback restored b/second.txt first", Files.readString(base.resolve("b/second.txt")).equals("2\n")
                && Files.readString(base.resolve("a/first.txt")).equals("one\n"));
    }

    private static void resume(Path base, Path out, String id, Map<String, String> original) throws Exception {
        String outcome = Checks.outcome(() -> CodePatch.rollback(base, out, id));
        check("resumed rollback completes: " + outcome, outcome.equals("done"));
        check("resumed rollback restores every file", Fixtures.snapshot(base).equals(original));
        check("resumed rollback ends ROLLED_BACK",
                Files.readString(CodePatch.receipt(out, id).resolve("STATUS")).strip().equals("ROLLED_BACK"));
    }
}
