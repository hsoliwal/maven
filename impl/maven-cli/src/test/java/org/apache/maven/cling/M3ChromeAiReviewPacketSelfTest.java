// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Hitesh Soliwal and contributors
package org.apache.maven.cling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Direct Java 17 acceptance controls; no Chrome model or GitHub call required. */
public final class M3ChromeAiReviewPacketSelfTest {
    private static int checked;
    private static final String BASE = "a".repeat(40);
    private static final String HEAD = "b".repeat(40);
    private M3ChromeAiReviewPacketSelfTest() { }

    private static void require(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checked++;
    }

    private static void throwsIo(String name, Throwing operation) throws Exception {
        try {
            operation.run();
            throw new AssertionError("not refused: " + name);
        } catch (IOException expected) {
            checked++;
        }
    }

    @FunctionalInterface private interface Throwing { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("m3-chrome-review-");
        Files.createDirectory(root.resolve(".m3"));
        String text = "From " + HEAD + " Mon Sep 17 00:00:00 2001\n" +
                "diff --git a/Foo.java b/Foo.java\n--- a/Foo.java\n+++ b/Foo.java\n" +
                "@@ -1 +1 @@\n-\"before\"\n+\"after\"\n";
        byte[] patch = text.getBytes(StandardCharsets.UTF_8);
        String sha = M3GitHubPatchBootstrap.sha256(patch);
        Path receipt = M3ChromeAiReviewPacket.write(root, "hsoliwal/M3jdk21", 1621,
                BASE, HEAD, sha, patch);
        String contents = Files.readString(receipt);
        require(receipt.getParent().equals(root.resolve(".m3/patch-ai-reviews")), "receipt stored below project .m3");
        require(contents.contains("\"schema\":\"M3-CHROME-HUNK-REVIEW/1\""), "schema");
        require(contents.contains("\"patchSha256\":\"" + sha + "\""), "pin");
        require(contents.contains("\"mutationAuthorized\":false"), "no mutation authority");
        require(contents.contains("\\\"after\\\""), "escaped patch lines");
        require(contents.contains("\\n"), "escaped newlines");
        throwsIo("immutable packet", () -> M3ChromeAiReviewPacket.write(root,
                "hsoliwal/M3jdk21", 1621, BASE, HEAD, sha, patch));
        throwsIo("invalid patch digest", () -> M3ChromeAiReviewPacket.write(root,
                "hsoliwal/M3jdk21", 1622, BASE, HEAD, "c".repeat(64), patch));
        throwsIo("excessively large patch", () -> M3ChromeAiReviewPacket.write(root,
                "hsoliwal/M3jdk21", 1622, BASE, HEAD,
                M3GitHubPatchBootstrap.sha256(new byte[21000]), new byte[21000]));
        throwsIo("untrusted UTF8", () -> M3ChromeAiReviewPacket.write(root,
                "hsoliwal/M3jdk21", 1622, BASE, HEAD,
                M3GitHubPatchBootstrap.sha256(new byte[]{(byte) 0xff}),
                new byte[]{(byte) 0xff}));
        throwsIo("invalid repository", () -> M3ChromeAiReviewPacket.write(root,
                "../invalid", 1622, BASE, HEAD, sha, patch));
        Path missing = Files.createTempDirectory("m3-chrome-no-config-");
        throwsIo("missing source custody", () -> M3ChromeAiReviewPacket.write(missing,
                "hsoliwal/M3jdk21", 1621, BASE, HEAD, sha, patch));
        Path symlink = Files.createTempDirectory("m3-chrome-link-");
        Files.createSymbolicLink(symlink.resolve(".m3"), root.resolve(".m3"));
        throwsIo("symlinked .m3 refuses", () -> M3ChromeAiReviewPacket.write(symlink,
                "hsoliwal/M3jdk21", 1621, BASE, HEAD, sha, patch));
        require(M3GitHubPatchBootstrap.setting(new String[]{"-Dm3.chrome.ai.review=true"},
                "m3.chrome.ai.review"), "explicit CLI option");
        require(!M3GitHubPatchBootstrap.setting(new String[]{"verify"},
                "m3.chrome.ai.review"), "default disabled");
        System.out.println("M3_CHROME_AI_REVIEW_PACKET_PASS " + checked);
    }
}
