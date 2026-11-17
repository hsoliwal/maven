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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Plans that must be refused: a second hunk whose context no longer matches (named with file, hunk and line), LF
 * context against a CRLF file, a hunk at a moved position (no offset search), paths that escape the base directory,
 * a missing file and an added file that already exists.
 */
final class RefusalTest {
    private static final String LONG = IntStream.rangeClosed(1, 20).mapToObj(i -> "l" + i + "\n").collect(Collectors.joining());
    private static final String TWO_HUNKS = "--- a/src/long.txt\n+++ b/src/long.txt\n"
            + "@@ -2,3 +2,3 @@\n l2\n-l3\n+L3\n l4\n"
            + "@@ -15,3 +15,3 @@\n l15\n-l16\n+L16\n l17\n";

    private RefusalTest() { }

    static void run(Path dir) throws Exception {
        Path base = Fixtures.tree(dir.resolve("tree"), Map.of("src/long.txt", LONG.replace("l17\n", "changed\n"),
                "src/crlf.txt", "one\r\ntwo\r\n", "src/shifted.txt", "new first line\n" + LONG));
        Path out = base.resolve("target/patch");
        refuses("context mismatch names file, hunk and line", () -> plan(base, out, "two.patch", TWO_HUNKS),
                "two.patch: src/long.txt: hunk 2 of 2 (@@ -15,3 +15,3 @@) does not match at line 17", "\"changed\\n\"");
        refuses("LF context against a CRLF file refused", () -> plan(base, out, "lf.diff",
                "--- a/src/crlf.txt\n+++ b/src/crlf.txt\n@@ -1,2 +1,2 @@\n one\n-two\n+TWO\n"), "src/crlf.txt: hunk 1 of 1", "\"one\\r\\n\"");
        refuses("hunk at a moved position refused (no offset search)", () -> plan(base, out, "shift.diff",
                TWO_HUNKS.replace("src/long.txt", "src/shifted.txt")), "src/shifted.txt: hunk 1 of 2", "line 2");
        paths(dir, base, out);
    }

    private static void paths(Path dir, Path base, Path out) {
        refuses("path with .. refused", () -> plan(base, out, "up.patch",
                "diff --git a/../outside.txt b/../outside.txt\nnew file mode 100644\n--- /dev/null\n+++ b/../outside.txt\n"
                        + "@@ -0,0 +1 @@\n+x\n"), "../outside.txt", "escapes");
        refuses("absolute path refused", () -> plan(base, out, "abs.patch",
                "--- /dev/null\n+++ b//tmp/outside.txt\n@@ -0,0 +1 @@\n+x\n"), "/tmp/outside.txt", "escapes");
        check("nothing was written outside the base directory", !Files.exists(dir.resolve("outside.txt")));
        refuses("missing file refused", () -> plan(base, out, "missing.diff",
                "--- a/src/none.txt\n+++ b/src/none.txt\n@@ -1 +1 @@\n-a\n+b\n"), "src/none.txt: no such file");
        refuses("added file that exists refused", () -> plan(base, out, "exists.diff",
                "--- /dev/null\n+++ b/src/crlf.txt\n@@ -0,0 +1 @@\n+x\n"), "src/crlf.txt: already exists");
    }

    private static void plan(Path base, Path out, String name, String patch) throws Exception {
        CodePatch.plan(base, out, List.of(Fixtures.write(base.resolveSibling("patches").resolve(name), patch)));
    }
}
