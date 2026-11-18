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

import static org.apache.maven.plugins.codepatch.Checks.group;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs every check of the plugin as a main class (argument: a scratch directory, emptied first): the parser, plan and
 * apply/rollback on a tree, refusals, a patch series, verify, and the source checks (method length, decision table)
 * when the scratch directory is {@code target/code-patch-test} of the module. Exits with an error if any check fails.
 */
public final class CodePatchTest {
    private CodePatchTest() { }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "target/code-patch-test").toAbsolutePath();
        Scratch.clean(dir);
        parser();
        transactions(dir);
        paths(dir);
        sources(dir);
        if (Checks.failures() > 0) throw new AssertionError(Checks.failures() + " code patch checks failed");
        System.err.println("code patch: all checks passed");
    }

    private static void parser() {
        group("PatchParserTest", PatchParserTest::run);
        group("ParserEdgeTest", ParserEdgeTest::run);
        group("DroppedContentTest", DroppedContentTest::run);
        group("HunkApplierTest", HunkApplierTest::run);
    }

    private static void transactions(Path dir) {
        group("PlanApplyTest", () -> PlanApplyTest.run(dir.resolve("plan-apply")));
        group("SeriesTest", () -> SeriesTest.run(dir.resolve("series")));
        group("MailProseTest", () -> MailProseTest.run(dir.resolve("mail-prose")));
        group("RollbackResumeTest", () -> RollbackResumeTest.run(dir.resolve("resume")));
        group("ReadOnlyTest", () -> ReadOnlyTest.run(dir.resolve("read-only")));
        group("FailureMessageTest", () -> FailureMessageTest.run(dir.resolve("failures")));
        group("DurabilityTest", () -> DurabilityTest.run(dir.resolve("durability")));
        group("VerifyTest", () -> VerifyTest.run(dir.resolve("verify")));
        group("NoAtomsTest", () -> NoAtomsTest.run(dir.resolve("no-atoms")));
        group("ListingCacheTest", () -> ListingCacheTest.run(dir.resolve("listing-cache")));
    }

    private static void paths(Path dir) {
        group("RefusalTest", () -> RefusalTest.run(dir.resolve("refusals")));
        group("TargetPathsTest", () -> TargetPathsTest.run(dir.resolve("target-paths")));
        group("SpellingTest", () -> SpellingTest.run(dir.resolve("spelling")));
        group("PlanTargetsTest", () -> PlanTargetsTest.run(dir.resolve("plan-targets")));
        group("CaseCollisionTest", () -> CaseCollisionTest.run(dir.resolve("case-collision")));
        group("CaseFlagTest", () -> CaseFlagTest.run(dir.resolve("case-flag")));
        group("NtfsScratchTest", NtfsScratchTest::run);
    }

    /** The module directory is two levels above {@code target/code-patch-test}; elsewhere the source checks skip. */
    private static void sources(Path dir) {
        Path module = dir.getParent() == null ? dir : dir.getParent().getParent();
        if (module == null || !Files.isRegularFile(module.resolve("pom.xml"))) {
            Links.skip("source checks", "no module directory above " + dir);
            return;
        }
        group("MethodLengthTest", () -> MethodLengthTest.run(module));
        group("DecisionTableTest", () -> DecisionTableTest.run(module));
    }
}
