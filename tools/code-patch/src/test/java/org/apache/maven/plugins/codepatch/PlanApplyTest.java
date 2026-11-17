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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Plan, preview, apply and rollback on the fixture tree: rows of every operation, a stable plan id that changes with
 * the patch, no source change before apply, refusals (no or malformed approval, stale approval, held lock, changed
 * file at rollback), byte-identical results after apply and after rollback (CRLF, no final newline, UTF-8), POSIX
 * permissions kept by apply and rollback, the modification time restored by rollback, re-apply after rollback, and
 * rollback of an interrupted apply.
 */
final class PlanApplyTest {
    private static final long OLD_TIME = 1_000_000_000_000L;

    private PlanApplyTest() { }

    private static boolean posix(Path file) {
        return Files.getFileAttributeView(file, PosixFileAttributeView.class) != null;
    }

    /** Checks the attributes of the CRLF fixture file: POSIX permissions where there are any, and optionally its time. */
    private static void attributes(String when, Path file, boolean time) throws Exception {
        if (posix(file)) {
            check("permissions kept " + when, PosixFilePermissions.toString(Files.getPosixFilePermissions(file)).equals("rwxr-x---"));
        } else {
            System.err.println("SKIP permissions kept " + when + " (no POSIX file attributes here)");
        }
        if (time) check("modification time restored " + when, Files.getLastModifiedTime(file).toMillis() == OLD_TIME);
    }

    static void run(Path dir) throws Exception {
        Path base = Fixtures.tree(dir.resolve("tree"), Fixtures.TREE);
        Path out = base.resolve("target/patch");
        Path patch = Fixtures.write(dir.resolve("series/0001-change.patch"), Fixtures.SERIES);
        oldAndRestricted(base.resolve("src/crlf.txt"));
        Map<String, String> original = Fixtures.snapshot(base);
        Plan plan = CodePatch.plan(base, out, List.of(patch));
        rows(plan, out);
        ids(base, out, patch, plan.id(), dir);
        check("plan changes no source file", Fixtures.snapshot(base).equals(original));
        preview(base, out, patch, original);
        refusals(base, out, patch, plan.id());
        applyAndRollback(base, out, patch, plan.id(), original);
    }

    private static void preview(Path base, Path out, Path patch, Map<String, String> original) throws Exception {
        Path staged = CodePatch.preview(base, out, List.of(patch));
        check("preview stages the after-images byte for byte", Fixtures.snapshot(staged).equals(Fixtures.digests(Fixtures.AFTER)));
        check("preview changes no source file", Fixtures.snapshot(base).equals(original));
    }

    /** An old modification time and, where there are POSIX permissions, rwxr-x---: both must survive. */
    private static void oldAndRestricted(Path crlf) throws Exception {
        Files.setLastModifiedTime(crlf, FileTime.fromMillis(OLD_TIME));
        if (posix(crlf)) Files.setPosixFilePermissions(crlf, PosixFilePermissions.fromString("rwxr-x---"));
    }

    private static void ids(Path base, Path out, Path patch, String id, Path dir) throws Exception {
        check("plan id stable across runs", CodePatch.plan(base, out, List.of(patch)).id().equals(id));
        check("plan id same from the patch directory", CodePatch.plan(base, out, List.of(patch.getParent())).id().equals(id));
        Path other = Fixtures.write(dir.resolve("other/0001-change.patch"), Fixtures.SERIES.replace("+TWO", "+Two"));
        check("plan id changes with the patch", !CodePatch.plan(base, out, List.of(other)).id().equals(id));
    }

    private static void rows(Plan plan, Path out) throws Exception {
        String rows = plan.rows().stream().map(r -> r.path() + " " + r.operation() + " " + r.hunks() + " " + r.fromPath())
                .collect(Collectors.joining("; "));
        check("plan rows " + rows, rows.equals("src/crlf.txt MODIFY 1 -; src/gone.txt DELETE 1 -; src/kept.txt RENAME 0 src/keep.txt; "
                + "src/moved.txt RENAME 1 src/old.txt; src/new.txt ADD 1 -; src/nonl.txt MODIFY 1 -"));
        PlanRow added = plan.rows().get(4);
        check("ADD row hashes", added.beforeSha256().equals("-")
                && added.afterSha256().equals(Digests.sha256("fresh\nlast".getBytes(StandardCharsets.UTF_8))));
        check("PLAN.tsv and PLAN_ID written", Files.readString(out.resolve("PLAN.tsv")).equals(plan.tsv())
                && Files.readString(out.resolve("PLAN_ID")).strip().equals(plan.id()) && plan.id().matches("[0-9a-f]{64}"));
    }

