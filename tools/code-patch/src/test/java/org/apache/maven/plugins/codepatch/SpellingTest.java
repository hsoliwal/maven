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

/**
 * A path that names an existing file or directory by another spelling (another letter case on a case-insensitive
 * volume, an 8.3 short name, a directory link inside the tree) is refused with the real spelling: the plan keys files
 * by the patch text, so apply and rollback would otherwise leave the file renamed to the patch's spelling.
 */
final class SpellingTest {
    private static final String REAL = "src/Reader.java";
    private static final String LONG = "src/LongFileName.txt";

    private SpellingTest() { }

    static void run(Path dir) throws Exception {
        Path base = Fixtures.tree(dir.resolve("tree"), Map.of(REAL, "a\n", LONG, "l\n"));
        check("the real spelling resolves", TargetPaths.resolve(base, REAL).equals(base.resolve(REAL)));
        check("a new file under the real spelling resolves", TargetPaths.resolve(base, "src/New.java").startsWith(base));
        letterCase(base);
        shortName(base);
        link(base);
    }

    private static void letterCase(Path base) throws Exception {
        if (!Files.exists(base.resolve("SRC/READER.JAVA"))) {
            Links.skip("other letter case refused", "case-sensitive volume");
            check("other letter case is another file here", TargetPaths.resolve(base, "src/reader.java").startsWith(base));
            return;
        }
        refuses("other letter case of a file refused", () -> TargetPaths.resolve(base, "src/reader.java"),
                "src/reader.java", "real spelling src/Reader.java");
        refuses("other letter case of a directory refused (new file)", () -> TargetPaths.resolve(base, "SRC/New.java"),
                "SRC/New.java", "real spelling src");
        refuses("other letter case of a file refused (all upper case)", () -> TargetPaths.resolve(base, "src/READER.JAVA"),
                "src/READER.JAVA", "real spelling src/Reader.java");
        planCase(base);
    }

    private static void planCase(Path base) throws Exception {
        refuses("directory listing alone refuses another letter case (no toRealPath)",
                () -> OnDiskNames.require(base, "SRC/reader.java"), "SRC/reader.java", "real spelling src;");
        String patch = "--- a/src/READER.java\n+++ b/src/READER.java\n@@ -1 +1 @@\n-a\n+b\n";
        Path file = Fixtures.write(base.resolveSibling("patches").resolve("case.diff"), patch);
        refuses("plan refuses another letter case, so apply and rollback cannot rename the file",
                () -> CodePatch.plan(base, base.resolve("target/patch"), List.of(file)), "src/READER.java", "src/Reader.java");
    }

    private static void shortName(Path base) {
        if (!Files.exists(base.resolve("src/LONGFI~1.TXT"))) {
            Links.skip("8.3 short name of a file refused", "this volume has no short name LONGFI~1.TXT");
            return;
        }
        refuses("8.3 short name of a file refused", () -> TargetPaths.resolve(base, "src/LONGFI~1.TXT"),
                "src/LONGFI~1.TXT", "real spelling " + LONG);
        refuses("8.3 short name of a file in lower case refused", () -> TargetPaths.resolve(base, "src/longfi~1.txt"),
                "src/longfi~1.txt", "real spelling " + LONG);
        refuses("directory listing alone refuses an 8.3 short name (no toRealPath)",
                () -> OnDiskNames.require(base, "src/LONGFI~1.TXT"), "real spelling " + LONG);
    }

    /** A directory link inside the tree that points at {@code src}: two spellings of the same files. */
    private static void link(Path base) throws Exception {
        Path link = base.resolve("alias");
        boolean junction = Scratch.windows();
        if (!(junction ? Links.junction(link, base.resolve("src")) : Links.symbolic(link, base.resolve("src")))) {
            Links.skip("directory link inside the tree refused", "cannot create one here");
            return;
        }
        refuses("directory link inside the tree refused", () -> TargetPaths.resolve(base, "alias/Reader.java"),
                "alias/Reader.java", "real spelling " + REAL);
        Files.delete(link);
    }
}
