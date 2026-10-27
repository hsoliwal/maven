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
package org.apache.maven.cling;

// Copyright 2026 Hitesh Soliwal and contributors

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.Properties;

/** Self-contained Java 17 test runner for deterministic tests without Maven dependency downloading. */
public final class M3GitHubPatchBootstrapSelfTest {
    private static int tests;

    private static void check(boolean condition, String reason) {
        ++tests;
        if (!condition) {
            throw new AssertionError(reason);
        }
    }

    private static void rejects(String name, Throwing test) throws Exception {
        ++tests;
        try {
            test.run();
            throw new AssertionError("accepted: " + name);
        } catch (IOException expected) {
            // Expected fail-closed refusal.
        }
    }

    @FunctionalInterface
    private interface Throwing {
        void run() throws Exception;
    }
    /** An actual two-commit Git mbox must replay to its final content, without network. */
    private static void patchCycleAgainstRealGit() throws Exception {
        Path root = Files.createTempDirectory("m3-pr-patch-git-");
        try {
            git(root, "init", "-q");
            git(root, "config", "user.email", "tester@example.org");
            git(root, "config", "user.name", "M3 Test");
            Path path = Files.createDirectories(root.resolve("src")).resolve("Foo.java");
            Files.writeString(path, "one\ntwo\n");
            git(root, "add", ".");
            git(root, "commit", "-qm", "baseline");
            String base = git(root, "rev-parse", "HEAD").strip();
            Files.writeString(path, "one\ntwo\nthree\n");
            git(root, "commit", "-qam", "first");
            Files.writeString(path, "one\ntwo\nthree\nfour\n");
            git(root, "commit", "-qam", "second");
            String head = git(root, "rev-parse", "HEAD").strip();
            byte[] mbox =
                    git(root, "format-patch", "--stdout", base + ".." + head).getBytes(StandardCharsets.UTF_8);
            var receipt = M3GitHubPatchBootstrap.inspectMbox(mbox, head);
            check(receipt.commits() == 2 && receipt.fileSections() == 2, "actual Git multi-commit mbox");
            Path patchFile = root.resolve("candidate.patch");
            Files.write(patchFile, mbox);
            git(root, "checkout", "-q", base);
            git(root, "apply", "--check", "--index", patchFile.toString());
            git(root, "apply", "--index", patchFile.toString());
            check(Files.readString(path).equals("one\ntwo\nthree\nfour\n"), "actual Git indexed patch application");
        } finally {
            deleteFixtureTree(root);
        }
    }

    /**
     * Git marks loose objects read-only on some Windows filesystems. The fixture owns
     * this private directory, so clear that attribute before deleting. A short bounded
     * retry permits Windows antivirus/file indexing handles to close; persistent
     * failures remain fatal rather than being hidden or reported as passing tests.
     */
    private static void deleteFixtureTree(Path root) throws IOException {
        try (var entries = Files.walk(root)) {
            for (Path path : entries.sorted(java.util.Comparator.reverseOrder()).toList()) {
                deleteFixturePath(path);
            }
        }
    }

    private static void deleteFixturePath(Path path) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            try {
                DosFileAttributeView dos =
                        Files.getFileAttributeView(path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (dos != null && dos.readAttributes().isReadOnly()) {
                    dos.setReadOnly(false);
                }
                Files.deleteIfExists(path);
                return;
            } catch (NoSuchFileException alreadyDeleted) {
                return;
            } catch (AccessDeniedException locked) {
                last = locked;
                if (attempt < 7) {
                    try {
                        Thread.sleep(25L << attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("M3_GIT_FIXTURE_CLEANUP_INTERRUPTED", interrupted);
                    }
                }
            }
        }
        throw last;
    }