    private static void refusals(Path base, Path out, Path patch, String id) throws Exception {
        refuses("apply without approval refused", () -> CodePatch.apply(base, out, List.of(patch), " "), "approvedPlan");
        refuses("apply with a malformed id refused", () -> CodePatch.apply(base, out, List.of(patch), "../x"), "64-hex");
        Path old = base.resolve("src/old.txt");
        byte[] before = Files.readAllBytes(old);
        Files.write(old, "x4\n".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        refuses("stale approval refused, naming the changed file", () -> CodePatch.apply(base, out, List.of(patch), id),
                "stale approval", id, "src/moved.txt");
        Files.write(old, before);
        lockHeld(base, out, patch, id);
    }

    private static void lockHeld(Path base, Path out, Path patch, String id) throws Exception {
        PatchLock lock = PatchLock.acquire(out);
        try {
            refuses("apply while the lock is held refused", () -> CodePatch.apply(base, out, List.of(patch), id), "holds the lock");
        } finally {
            lock.close();
        }
    }

    private static void applyAndRollback(Path base, Path out, Path patch, String id, Map<String, String> original)
            throws Exception {
        CodePatch.apply(base, out, List.of(patch), id);
        Path receipt = CodePatch.receipt(out, id);
        check("apply gives the after-images byte for byte", Fixtures.snapshot(base).equals(Fixtures.digests(Fixtures.AFTER)));
        receipt(receipt);
        attributes("by apply", base.resolve("src/crlf.txt"), false);
        refuses("second apply refused", () -> CodePatch.apply(base, out, List.of(patch), id), "src/crlf.txt", "hunk 1 of 1");
        CodePatch.rollback(base, out, id);
        check("rollback restores every file byte for byte", Fixtures.snapshot(base).equals(original));
        attributes("by rollback", base.resolve("src/crlf.txt"), true);
        refuses("second rollback refused", () -> CodePatch.rollback(base, out, id), "already rolled back");
        edited(base, out, patch, id, original);
        interrupted(base, out, patch, id, original);
    }

    private static void receipt(Path receipt) throws Exception {
        check("receipt APPLIED with backups", Files.readString(receipt.resolve("STATUS")).strip().equals("APPLIED")
                && Files.isRegularFile(receipt.resolve("backup/src/crlf.txt")) && Files.isRegularFile(receipt.resolve("backup/src/old.txt"))
                && Files.readString(receipt.resolve("RECEIPT.tsv")).startsWith(PlanRow.HEADER));
    }

    /** Re-apply, then a file edited after apply: rollback refuses until the edit is undone. */
    private static void edited(Path base, Path out, Path patch, String id, Map<String, String> original)
            throws Exception {
        CodePatch.apply(base, out, List.of(patch), id);
        Path moved = base.resolve("src/moved.txt");
        byte[] applied = Files.readAllBytes(moved);
        Files.write(moved, "edited\n".getBytes(StandardCharsets.UTF_8));
        refuses("rollback refused when a file changed after apply", () -> CodePatch.rollback(base, out, id),
                "rollback refused", "src/moved.txt");
        Files.write(moved, applied);
        CodePatch.rollback(base, out, id);
        check("re-apply then rollback restores every file", Fixtures.snapshot(base).equals(original));
    }

    /** An apply that stopped after some files: STATUS still PENDING, one file never promoted. */
    private static void interrupted(Path base, Path out, Path patch, String id, Map<String, String> original)
            throws Exception {
        CodePatch.apply(base, out, List.of(patch), id);
        Path receipt = CodePatch.receipt(out, id);
        Files.writeString(receipt.resolve("STATUS"), "PENDING\n");
        Files.copy(receipt.resolve("backup/src/crlf.txt"), base.resolve("src/crlf.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        CodePatch.rollback(base, out, id);
        check("rollback of an interrupted apply restores every file", Fixtures.snapshot(base).equals(original));
    }
}
