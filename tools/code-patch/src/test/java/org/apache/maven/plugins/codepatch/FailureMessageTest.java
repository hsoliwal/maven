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
import static org.apache.maven.plugins.codepatch.Checks.refuses;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Failure messages keep the exception type, because a file system exception's message holds only paths: an execution
 * error of a goal, a promotion that fails during apply, and an undo that fails after it.
 */
final class FailureMessageTest {
    private static final String TWO = "--- a/a/x.txt\n+++ b/a/x.txt\n@@ -1 +1 @@\n-x\n+X\n"
            + "--- a/b/y.txt\n+++ b/b/y.txt\n@@ -1 +1 @@\n-y\n+Y\n";
    private static final String DELETE_THEN_MODIFY = "diff --git a/d/a.txt b/d/a.txt\ndeleted file mode 100644\n"
            + "--- a/d/a.txt\n+++ /dev/null\n@@ -1 +0,0 @@\n-a\n"
            + "diff --git a/d/b.txt b/d/b.txt\n--- a/d/b.txt\n+++ b/d/b.txt\n@@ -1 +1 @@\n-b\n+B\n";

    private FailureMessageTest() { }

    static void run(Path dir) throws Exception {
        goal(dir.resolve("goal"));
        promotion(dir.resolve("promotion"));
        undo(dir.resolve("undo"));
    }

    private static void goal(Path dir) throws Exception {
        Path base = Fixtures.tree(dir, Map.of("x.txt", "x\n"));
        Path out = Fixtures.write(base.resolve("out-is-a-file"), "not a directory\n");
        Path patch = Fixtures.write(dir.resolve("p.diff"), "--- a/x.txt\n+++ b/x.txt\n@@ -1 +1 @@\n-x\n+X\n");
        Mojos.Run run = Mojos.run(new PlanMojo(), base, out, List.of(patch), null, null);
        check("goal execution error names the exception type: " + run.outcome(),
                run.outcome().startsWith("MojoExecutionException") && run.outcome().contains("FileAlreadyExistsException"));
    }

    /** The second file cannot be written: the message says AccessDeniedException, and the first file is restored. */
    private static void promotion(Path dir) throws Exception {
        Path base = Fixtures.tree(dir, Map.of("a/x.txt", "x\n", "b/y.txt", "y\n"));
        Path out = base.resolve("target/patch");
        Path patch = Fixtures.write(dir.resolve("two.diff"), TWO);
        Map<String, String> original = Fixtures.snapshot(base);
        String id = CodePatch.plan(base, out, List.of(patch)).id();
        String name = "promotion failure names the exception type";
        if (!blocked(name, Blocks.noNewFiles(base.resolve("b")), () -> refuses(name,
                () -> CodePatch.apply(base, out, List.of(patch), id),
                "stopped: java.nio.file.AccessDeniedException", "the 1 files promoted so far were restored"))) return;
        check("failed promotion left every file as it was", Fixtures.snapshot(base).equals(original));
    }

    /** d/a.txt is deleted, d/b.txt cannot be written, and restoring d/a.txt cannot create its file either. */
    private static void undo(Path dir) throws Exception {
        Path base = Fixtures.tree(dir, Map.of("d/a.txt", "a\n", "d/b.txt", "b\n"));
        Path out = base.resolve("target/patch");
        Path patch = Fixtures.write(dir.resolve("undo.diff"), DELETE_THEN_MODIFY);
        Map<String, String> original = Fixtures.snapshot(base);
        String id = CodePatch.plan(base, out, List.of(patch)).id();
        String name = "failed undo names the exception type";
        Optional<Blocks.Undo> block = Scratch.windows() ? Blocks.noNewFiles(base.resolve("d")) : Optional.empty();
        if (!blocked(name, block, () -> refuses(name, () -> CodePatch.apply(base, out, List.of(patch), id),
                "restoring the promoted files failed (java.nio.file.AccessDeniedException", "PENDING"))) return;
        CodePatch.rollback(base, out, id);
        check("rollback after a failed undo restores every file", Fixtures.snapshot(base).equals(original));
    }

    /** Runs the checks while the block holds; false (and a SKIP line) when the file system cannot block. */
    private static boolean blocked(String name, Optional<Blocks.Undo> block, Checks.Action checks) throws Exception {
        if (block.isEmpty()) {
            Links.skip(name, "cannot deny new files here (needs NTFS, or POSIX without root; the undo case needs NTFS)");
            return false;
        }
        Blocks.Undo undo = block.get();
        try (undo) {
            checks.run();
        }
        return true;
    }
}
