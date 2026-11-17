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
import java.util.Set;

/**
 * New targets whose names collide ignoring case ({@code d/Foo.txt} and {@code d/foo.txt}, or {@code NewDir/a.txt} and
 * {@code newdir/b.txt}) are one file or one directory on a case-insensitive volume: plan refuses them there, so apply
 * never stops halfway. On a case-sensitive volume they are different files and plan, apply and rollback them.
 */
final class CaseCollisionTest {
    private CaseCollisionTest() { }

    static void run(Path dir) throws Exception {
        Path base = Fixtures.tree(dir.resolve("tree"), Map.of("keep.txt", "k\n"));
        Path out = base.resolve("target/patch");
        String files = PlanTargetsTest.add("d/Foo.txt") + PlanTargetsTest.add("d/foo.txt");
        String dirs = PlanTargetsTest.add("NewDir/a.txt") + PlanTargetsTest.add("newdir/b.txt");
        check("names in different directories do not collide", plan(base, out, "apart.diff",
                PlanTargetsTest.add("d/Foo.txt") + PlanTargetsTest.add("e/foo.txt")).rows().size() == 2);
        if (Files.exists(base.resolve("KEEP.TXT"))) {
            insensitive(base, out, files, dirs);
        } else {
            Links.skip("names colliding ignoring case refused", "case-sensitive volume");
            sensitive(base, out, files, dirs);
        }
    }

    /** A case-insensitive volume: each pair names one file or one directory twice; plan refuses and writes nothing. */
    private static void insensitive(Path base, Path out, String files, String dirs) throws Exception {
        refuses("new files colliding ignoring case refused", () -> plan(base, out, "files.diff", files),
                "d/foo.txt", "d/Foo.txt", "case");
        refuses("new directories colliding ignoring case refused", () -> plan(base, out, "dirs.diff", dirs),
                "newdir", "NewDir", "case");
        check("nothing written by the refused plans", Fixtures.snapshot(base).keySet().equals(Set.of("keep.txt")));
    }

    /** A case-sensitive volume: both pairs are different files; apply and rollback restore the tree. */
    private static void sensitive(Path base, Path out, String files, String dirs) throws Exception {
        Plan plan = plan(base, out, "both.diff", files + dirs);
        check("names differing in case are different files here: " + plan.summary(), plan.rows().size() == 4);
        CodePatch.apply(base, out, List.of(base.resolveSibling("patches").resolve("both.diff")), plan.id());
        check("apply wrote four files", Fixtures.snapshot(base).size() == 5);
        CodePatch.rollback(base, out, plan.id());
        check("rollback left the tree as before", Fixtures.snapshot(base).keySet().equals(Set.of("keep.txt")));
    }

    private static Plan plan(Path base, Path out, String name, String patch) throws Exception {
        return CodePatch.plan(base, out, List.of(Fixtures.write(base.resolveSibling("patches").resolve(name), patch)));
    }
}
