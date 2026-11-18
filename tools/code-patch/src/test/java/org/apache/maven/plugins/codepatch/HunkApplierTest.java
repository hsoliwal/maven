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

/**
 * The applier never joins two lines: a line written after a line without a terminator (a zero-context insertion after
 * an unterminated last line, or a hunk that leaves a middle line unterminated) is refused. Removing or adding the
 * final newline of the last line still works.
 */
final class HunkApplierTest {
    private HunkApplierTest() { }

    static void run() throws Exception {
        refuses("insertion after an unterminated last line refused",
                () -> apply("a\nb", "--- a/f\n+++ b/f\n@@ -2,0 +3 @@\n+c\n"), "f: hunk 1 of 1", "line terminator");
        refuses("hunk that leaves a middle line unterminated refused",
                () -> apply("a\nb\nc\n", "--- a/f\n+++ b/f\n@@ -2 +2 @@\n-b\n+B\n\\ No newline at end of file\n"),
                "f: hunk 1 of 1", "line terminator");
        check("removing the final newline of the last line still works", apply("a\nb\n",
                "--- a/f\n+++ b/f\n@@ -2 +2 @@\n-b\n+b\n\\ No newline at end of file\n").equals("a\nb"));
        check("insertion before an unterminated last line still works",
                apply("a\nb", "--- a/f\n+++ b/f\n@@ -1,0 +2 @@\n+x\n").equals("a\nx\nb"));
    }

    private static String apply(String before, String patch) throws PatchException {
        FilePatch file = ParserEdgeTest.parse(patch).get(0);
        byte[] after = HunkApplier.apply("f", before.getBytes(StandardCharsets.UTF_8), file.hunks());
        return new String(after, StandardCharsets.UTF_8);
    }
}