    private static void checkReadOnlyGitFixtureCleanup() throws Exception {
        Path root = Files.createTempDirectory("m3-pr-patch-readonly-git-");
        try {
            Path object = Files.createDirectories(root.resolve(".git/objects/aa")).resolve("loose-object");
            Files.writeString(object, "fixture");
            DosFileAttributeView dos =
                    Files.getFileAttributeView(object, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (dos != null) {
                dos.setReadOnly(true);
            }
            deleteFixtureTree(root);
            check(!Files.exists(root), "Git fixture removes a read-only loose object");
        } finally {
            if (Files.exists(root)) {
                deleteFixtureTree(root);
            }
        }
    }

    private static String git(Path directory, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        return new String(gitBytes(directory, command), StandardCharsets.UTF_8);
    }

    private static byte[] gitBytes(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        byte[] result = process.getInputStream().readAllBytes();
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IOException("Git fixture failed: " + String.join(" ", command));
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        String head = "a".repeat(40);
        String patch = "From " + head + " Mon Sep 17 00:00:00 2001\nSubject: [PATCH] foo\n---\n"
                + "diff --git a/src/Foo.java b/src/Foo.java\nindex aaaa..bbbb 100644\n"
                + "--- a/src/Foo.java\n+++ b/src/Foo.java\n@@ -1 +1 @@\n-old\n+new\n";
        var good = M3GitHubPatchBootstrap.inspectMbox(patch.getBytes(StandardCharsets.UTF_8), head);
        check(
                good.commits() == 1
                        && good.fileSections() == 1
                        && good.finalHead().equals(head),
                "sealed mbox");
        rejects(
                "head-drift",
                () -> M3GitHubPatchBootstrap.inspectMbox(patch.getBytes(StandardCharsets.UTF_8), "b".repeat(40)));
        rejects(
                "binary",
                () -> M3GitHubPatchBootstrap.inspectMbox(
                        (patch + "GIT binary patch\n").getBytes(StandardCharsets.UTF_8), head));
        rejects(
                "delete",
                () -> M3GitHubPatchBootstrap.inspectMbox(
                        (patch + "deleted file mode 100644\n").getBytes(StandardCharsets.UTF_8), head));
        rejects(
                "rename",
                () -> M3GitHubPatchBootstrap.inspectMbox(
                        (patch + "rename from src/Foo.java\n").getBytes(StandardCharsets.UTF_8), head));
        rejects(
                "symlink",
                () -> M3GitHubPatchBootstrap.inspectMbox(
                        patch.replace("index aaaa..bbbb 100644", "new file mode 120000")
                                .getBytes(StandardCharsets.UTF_8),
                        head));
        rejects(
                "duplicate-commit",
                () -> M3GitHubPatchBootstrap.inspectMbox((patch + patch).getBytes(StandardCharsets.UTF_8), head));
        rejects(
                "traversal",
                () -> M3GitHubPatchBootstrap.inspectMbox(
                        patch.replace("src/Foo.java", "../secret").getBytes(StandardCharsets.UTF_8), head));
        check(M3GitHubPatchBootstrap.safePath("src/Foo.java"), "safe path accepted");
        check(!M3GitHubPatchBootstrap.safePath("a/../b"), "traversal rejected");
        check(!M3GitHubPatchBootstrap.safePath(".git/config"), "git config rejected");
        check(!M3GitHubPatchBootstrap.safePath("c:/windows"), "windows drive rejected");
        check(!M3GitHubPatchBootstrap.setting(new String[] {"-Dm3.patch.apply=false"}, "m3.patch.apply"), "false flag");
        check(
                M3GitHubPatchBootstrap.setting(new String[] {"-Dm3.patch.apply=true"}, "m3.patch.apply"),
                "explicit apply");
        Properties valid = new Properties();
        valid.setProperty("repository", "hsoliwal/M3jdk21");
        valid.setProperty("pr", "1621");
        valid.setProperty("head", head);
        valid.setProperty("base", "b".repeat(40));
        valid.setProperty("patchSha256", "c".repeat(64));
        var plan = M3GitHubPatchBootstrap.Plan.from(valid);
        check(
                plan.patchUri().toString().equals("https://github.com/hsoliwal/M3jdk21/pull/1621.patch"),
                "immutable origin");
        valid.setProperty("repository", "evil/../target");
        rejects("invalid repo", () -> M3GitHubPatchBootstrap.Plan.from(valid));
        Path temp = Files.createTempDirectory("m3-bootstrap-test-");
        try {
            Path nested = Files.createDirectories(temp.resolve("child/sub"));
            check(M3GitHubPatchBootstrap.findManifest(nested) == null, "no manifest noop");
            M3GitHubPatchBootstrap.beforeMaven(new String[] {"verify"});
            Files.createDirectories(temp.resolve(".m3"));
            Files.writeString(temp.resolve(".m3/patch-boot.properties"), "autoCheck=false\n");
            check(
                    M3GitHubPatchBootstrap.findManifest(nested).equals(temp.resolve(".m3/patch-boot.properties")),
                    "ancestor manifest");
        } finally {
            deleteFixtureTree(temp);
        }
        checkReadOnlyGitFixtureCleanup();
        patchCycleAgainstRealGit();
        check(
                M3GitHubPatchBootstrap.sha256("abc".getBytes(StandardCharsets.UTF_8))
                        .equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
                "SHA-256 known answer");
        System.out.println("PASS " + tests + " Maven GitHub patch bootstrap checks");
    }
}
