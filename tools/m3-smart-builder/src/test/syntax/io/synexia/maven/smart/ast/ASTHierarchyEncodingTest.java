// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Hitesh Soliwal and contributors.
package io.synexia.maven.smart.ast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/** Exact public-root witnesses for the accepted UTF-16 identifier domain. */
public final class ASTHierarchyEncodingTest {
    private static final String LEGACY_VALID_ROOTS = "646f2f287e6f5a8e991f4c7debf170e15fa6a4829e90715b97c28cf78f529380";
    private static int checks;

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 1 && arguments[0].equals("--fingerprint")) {
            System.out.println(validRootsFingerprint());
            return;
        }
        verify();
        System.out.println("PASS HierarchyEncoding checks=" + checks);
    }

    private static void verify() throws Exception {
        checks = 0;
        check(LEGACY_VALID_ROOTS.equals(validRootsFingerprint()),
                "all frozen well-formed UTF-8 hierarchy roots retain their original bytes");
        for (int scope = 0; scope < 5; scope++) {
            HashSet<String> roots = new HashSet<>();
            String question = root(scope, "?");
            roots.add(question);
            roots.add(root(scope, "\ufffd"));
            roots.add(root(scope, "\ud800\udc00"));
            roots.add(root(scope, "\udbff\udfff"));
            for (int codeUnit = 0xd800; codeUnit <= 0xdfff; codeUnit++) {
                String identifier = Character.toString((char) codeUnit);
                check(roots.add(root(scope, identifier)),
                        "isolated surrogate remains distinct at scope " + scope + ": " + codeUnit);
            }
            for (String identifier : List.of("\ud800x", "x\ud800", "\udc00x", "x\udc00",
                    "\ud800\ud800", "\udc00\udc00", "\ud800\ud800\udc00", "\udc00\ud800\udc00",
                    "\ud800\udc00\ud800", "\ud800\udc00\udc00", "\u0000\ud800", "\uffff\ud800",
                    "x".repeat(493) + "\ud800", "x".repeat(492) + "\ud800\udc00")) {
                check(roots.add(root(scope, identifier)), "malformed/valid boundary remains distinct");
            }
        }
    }

    private static String validRootsFingerprint() throws Exception {
        StringBuilder roots = new StringBuilder();
        for (String identifier : List.of("ascii", "caf\u00e9", "\u0939\u093f\u0928\u094d\u0926\u0940",
                "\u6f22\u5b57", "\ufffd", "\u00ff", "\ud83d\ude00", "\udbff\udfff",
                "x\u0000y", "x\tq", "x".repeat(493), "?")) {
            for (int scope = 0; scope < 5; scope++) roots.append(root(scope, identifier)).append('\n');
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(roots.toString().getBytes(StandardCharsets.US_ASCII)));
    }

    private static String root(int scope, String identifier) {
        String[] names = {"repository", "project", "module", "package", "File.java"};
        names[scope] = "id-" + identifier;
        var file = new ASTHierarchy.FileFact(names[1], names[2], names[3], names[4],
                "0".repeat(64), "1".repeat(64), ASTHierarchy.LIST,
                new int[] {1, 7, 3}, new byte[] {0, (byte) 0x80, (byte) 0xff});
        return ASTHierarchy.build(names[0], List.of(file)).rootSha256();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
