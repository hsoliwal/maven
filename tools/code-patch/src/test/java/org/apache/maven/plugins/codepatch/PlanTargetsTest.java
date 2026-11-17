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
 * Plan refuses targets that apply could not write: an added file under an existing regular file, an added file under
 * a dangling directory link, a Windows device name, and a target inside the output directory.
 */
final class PlanTargetsTest {
    private PlanTargetsTest() { }

    static void run(Path dir) throws Exception {
        Path base = Fixtures.tree(dir.resolve("tree"), Map.of("f.txt", "f\n", "src/a.txt", "a\n"));
        Path out = base.resolve("target/patch");
        refuses("added file under a regular file refused", () -> plan(base, out, "under-file.diff", add("f.txt/x")),
                "f.txt/x", "not a directory");
        refuses("target inside the output directory refused", () -> plan(base, out, "into-out.diff", add("target/patch/x.txt")),
                "target/patch/x.txt", "output directory");
        check("added file next to the output directory is planned",
                plan(base, out, "next-to-out.diff", add("target/other.txt")).rows().size() == 1);
        dangling(base, out);
        devices(base, out);
    }

    private static void devices(Path base, Path out) throws Exception {
        if (Scratch.windows()) {
            refuses("Windows device name refused", () -> plan(base, out, "nul.diff", add("src/NUL")), "src/NUL", "reserved");
            refuses("Windows device name with extension refused", () -> plan(base, out, "aux.diff", add("src/aux.c")), "reserved");
        } else {
            check("device names are ordinary names on POSIX", plan(base, out, "nul.diff", add("src/NUL")).rows().size() == 1);
        }
    }

    private static void dangling(Path base, Path out) throws Exception {
        Path link = base.resolve("dl");
        if (!Links.symbolic(link, base.resolve("missing"))) {
            Links.skip("added file under a dangling directory link refused", "cannot create a symbolic link here");
            return;
        }
        refuses("added file under a dangling directory link refused", () -> plan(base, out, "dangling.diff", add("dl/x")),
                "dl/x", "not a directory");
        Files.delete(link);
    }

    static String add(String path) {
        return "--- /dev/null\n+++ b/" + path + "\n@@ -0,0 +1 @@\n+x\n";
    }

    private static Plan plan(Path base, Path out, String name, String patch) throws Exception {
        return CodePatch.plan(base, out, List.of(Fixtures.write(base.resolveSibling("patches").resolve(name), patch)));
    }
}
