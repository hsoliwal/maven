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
        damagedFirstCharacter();
        damagedBeforeFirstHunk();
        boundaries();
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

    /**
     * A hunk header damaged at its first character (a tab, a no-break space, quoted-printable {@code =40=40}, a word)
     * after a good hunk: text inside a file section with hunks is refused, so its lines are never skipped as text.
     */
    private static void damagedFirstCharacter() {
        refuses("tab-indented '@@' header after a good git hunk refused as unreadable",
                () -> parse(GIT_ONE + "\t@@ -3 +3 @@\n-c\n+C\n"), "x.patch line 8", "unreadable hunk header");
        refuses("tab-indented '@@' header after a good diff -u hunk refused as unreadable",
                () -> parse(ONE + "\t@@ -3 +3 @@\n-c\n+C\n"), "x.patch line 6", "unreadable hunk header");
        refuses("no-break space before '@@' after a good hunk refused", () -> parse(GIT_ONE + " @@ -3 +3 @@\n-c\n+C\n"),
                "x.patch line 8", "text inside a file section");
        refuses("quoted-printable '=40=40' header after a good hunk refused",
                () -> parse(GIT_ONE + "=40=40 -3 +3 =40=40\n-c\n+C\n"), "x.patch line 8", "text inside a file section");
        refuses("a word line after a good git hunk refused", () -> parse(GIT_ONE + "Hunk 2\n-c\n+C\n"), "x.patch line 8",
                "text inside a file section");
        refuses("a damaged header in a diff -u section refused", () -> parse(ONE + "x@@ -3 +3 @@\n-c\n+C\n"),
                "x.patch line 6", "text inside a file section");
    }

    /** Between the {@code ---}/{@code +++} lines and the first hunk, and in a git header, a damaged header is refused. */
    private static void damagedBeforeFirstHunk() {
        refuses("tab-indented first header refused, before a good second hunk",
                () -> parse("--- a/x\n+++ b/x\n\t@@ -1 +1 @@\n-a\n+b\n@@ -5 +5 @@\n-e\n+E\n"), "x.patch line 3",
                "unreadable hunk header");
        refuses("a word line between +++ and the first hunk refused",
                () -> parse("--- a/x\n+++ b/x\nHunk\n-a\n+b\n@@ -5 +5 @@\n-e\n+E\n"), "x.patch line 3",
                "text inside a file section");
        refuses("'@ -1 +1 @' in a git header without ---/+++ refused (the file would vanish)",
                () -> parse("diff --git a/x b/x\nindex 1..2 100644\n@ -1 +1 @\n-a\n+b\n"), "x.patch line 3",
                "header of a diff --git section");
        refuses("hunk lines in a git header without ---/+++ refused",
                () -> parse("diff --git a/x b/x\nindex 1..2 100644\n-a\n+b\n"), "x.patch line 3",
                "header of a diff --git section");
    }

    /**
     * What may follow a section's hunks: blank lines, the next file ({@code diff} line, {@code ---}/{@code +++} pair),
     * the signature and the text after it, a {@code git log -p} commit line, a {@code From <sha>} mail line.
     */
    private static void boundaries() throws Exception {
        String footer = "-- \n2.43.0\n\n_______________________________________________\nlist mailing list\n";
        check("signature and a mailing-list footer after the hunks plan", shape(parse(GIT_ONE + footer)).equals("MODIFY x x 1"));
        check("a pure rename before the signature plans", shape(parse("diff --git a/x b/y\nsimilarity index 100%\n"
                + "rename from x\nrename to y\n" + footer)).equals("RENAME x y 0"));
        gitLog();
        check("diff -ruN lines between files plan", shape(parse("diff -ruN a/x b/x\n" + ONE + "diff -ruN a/y b/y\n"
                + "--- a/y\n+++ b/y\n" + NEXT)).equals("MODIFY x x 1; MODIFY y y 1"));
        check("blank lines after the hunks plan", shape(parse(GIT_ONE + "\n\ndiff --git a/y b/y\n--- a/y\n+++ b/y\n" + NEXT))
                .equals("MODIFY x x 1; MODIFY y y 1"));
        refuses("hunk header after the signature refused", () -> parse(GIT_ONE + "-- \n2.43.0\n" + NEXT),
                "x.patch line 10", "after the format-patch signature");
    }

    /** {@code git log -p}: a commit line (SHA-1 or SHA-256, with or without decorations) ends the file before it. */
    private static void gitLog() throws Exception {
        String sha1 = "0123456789abcdef0123456789abcdef01234567";
        String sha256 = sha1 + "0123456789abcdef01234567";
        String log = "commit " + sha1 + "\nAuthor: A <a@example.org>\nDate:   Mon Sep 17 2001\n\n    first\n\n" + GIT_ONE
                + "\ncommit " + sha256 + " (HEAD -> main)\nAuthor: A <a@example.org>\n\n    second\n    rename from x\n\n"
                + "diff --git a/y b/y\n--- a/y\n+++ b/y\n" + NEXT;
        check("git log -p output plans", shape(parse(log)).equals("MODIFY x x 1; MODIFY y y 1"));
        refuses("a commit line with a short hash after the hunks refused",
                () -> parse(GIT_ONE + "commit 0123abc\n-c\n+C\n"), "x.patch line 8", "text inside a file section");
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
