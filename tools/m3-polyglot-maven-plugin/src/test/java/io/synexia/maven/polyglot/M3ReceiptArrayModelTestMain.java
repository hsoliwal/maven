/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Deterministic reference oracle for the compact, growing M3 receipt lanes.
 * TreeMap exists only in the test: no per-entry map/node enters production.
 */
public final class M3ReceiptArrayModelTestMain {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final long SEED = 0x65A123B5L;
    private static int assertions;

    private M3ReceiptArrayModelTestMain() {}

    public static void main(String[] ignored) throws Exception {
        Path parent = Files.createTempDirectory("m3-array-lanes-model-");
        try {
            Path module = Files.createDirectories(parent.resolve("module"));
            TreeMap<String, String[]> expected = new TreeMap<>();
            Random random = new Random(SEED);
            try (M3DbModuleStore db = M3DbModuleStore.open(module, true, ignoredLine -> {})) {
                for (int round = 0; round < 9; round++) {
                    TreeMap<String, String[]> pending = new TreeMap<>();
                    for (int n = 0; n < 400; n++) {
                        String name = key(random.nextInt(2400));
                        String input = random.nextBoolean() ? A : B;
                        String output = random.nextBoolean() ? A : B;
                        db.stage(name, input, output);
                        pending.put(name, new String[] {input, output});
                    }
                    // Uncommitted writes must not be visible, even when they
                    // replace an older visible identity.
                    for (Map.Entry<String, String[]> entry : pending.entrySet()) {
                        require(Arrays.equals(db.lookup(entry.getKey()), expected.get(entry.getKey())),
                                "uncommitted stage cannot change previously published lookup");
                    }
                    db.commitStaged();
                    expected.putAll(pending);
                    compare(db, expected);

                    List<String> removed = new ArrayList<>();
                    for (int n = 0; n < 130; n++) {
                        String name = key(random.nextInt(2400));
                        removed.add(name);
                        if (n % 3 == 0) removed.add(name); // duplicate revocation
                    }
                    db.invalidate(removed);
                    for (String name : removed) expected.remove(name);
                    compare(db, expected);
                    if (round % 3 == 2) {
                        String prefix = "n00";
                        db.invalidatePrefix(prefix);
                        expected.keySet().removeIf(k -> k.startsWith(prefix));
                        compare(db, expected);
                    }
                }
                // An immediate write remains an atomic single-record commit.
                db.record("n99999", B, A);
                expected.put("n99999", new String[] {B, A});
                compare(db, expected);
            }
            try (M3DbModuleStore db = M3DbModuleStore.open(module, true, ignoredLine -> {})) {
                compare(db, expected);
                db.invalidateAll();
                require(db.lookup("n99999") == null, "invalidateAll clears the packed index");
            }
            try (M3DbModuleStore db = M3DbModuleStore.open(module, true, ignoredLine -> {})) {
                require(db.lookup("n99999") == null, "global invalidation survives reopen");
            }
            System.out.println("PASS: " + assertions + " deterministic growing-array receipt oracle assertions");
        } finally {
            try (var walk = Files.walk(parent)) {
                for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static String key(int value) {
        return String.format(java.util.Locale.ROOT, "n%05d", value);
    }

    private static void compare(M3DbModuleStore db, TreeMap<String, String[]> expected) {
        for (int i = 0; i < 2400; i++) {
            String name = key(i);
            require(Arrays.equals(expected.get(name), db.lookup(name)), "index mismatch: " + name);
        }
        require(db.lookup("n99999") == null || expected.containsKey("n99999"),
                "unexpected success outside expected keys");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        assertions++;
    }
}
