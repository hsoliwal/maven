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
import java.util.List;
import java.util.stream.Collectors;

/**
 * Parser edges: hunk lines after a body whose header count is exhausted (stale count; {@code "-- "} is the signature
 * only before plain text, not at the end, before a header, a pair, a blank or a hunk line), hunk numbers too large for an
 * int, {@code --no-prefix} git sections with {@code /dev/null} on one side, {@code diff --git} paths that disagree
 * with the {@code ---}/{@code +++} paths, a leading byte order mark, git header lines outside a section header (before
 * the first section only exact git syntax; the same words in prose are ignored), and a no-newline marker that is not
 * on the last line of its side.
 */
final class ParserEdgeTest {
    private static final String ONE = "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n";
    private static final String GIT_ONE = "diff --git a/x b/x\nindex 1..2 100644\n" + ONE;

    private ParserEdgeTest() { }

    static void run() throws Exception {
        staleCount();
        noPrefix();
        bomAndOutside();
        refuses("hunk numbers beyond int refused", () -> parse("--- a/x\n+++ b/x\n@@ -99999999999 +1 @@\n-a\n+b\n"),
                "x.patch line", "hunk header");
        refuses("no-newline marker before the end of its side refused",
                () -> parse("--- a/x\n+++ b/x\n@@ -1 +1,2 @@\n-x\n+a\n\\ No newline at end of file\n+b\n"),
                "No newline at end of file", "last line");
    }

    private static void staleCount() throws Exception {
        refuses("context line after a finished hunk refused (stale count)", () -> parse(ONE + " c\n+d\n"),
                "x.patch line 6", "after the end of hunk");
        refuses("removed line after a finished hunk refused", () -> parse(ONE + "-c\n"), "after the end of hunk");
        refuses("added line after the no-newline marker refused",
                () -> parse(ONE + "\\ No newline at end of file\n+c\n"), "after the end of hunk");
        check("--- / +++ pair after a hunk starts the next section",
                shape(parse(ONE + "--- a/y\n+++ b/y\n@@ -1 +1 @@\n-c\n+d\n")).equals("MODIFY x x 1; MODIFY y y 1"));
        check("format-patch signature after a hunk is not a hunk line",
                shape(parse(ONE + "-- \n2.43.0\n\n")).equals("MODIFY x x 1"));
        refuses("hunk line right after the signature refused", () -> parse(ONE + "-- \n+c\n"), "after the end of hunk");
        signature();
    }

    /** {@code "-- "} is the signature only before plain text (its version line); before anything else it is a line. */
    private static void signature() {
        refuses("'-- ' at the end of the patch is a removed line, not the signature", () -> parse(ONE + "-- \n"),
                "x.patch line 6", "after the end of hunk");
        refuses("'-- ' before a diff --git line is a removed line, not the signature",
                () -> parse(GIT_ONE + "-- \ndiff --git a/y b/y\n--- a/y\n+++ b/y\n@@ -1 +1 @@\n-p\n+q\n"),
                "x.patch line 8", "after the end of hunk");
        refuses("'-- ' before a --- / +++ pair is a removed line", () -> parse(ONE + "-- \n--- a/y\n+++ b/y\n@@ -1 +1 @@\n"
                + "-p\n+q\n"), "x.patch line 6", "after the end of hunk");
        refuses("'-- ' before an @@ header is a removed line", () -> parse(ONE + "-- \n@@ -3 +3 @@\n-c\n+C\n"),
                "x.patch line 6", "after the end of hunk");
        refuses("'-- ' before a blank line is a removed line", () -> parse(ONE + "-- \n\n"), "x.patch line 6",
                "after the end of hunk");
    }

    private static void noPrefix() throws Exception {
        check("--no-prefix added file keeps its first segment", shape(parse("diff --git b/x b/x\nnew file mode 100644\n"
                + "--- /dev/null\n+++ b/x\n@@ -0,0 +1 @@\n+x\n")).equals("ADD null b/x 1"));
        check("--no-prefix deleted file keeps its first segment", shape(parse("diff --git a/y a/y\n"
                + "deleted file mode 100644\n--- a/y\n+++ /dev/null\n@@ -1 +0,0 @@\n-y\n")).equals("DELETE a/y null 1"));
        check("--no-prefix modified file", shape(parse("diff --git s/x s/x\n--- s/x\n+++ s/x\n@@ -1 +1 @@\n-a\n+b\n"))
                .equals("MODIFY s/x s/x 1"));
        refuses("diff --git paths that disagree with --- / +++ refused",
                () -> parse("diff --git a/x b/x\n--- a/z\n+++ b/z\n@@ -1 +1 @@\n-a\n+b\n"), "disagree", "x", "z");
        refuses("unprefixed --- / +++ under a prefixed diff --git refused",
                () -> parse("diff --git a/x b/x\n--- x\n+++ x\n@@ -1 +1 @@\n-a\n+b\n"), "disagree");
    }

    private static void bomAndOutside() throws Exception {
        String rename = "diff --git a/x b/y\nsimilarity index 100%\nrename from x\nrename to y\n";
        check("leading byte order mark: the pure rename is read", shape(parse("﻿" + rename)).equals("RENAME x y 0"));
        refuses("Binary files line before any section refused", () -> parse("Binary files a/x and b/x differ\n"), "Binary files");
        refuses("GIT binary patch before any section refused", () -> parse("GIT binary patch\nliteral 0\n"), "GIT binary patch");
        refuses("new file mode before any section refused", () -> parse("new file mode 100644\n" + ONE), "new file mode");
        refuses("deleted file mode before any section refused", () -> parse("deleted file mode 100755\n" + ONE),
                "deleted file mode");
        refuses("rename lines after a section's hunks refused",
                () -> parse(ONE + "index 1..2\nrename from p\nrename to q\n"), "rename from p");
        prose();
    }

    /** Before the first section only exact git syntax is refused; the same words in a commit message are prose. */
    private static void prose() throws Exception {
        check("rename lines before the first section are prose (no diff --git line)",
                shape(parse("rename from x\nrename to y\n" + ONE)).equals("MODIFY x x 1"));
        check("copy line before the first section is prose", shape(parse("copy from x\ncopy to y\n" + ONE)).equals("MODIFY x x 1"));
        String words = "Binary files are no longer generated.\nGIT binary patch support is dropped\nnew file mode handling\n"
                + "deleted file mode 100644 is kept\nBinary files a and b differ in size\n";
        check("git words in prose before the first section are ignored", shape(parse(words + ONE)).equals("MODIFY x x 1"));
    }

    static List<FilePatch> parse(String patch) throws PatchException {
        return PatchParser.parse(patch.getBytes(StandardCharsets.UTF_8), "x.patch");
    }

    static String shape(List<FilePatch> files) {
        return files.stream().map(f -> f.operation() + " " + f.oldPath() + " " + f.newPath() + " " + f.hunks().size())
                .collect(Collectors.joining("; "));
    }
}
