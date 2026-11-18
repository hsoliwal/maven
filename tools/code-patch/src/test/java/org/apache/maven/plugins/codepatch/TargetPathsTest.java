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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Paths that reach {@code .git} by another spelling (a trailing dot or space, the 8.3 short name in any case, also
 * through {@code plan}, a symbolic link or a junction inside the tree), control characters that would break the
 * tab-separated receipt, and names Windows cannot hold, which must be refusals rather than
 * {@code InvalidPathException}.
 */
final class TargetPathsTest {
    private TargetPathsTest() { }

    static void run(Path dir) throws Exception {
        Path base = Fixtures.tree(dir.resolve("tree"), Map.of(".git/config", "[core]\n", "src/a.txt", "a\n"));
        gitByName(base);
        gitByLink(base, "sl", false);
        gitByLink(base, "jn", true);
        controlCharacters(base);
        windowsNames(base);
    }

    private static void gitByName(Path base) throws Exception {
        refuses(".git. (trailing dot) refused", () -> TargetPaths.resolve(base, ".git./config"), ".git./config");
        refuses(".git with a trailing space refused", () -> TargetPaths.resolve(base, ".git /config"), ".git /config");
        refuses("a name ending in a dot refused", () -> TargetPaths.resolve(base, "src./a.txt"), "src./a.txt");
        if (Files.exists(base.resolve("GIT~1"))) {
            refuses("8.3 short name of .git refused", () -> TargetPaths.resolve(base, "GIT~1/config"), "GIT~1/config", ".git");
            refuses("8.3 short name of .git refused (new file)", () -> TargetPaths.resolve(base, "GIT~1/hooks/x"), ".git");
            refuses("8.3 short name of .git in lower case refused", () -> TargetPaths.resolve(base, "git~1/config"), ".git");
            refuses("directory listing alone refuses GIT~1 as .git (no toRealPath)",
                    () -> OnDiskNames.require(base, "GIT~1/hooks/x"), "GIT~1/hooks/x", ".git metadata (real spelling .git)");
            gitByShortNamePlan(base);
        } else {
            Links.skip("8.3 short name of .git refused", "this volume has no short name GIT~1");
        }
    }

    /** A patch that adds a hook and changes the config through {@code GIT~1}: plan refuses it, nothing is written. */
    private static void gitByShortNamePlan(Path base) throws Exception {
        String patch = "diff --git a/GIT~1/hooks/post-checkout b/GIT~1/hooks/post-checkout\nnew file mode 100755\n"
                + "--- /dev/null\n+++ b/GIT~1/hooks/post-checkout\n@@ -0,0 +1 @@\n+echo hook\n"
                + "diff --git a/GIT~1/config b/GIT~1/config\n--- a/GIT~1/config\n+++ b/GIT~1/config\n"
                + "@@ -1 +1 @@\n-[core]\n+[hook]\n";
        Path file = Fixtures.write(base.resolveSibling("git83.diff"), patch);
        refuses("plan refuses a patch that writes into .git through GIT~1",
                () -> CodePatch.plan(base, base.resolve("target/patch"), List.of(file)), "GIT~1/hooks/post-checkout", ".git");
        check(".git unchanged by the refused plan", !Files.exists(base.resolve(".git/hooks"))
                && Files.readString(base.resolve(".git/config")).equals("[core]\n"));
    }

    /** A directory link inside the tree that points at {@code .git}: the real location decides. */
    private static void gitByLink(Path base, String name, boolean junction) throws Exception {
        Path link = base.resolve(name);
        Path git = base.resolve(".git");
        String what = (junction ? "junction" : "symbolic link") + " into .git refused";
        if (!(junction ? Links.junction(link, git) : Links.symbolic(link, git))) {
            Links.skip(what, "cannot create one here");
            return;
        }
        refuses(what + " (existing file)", () -> TargetPaths.resolve(base, name + "/config"), name + "/config", ".git");
        refuses(what + " (new file)", () -> TargetPaths.resolve(base, name + "/hooks/new"), ".git");
        refuses(what + " (other case)", () -> TargetPaths.resolve(base, name + "/CONFIG"), ".git");
        Files.delete(link);
    }

    private static void controlCharacters(Path base) throws Exception {
        for (String path : List.of("src/a\tb.txt", "src/a\rb.txt", "src/a\u007fb.txt", "src/\u0001.txt")) {
            String shown = ByteLines.show(path.getBytes(StandardCharsets.UTF_8));
            refuses("control character refused: " + shown, () -> TargetPaths.resolve(base, path), "control character");
        }
        Path patch = Fixtures.write(base.resolveSibling("tab.patch"), "diff --git a/x\ty b/x\ty\nnew file mode 100644\n");
        refuses("plan refuses a tab in a path (RECEIPT.tsv stays readable)",
                () -> CodePatch.plan(base, base.resolve("target/patch"), List.of(patch)), "control character");
    }

    /** Windows cannot hold these names: refusals there (not InvalidPathException); ordinary names on POSIX. */
    private static void windowsNames(Path base) throws Exception {
        List<String> names = List.of("src/a|b.txt", "src/a<b.txt", "src/q?.txt", "src/NUL", "src/com1.txt", "src/a.txt ");
        for (String name : names) {
            if (Scratch.windows() || name.endsWith(" ")) {
                refuses("Windows name refused: '" + name + "'", () -> TargetPaths.resolve(base, name), name);
            } else {
                check("name allowed on POSIX: " + name, TargetPaths.resolve(base, name).startsWith(base));
            }
        }
    }
}
