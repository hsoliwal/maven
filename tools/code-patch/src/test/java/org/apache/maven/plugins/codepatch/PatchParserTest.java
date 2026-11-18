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

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.unifieddiff.UnifiedDiffFile;
import com.github.difflib.unifieddiff.UnifiedDiffReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.maven.plugins.codepatch.FilePatch.Line;

/**
 * The parser on the fixture patch: operations, paths, kept {@code \r\n}, the no-newline marker, a format-patch mail
 * around the diff, and refusals. The same patch read by java-diff-utils serves as an independent oracle for file names,
 * operations and old-side/new-side line counts.
 */
final class PatchParserTest {
    private PatchParserTest() { }

    static void run() throws Exception {
        byte[] bytes = Fixtures.SERIES.getBytes(StandardCharsets.UTF_8);
        List<FilePatch> files = PatchParser.parse(bytes, "series.patch");
        sections(files);
        Line removed = files.get(0).hunks().get(0).lines().get(1);
        check("CRLF line keeps \\r\\n", removed.kind() == '-' && new String(removed.bytes(), StandardCharsets.UTF_8).equals("two\r\n"));
        List<Line> nonl = files.get(4).hunks().get(0).lines();
        check("no-newline marker strips the last line's terminator",
                new String(nonl.get(nonl.size() - 1).bytes(), StandardCharsets.UTF_8).equals("c"));
        mail(files);
        finalNewline();
        oracle(bytes, files);
        refusals();
    }

    private static void sections(List<FilePatch> files) {
        String shape = files.stream().map(f -> f.operation() + " " + f.oldPath() + " " + f.newPath() + " " + f.hunks().size())
                .collect(Collectors.joining("; "));
        check("sections " + shape, shape.equals("MODIFY src/crlf.txt src/crlf.txt 1; DELETE src/gone.txt null 1; "
                + "RENAME src/keep.txt src/kept.txt 0; ADD null src/new.txt 1; MODIFY src/nonl.txt src/nonl.txt 1; "
                + "RENAME src/old.txt src/moved.txt 1"));
    }

    /** The same patch inside a format-patch mail: headers, commit message, diffstat and signature around it. */
    private static void mail(List<FilePatch> files) throws Exception {
        String mail = "From 0123 Mon Sep 17 00:00:00 2001\nFrom: someone <someone@example.org>\nSubject: [PATCH] change\n\n"
                + "---\n src/crlf.txt | 2 +-\n 1 file changed\n\n" + Fixtures.SERIES + "-- \n2.43.0\n\n";
        List<FilePatch> mailed = PatchParser.parse(mail.getBytes(StandardCharsets.UTF_8), "0001-change.patch");
        check("format-patch mail parses to the same sections", mailed.size() == files.size()
                && mailed.get(5).hunks().get(0).lines().size() == files.get(5).hunks().get(0).lines().size());
    }

    /** A patch that adds the missing final newline: "-c" with the marker, "+c" without it. */
    private static void finalNewline() throws Exception {
        String patch = "--- a/f.txt\n+++ b/f.txt\n@@ -3 +3 @@\n-c\n\\ No newline at end of file\n+c\n";
        FilePatch file = PatchParser.parse(patch.getBytes(StandardCharsets.UTF_8), "eol.diff").get(0);
        byte[] after = HunkApplier.apply("f.txt", "a\nb\nc".getBytes(StandardCharsets.UTF_8), file.hunks());
        check("diff -u section adds the final newline", file.operation() == FilePatch.Operation.MODIFY
                && new String(after, StandardCharsets.UTF_8).equals("a\nb\nc\n"));
    }

    private static void oracle(byte[] bytes, List<FilePatch> files) throws Exception {
        List<UnifiedDiffFile> theirs = UnifiedDiffReader.parseUnifiedDiff(new ByteArrayInputStream(bytes)).getFiles();
        String ours = files.stream().map(PatchParserTest::shape).collect(Collectors.joining("; "));
        String oracle = theirs.stream().map(PatchParserTest::shape).collect(Collectors.joining("; "));
        check("java-diff-utils oracle agrees on names and line counts: " + oracle, ours.equals(oracle));
    }

    /** Names and the old-side and new-side line counts (java-diff-utils keeps a hunk's context in its delta). */
    private static String shape(FilePatch f) {
        long old = f.hunks().stream().flatMap(h -> h.lines().stream()).filter(l -> l.kind() != '+').count();
        long now = f.hunks().stream().flatMap(h -> h.lines().stream()).filter(l -> l.kind() != '-').count();
        return (f.oldPath() == null ? "/dev/null" : f.oldPath()) + ">" + (f.newPath() == null ? "/dev/null" : f.newPath())
                + " old " + old + " new " + now;
    }

    private static String shape(UnifiedDiffFile f) {
        List<AbstractDelta<String>> deltas = f.getPatch().getDeltas();
        int old = deltas.stream().mapToInt(d -> d.getSource().size()).sum();
        int now = deltas.stream().mapToInt(d -> d.getTarget().size()).sum();
        return f.getFromFile() + ">" + f.getToFile() + " old " + old + " new " + now;
    }

    private static void refusals() {
        refuses("binary patch refused", () -> parse("diff --git a/x.bin b/x.bin\nBinary files a/x.bin and b/x.bin differ\n"),
                "x.patch", "binary");
        refuses("copy refused", () -> parse("diff --git a/x b/y\nsimilarity index 100%\ncopy from x\ncopy to y\n"), "copies");
        refuses("quoted path refused", () -> parse("diff --git \"a/t\\tx\" \"b/t\\tx\"\n"), "quoted");
        refuses("truncated hunk refused", () -> parse("--- a/x\n+++ b/x\n@@ -1,3 +1,3 @@\n a\n-b\n"),
                "x.patch line", "ends before its last line");
        refuses("hunk longer than its header refused", () -> parse("--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n-b\n+c\n"),
                "more lines than its header counts");
    }

    private static void parse(String patch) throws PatchException {
        PatchParser.parse(patch.getBytes(StandardCharsets.UTF_8), "x.patch");
    }
}
