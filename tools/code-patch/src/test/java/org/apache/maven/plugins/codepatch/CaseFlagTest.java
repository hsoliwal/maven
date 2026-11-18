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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NTFS keeps the case sensitivity per directory ({@code fsutil file setCaseSensitiveInfo}, WSL {@code case=dir}). In a
 * case-sensitive directory the path checks still hold (an 8.3 short name resolves there only in upper case). With a
 * case-sensitive base and a case-insensitive subdirectory, two new names differing only in case are refused in the
 * subdirectory; in the reverse layout they are two files. Windows only; skipped where fsutil cannot set the flag.
 */
final class CaseFlagTest {
    private static final String WHAT = "per-directory case sensitivity (NTFS)";

    private CaseFlagTest() { }

    static void run(Path dir) throws Exception {
        if (!Scratch.windows() || !flag(Files.createDirectories(dir.resolve("probe")), true)) {
            Links.skip(WHAT, Scratch.windows() ? "fsutil cannot set the case-sensitive flag here" : "not Windows");
            return;
        }
        Path sensitive = Files.createDirectories(dir.resolve("sensitive"));
        flag(sensitive, true);
        TargetPathsTest.run(sensitive.resolve("target-paths"));
        SpellingTest.run(sensitive.resolve("spelling"));
        CaseCollisionTest.run(sensitive.resolve("case-collision"));
        mixed(dir.resolve("mixed"), true);
        mixed(dir.resolve("reverse"), false);
    }

    /** The base has {@code baseFlag}, its subdirectory {@code d} the other one; {@code d} holds a file with letters. */
    private static void mixed(Path dir, boolean baseFlag) throws Exception {
        Path base = Files.createDirectories(dir.resolve("tree"));
        flag(base, baseFlag);
        flag(Files.createDirectories(base.resolve("d")), !baseFlag);
        Fixtures.tree(base, Map.of("keep.txt", "k\n", "d/keep.txt", "k\n"));
        Path out = base.resolve("target/patch");
        Path patch = Fixtures.write(dir.resolve("pair.diff"), PlanTargetsTest.add("d/Foo.txt") + PlanTargetsTest.add("d/foo.txt"));
        if (baseFlag) {
            refuses("case-sensitive base, case-insensitive d: d/Foo.txt and d/foo.txt refused",
                    () -> CodePatch.plan(base, out, List.of(patch)), "d/foo.txt", "d/Foo.txt", "case");
        } else {
            cycle(base, out, patch);
        }
    }

    /** A case-insensitive base, a case-sensitive {@code d}: two files there, applied and rolled back. */
    private static void cycle(Path base, Path out, Path patch) throws Exception {
        Plan plan = CodePatch.plan(base, out, List.of(patch));
        check("case-insensitive base, case-sensitive d: d/Foo.txt and d/foo.txt are two files: " + plan.summary(),
                plan.rows().size() == 2);
        CodePatch.apply(base, out, List.of(patch), plan.id());
        check("apply wrote both files in d", Fixtures.snapshot(base).size() == 4);
        CodePatch.rollback(base, out, plan.id());
        check("rollback left d as before", Fixtures.snapshot(base).keySet().equals(Set.of("keep.txt", "d/keep.txt")));
    }

    /** Sets the case-sensitive flag of an empty directory; false when fsutil cannot. */
    static boolean flag(Path dir, boolean on) throws InterruptedException {
        try {
            Process fsutil = new ProcessBuilder("fsutil", "file", "setCaseSensitiveInfo", dir.toString(),
                    on ? "enable" : "disable").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return fsutil.waitFor() == 0;
        } catch (IOException e) {
            return false;
        }
    }
}
