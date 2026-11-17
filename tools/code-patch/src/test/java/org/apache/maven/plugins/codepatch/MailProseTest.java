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

/**
 * A {@code git format-patch} mail whose commit message uses git header words in prose ({@code rename from},
 * {@code copy from}, {@code Binary files}, {@code new file mode}) plans like the same mail without them; a mail with
 * an exact git header line before its first section is still refused.
 */
final class MailProseTest {
    private static final String HEAD = "From 0123 Mon Sep 17 00:00:00 2001\nFrom: someone <someone@example.org>\n"
            + "Subject: [PATCH] rename the reader\n\n";
    private static final String PROSE = "rename from the old reader to the new one\nrename to keep the API small\n"
            + "copy from upstream where it fits\nBinary files are no longer generated.\nnew file mode handling\n"
            + "GIT binary patch support is dropped as well\n";
    private static final String BODY = "---\n src/a.txt | 2 +-\n 1 file changed\n\n"
            + "diff --git a/src/a.txt b/src/a.txt\nindex 1111111..2222222 100644\n--- a/src/a.txt\n+++ b/src/a.txt\n"
            + "@@ -1 +1 @@\n-a\n+A\n-- \n2.43.0\n\n";

    private MailProseTest() { }

    static void run(Path dir) throws Exception {
        Path base = Fixtures.tree(dir.resolve("tree"), Map.of("src/a.txt", "a\n", "src/b.txt", "b\n"));
        Path out = base.resolve("target/patch");
        Plan plain = plan(base, out, "0001-plain.patch", HEAD + BODY);
        Plan prose = plan(base, out, "0001-prose.patch", HEAD + PROSE + "\n" + BODY);
        check("format-patch mail with git words in its commit message plans: " + prose.summary(),
                prose.rows().equals(plain.rows()) && prose.rows().size() == 1);
        refuses("format-patch mail with an exact git header line before its first section refused",
                () -> plan(base, out, "0001-binary.patch", HEAD + "Binary files a/x and b/x differ\n" + BODY),
                "0001-binary.patch line 5", "Binary files");
        mbox(base, out);
    }

    /**
     * {@code git format-patch --stdout}: one file, one mail per commit, each starting with its {@code From <sha>} line.
     * The second commit message may use git words too; without that line they follow a section's hunks and are refused.
     */
    private static void mbox(Path base, Path out) throws Exception {
        String second = "Subject: [PATCH 2/2] change b\n\n" + PROSE + "\n---\n src/b.txt | 2 +-\n\n"
                + "diff --git a/src/b.txt b/src/b.txt\nindex 3333333..4444444 100644\n--- a/src/b.txt\n+++ b/src/b.txt\n"
                + "@@ -1 +1 @@\n-b\n+B\n-- \n2.43.0\n\n";
        String from = "From 0123456789abcdef0123456789abcdef01234567 Mon Sep 17 00:00:00 2001\n";
        Plan series = plan(base, out, "series.mbox", HEAD + PROSE + "\n" + BODY + from + second);
        check("format-patch --stdout series whose second message uses git words plans: " + series.summary(),
                series.rows().size() == 2 && series.rows().get(1).path().equals("src/b.txt"));
        refuses("second message without its From <sha> line is refused (rename lines after a section's hunks)",
                () -> plan(base, out, "joined.mbox", HEAD + BODY + "From 0123 Mon Sep 17 00:00:00 2001\n" + second),
                "joined.mbox line 22", "rename from the old reader");
    }

    private static Plan plan(Path base, Path out, String name, String patch) throws Exception {
        return CodePatch.plan(base, out, List.of(Fixtures.write(base.resolveSibling("patches").resolve(name), patch)));
    }
}
