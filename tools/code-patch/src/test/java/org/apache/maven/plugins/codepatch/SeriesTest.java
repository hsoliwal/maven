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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A patch series in one directory, applied in name order: the second patch changes the first one's result and renames
 * the file, so the plan has one RENAME row with both hunks and the net content.
 */
final class SeriesTest {
    private static final String FIRST = "--- a/s.txt\n+++ b/s.txt\n@@ -1,3 +1,3 @@\n-1\n+one\n 2\n 3\n";
    private static final String SECOND = "diff --git a/s.txt b/t.txt\nsimilarity index 60%\nrename from s.txt\n"
            + "rename to t.txt\n--- a/s.txt\n+++ b/t.txt\n@@ -1,3 +1,3 @@\n one\n 2\n-3\n+three\n";

    private SeriesTest() { }

    static void run(Path dir) throws Exception {
        Path base = Fixtures.tree(dir.resolve("tree"), Map.of("s.txt", "1\n2\n3\n"));
        Path patches = dir.resolve("patches");
        Fixtures.write(patches.resolve("0002-second.patch"), SECOND);
        Fixtures.write(patches.resolve("0001-first.patch"), FIRST);
        Fixtures.write(patches.resolve("notes.txt"), "not a patch");
        Plan plan = CodePatch.plan(base, base.resolve("target/patch"), List.of(patches));
        PlanRow row = plan.rows().get(0);
        check("series: one RENAME row with both hunks", plan.rows().size() == 1 && row.operation() == FilePatch.Operation.RENAME
                && row.path().equals("t.txt") && row.fromPath().equals("s.txt") && row.hunks() == 2);
        check("series: net after-image", Fixtures.digests(Map.of("t.txt", "one\n2\nthree\n")).get("t.txt")
                .equals(Digests.sha256(plan.after().get("t.txt"))));
    }
}
