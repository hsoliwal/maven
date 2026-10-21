// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Hitesh Soliwal and contributors
package org.apache.maven.cling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Self-contained Java 17 test runner for deterministic tests without Maven dependency downloading. */
public final class M3GitHubPatchBootstrapSelfTest {
    private static int tests;
    private static void check(boolean condition, String reason) { ++tests; if (!condition) throw new AssertionError(reason); }
    private static void rejects(String name, Throwing test) throws Exception {
        ++tests; try {test.run();throw new AssertionError("accepted: " + name);} catch (IOException expected) { /* refusal */ }
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        String head = "a".repeat(40);
        String patch = "From " + head + " Mon Sep 17 00:00:00 2001\nSubject: [PATCH] foo\n---\n"
                + "diff --git a/src/Foo.java b/src/Foo.java\nindex aaaa..bbbb 100644\n"
                + "--- a/src/Foo.java\n+++ b/src/Foo.java\n@@ -1 +1 @@\n-old\n+new\n";
        var good = M3GitHubPatchBootstrap.inspectMbox(patch.getBytes(StandardCharsets.UTF_8), head);
        check(good.commits() == 1 && good.fileSections() == 1 && good.finalHead().equals(head), "sealed mbox");
        rejects("head-drift", () -> M3GitHubPatchBootstrap.inspectMbox(patch.getBytes(StandardCharsets.UTF_8), "b".repeat(40)));
        rejects("binary", () -> M3GitHubPatchBootstrap.inspectMbox((patch + "GIT binary patch\n").getBytes(StandardCharsets.UTF_8), head));
        rejects("delete", () -> M3GitHubPatchBootstrap.inspectMbox((patch + "deleted file mode 100644\n").getBytes(StandardCharsets.UTF_8), head));
        rejects("rename", () -> M3GitHubPatchBootstrap.inspectMbox((patch + "rename from src/Foo.java\n").getBytes(StandardCharsets.UTF_8), head));
        rejects("symlink", () -> M3GitHubPatchBootstrap.inspectMbox(patch.replace("index aaaa..bbbb 100644", "new file mode 120000").getBytes(StandardCharsets.UTF_8), head));
        rejects("duplicate-commit", () -> M3GitHubPatchBootstrap.inspectMbox((patch + patch).getBytes(StandardCharsets.UTF_8), head));
        rejects("traversal", () -> M3GitHubPatchBootstrap.inspectMbox(patch.replace("src/Foo.java", "../secret").getBytes(StandardCharsets.UTF_8), head));
        check(M3GitHubPatchBootstrap.safePath("src/Foo.java"), "safe path accepted");
        check(!M3GitHubPatchBootstrap.safePath("a/../b"), "traversal rejected");
        check(!M3GitHubPatchBootstrap.safePath(".git/config"), "git config rejected");
        check(!M3GitHubPatchBootstrap.safePath("c:/windows"), "windows drive rejected");
        check(!M3GitHubPatchBootstrap.setting(new String[]{"-Dm3.patch.apply=false"}, "m3.patch.apply"), "false flag");
        check(M3GitHubPatchBootstrap.setting(new String[]{"-Dm3.patch.apply=true"}, "m3.patch.apply"), "explicit apply");
        Properties valid = new Properties();
        valid.setProperty("repository", "hsoliwal/M3jdk21");
        valid.setProperty("pr", "1621"); valid.setProperty("head", head);
        valid.setProperty("base", "b".repeat(40)); valid.setProperty("patchSha256", "c".repeat(64));
        var plan = M3GitHubPatchBootstrap.Plan.from(valid);
        check(plan.patchUri().toString().equals("https://github.com/hsoliwal/M3jdk21/pull/1621.patch"), "immutable origin");
        valid.setProperty("repository", "evil/../target");
        rejects("invalid repo", () -> M3GitHubPatchBootstrap.Plan.from(valid));
        Path temp = Files.createTempDirectory("m3-bootstrap-test-");
        try {
            Path nested = Files.createDirectories(temp.resolve("child/sub"));
            check(M3GitHubPatchBootstrap.findManifest(nested) == null, "no manifest noop");
            M3GitHubPatchBootstrap.beforeMaven(new String[]{"verify"});
            Files.createDirectories(temp.resolve(".m3"));
            Files.writeString(temp.resolve(".m3/patch-boot.properties"), "autoCheck=false\n");
            check(M3GitHubPatchBootstrap.findManifest(nested).equals(temp.resolve(".m3/patch-boot.properties")), "ancestor manifest");
        } finally {
            Files.walk(temp).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {try {Files.deleteIfExists(p);} catch (IOException e) {throw new RuntimeException(e);} });
        }
        check(M3GitHubPatchBootstrap.sha256("abc".getBytes(StandardCharsets.UTF_8)).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "SHA-256 known answer");
        System.out.println("PASS " + tests + " Maven GitHub patch bootstrap checks");
    }
}
