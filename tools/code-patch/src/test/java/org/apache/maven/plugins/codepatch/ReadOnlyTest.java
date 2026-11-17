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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.List;
import java.util.Map;

/**
 * Windows read-only files: a read-only target is refused at plan time (apply could not replace it), and a plan whose
 * earlier receipt (ROLLED_BACK) holds a read-only backup applies again. Skipped where there is no read-only attribute.
 */
final class ReadOnlyTest {
    private static final String PATCH = "--- a/ro/r.txt\n+++ b/ro/r.txt\n@@ -1 +1 @@\n-r\n+R\n";

    private ReadOnlyTest() { }

    static void run(Path dir) throws Exception {
        if (!Scratch.windows()) {
            Links.skip("read-only targets on Windows", "not Windows");
            return;
        }
        Path base = Fixtures.tree(dir.resolve("tree"), Map.of("ro/r.txt", "r\n"));
        Path out = base.resolve("target/patch");
        Path patch = Fixtures.write(dir.resolve("ro.patch"), PATCH);
        Path target = base.resolve("ro/r.txt");
        readOnly(target, true);
        refuses("plan refuses a read-only target", () -> CodePatch.plan(base, out, List.of(patch)), "ro/r.txt", "read-only");
        readOnly(target, false);
        reapply(base, out, patch);
    }

    /** A ROLLED_BACK receipt whose backup is read-only: apply of the same plan replaces the old receipt. */
    private static void reapply(Path base, Path out, Path patch) throws Exception {
        String id = CodePatch.plan(base, out, List.of(patch)).id();
        CodePatch.apply(base, out, List.of(patch), id);
        CodePatch.rollback(base, out, id);
        Path backup = CodePatch.receipt(out, id).resolve("backup/ro/r.txt");
        readOnly(backup, true);
        String outcome = Checks.outcome(() -> CodePatch.apply(base, out, List.of(patch), id));
        check("plan applies again over a read-only backup of a rolled-back receipt: " + outcome, outcome.equals("done"));
        if (outcome.equals("done")) CodePatch.rollback(base, out, id);
        Scratch.writable(backup);
    }


    private static void readOnly(Path file, boolean on) throws Exception {
        if (Files.exists(file)) Files.getFileAttributeView(file, DosFileAttributeView.class).setReadOnly(on);
    }
}
