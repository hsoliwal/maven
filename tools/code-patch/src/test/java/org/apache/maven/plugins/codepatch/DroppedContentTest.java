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
import static org.apache.maven.plugins.codepatch.ParserEdgeTest.parse;
import static org.apache.maven.plugins.codepatch.ParserEdgeTest.shape;

/**
 * Patch content that must never be dropped without a refusal: a removed line {@code -- d} and an added line
 * {@code ++ e} after a hunk whose count is stale (they read as a {@code ---}/{@code +++} pair), a combined diff
 * ({@code diff --cc}, {@code diff --combined}, or its {@code @@@} hunks without that line), a malformed {@code @@}
 * header (also after a good hunk, and a one-{@code @} header inside a file section), and any other file section with
 * {@code ---}/{@code +++} lines but no hunk.
 */
final class DroppedContentTest {
    private static final String ONE = "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n";
    private static final String GIT_ONE = "diff --git a/x b/x\nindex 1..2 100644\n" + ONE;
    private static final String NEXT = "@@ -1 +1 @@\n-p\n+q\n";
    private static final String COMBINED = "--- a/f\n+++ b/f\n@@@ -1,1 -1,1 +1,1 @@@\n- a\n -b\n++c\n";

    private DroppedContentTest() { }

    static void run() throws Exception {
        stalePair();
        combined();
        malformedAfterHunk();
        refuses("malformed @@ header refused as unreadable", () -> parse("--- a/x\n+++ b/x\n@@-1 +1@@\n-a\n+b\n"),
                "x.patch line 3", "unreadable hunk header: @@-1 +1@@");
        refuses("git section with --- / +++ and no hunk refused",
                () -> parse("diff --git a/x b/x\n--- a/x\n+++ b/x\n"), "x.patch line 2", "without hunks");
        check("git section without --- / +++ (mode change only) is still no file",
                parse("diff --git a/x b/x\nold mode 100644\nnew mode 100755\n").isEmpty());
    }

    /** A malformed header after a good hunk must not end the hunks of its section and drop its lines. */
    private static void malformedAfterHunk() throws Exception {
        refuses("'@@-3 +3 @@' after a good hunk refused", () -> parse(ONE + "@@-3 +3 @@\n-c\n+C\n"),
                "x.patch line 6", "unreadable hunk header: @@-3 +3 @@");
        refuses("'@@@' hunk after a good git hunk (no diff --cc line) refused",
                () -> parse(GIT_ONE + "@@@ -3,1 -3,1 +3,1 @@@\n- c\n -c\n++C\n"), "x.patch line 8",
                "unreadable hunk header: @@@ -3,1 -3,1 +3,1 @@@");
        refuses("'@ -3 +3 @' after a good git hunk refused", () -> parse(GIT_ONE + "@ -3 +3 @\n-c\n+C\n"),
                "x.patch line 8", "unreadable hunk header: @ -3 +3 @");
        refuses("'@ -1 +1 @' after --- / +++ refused", () -> parse("--- a/x\n+++ b/x\n@ -1 +1 @\n-a\n+b\n"),
                "x.patch line 3", "unreadable hunk header: @ -1 +1 @");
        check("a line starting with @ before any file section is prose", shape(parse("@someone wrote\n" + ONE))
                .equals("MODIFY x x 1"));
    }

    /** A stale count: the next lines are a removed {@code "-- d"} and an added {@code "++ e"}, not a new section. */
    private static void stalePair() throws Exception {
        refuses("'-- d' / '++ e' after a stale hunk refused", () -> parse(ONE + "--- d\n+++ e\n"),
                "x.patch line 6", "after the end of hunk");
        refuses("'-- d' / '++ e' after a stale git hunk refused (a new git file needs diff --git)",
                () -> parse("diff --git a/x b/x\n" + ONE + "--- d\n+++ e\n" + NEXT), "x.patch line 7", "after the end of hunk");
        refuses("'-- d' / '++ e' after a stale hunk, then a context line, refused",
                () -> parse(ONE + "--- d\n+++ e\n c\n"), "after the end of hunk");
        check("--- / +++ pair followed by @@ after a plain hunk is the next diff -u file",
                shape(parse(ONE + "--- d\n+++ e\n" + NEXT)).equals("MODIFY x x 1; MODIFY e e 1"));
        check("--- / +++ pair after a diff --git line is the next git file", shape(parse("diff --git a/x b/x\n" + ONE
                + "diff --git a/y b/y\n--- a/y\n+++ b/y\n" + NEXT)).equals("MODIFY x x 1; MODIFY y y 1"));
    }

    private static void combined() {
        refuses("diff --cc section refused", () -> parse(ONE + "diff --cc f\nindex 1,2..3\n" + COMBINED),
                "x.patch line 6", "combined diff", "diff --cc f");
        refuses("diff --combined section refused", () -> parse("diff --combined f\n" + COMBINED), "combined diff");
        refuses("@@@ hunks without a diff --cc line refused (unreadable hunk header)", () -> parse(COMBINED),
                "x.patch line 3", "unreadable hunk header: @@@");
        refuses("@@@ hunks after a plain file refused", () -> parse(ONE + "diff -u a/f b/f\n" + COMBINED),
                "x.patch line 9", "unreadable hunk header: @@@");
    }
}
