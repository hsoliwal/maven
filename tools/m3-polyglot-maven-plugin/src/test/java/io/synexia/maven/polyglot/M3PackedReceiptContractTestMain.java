/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import jdk.m3.indexdb.M3IndexDB;

/**
 * Exact wire-compatibility and structural tests for packed M3 receipt hash
 * lanes. A TreeMap appears only in this oracle; the production state retains
 * sorted names and flat primitive arrays with zero per-hash objects.
 */
public final class M3PackedReceiptContractTestMain {
    private static int checks;
    private M3PackedReceiptContractTestMain() {}

    public static void main(String[] ignored) throws Exception {
        Path temp = Files.createTempDirectory("m3-packed-receipt-");
        try {
            primitiveLayout();
            wireFormat(temp.resolve("wire"));
            model(temp.resolve("model"));
            System.out.println("PASS: " + checks + " packed SHA-256 lane assertions");
        } finally {
            try (var entries = Files.walk(temp)) {
                for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void primitiveLayout() throws Exception {
        Class<?> owner = M3DbModuleStore.class;
        for (String name : List.of("inputs", "outputs", "pendingInputs", "pendingOutputs")) {
            var field = owner.getDeclaredField(name);
            check(field.getType() == byte[].class,
                    "SHA column " + name + " must be a primitive packed array");
        }
        check(owner.getDeclaredField("names").getType() == String[].class,
                "names retain their sorted string identity");
    }

    private static void wireFormat(Path module) throws Exception {
        Files.createDirectories(module);
        String first = digest("input-1"), second = digest("output-1");
        String third = digest("input-2"), fourth = digest("output-2");
        try (M3DbModuleStore state = M3DbModuleStore.open(module, true, ignored -> {})) {
            state.stage("compile", first, second);
            state.stage("resources", third, fourth);
            state.commitStaged();
        }

        byte[] actual;
        try (FileSystem zip = FileSystems.newFileSystem(module.resolve(M3DbModuleStore.FILE), Map.of());
                M3IndexDB db = M3IndexDB.open(zip.getPath("/"))) {
            actual = db.requireArtifact("successful-tasks").payload();
        }

        byte[] expected;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x4d334d44);
            out.writeInt(2);
            out.writeUTF(module.toRealPath().toString());
            out.writeUTF(Files.readAllLines(module.resolve(M3DbModuleStore.MARKER)).get(2));
            out.writeInt(2);
            out.writeUTF("compile");
            out.write(HexFormat.of().parseHex(first));
            out.write(HexFormat.of().parseHex(second));
            out.writeUTF("resources");
            out.write(HexFormat.of().parseHex(third));
            out.write(HexFormat.of().parseHex(fourth));
            out.writeInt(0);
            out.flush();
            byte[] body = bytes.toByteArray();
            out.write(MessageDigest.getInstance("SHA-256").digest(body));
            out.flush();
            expected = bytes.toByteArray();
        }
        check(Arrays.equals(actual, expected), "packed SHA lanes retain every byte of the sealed v2 schema");
        try (M3DbModuleStore reopened = M3DbModuleStore.open(module, true, ignored -> {})) {
            check(Arrays.equals(reopened.lookup("compile"), new String[] {first, second}),
                    "sealed v2 receipt decodes without digest allocation errors");
            check(Arrays.equals(reopened.lookup("resources"), new String[] {third, fourth}),
                    "second sealed v2 receipt retains order and exact bits");
            check(reopened.matchesInput("compile", first)
                            && reopened.matches("compile", first, second),
                    "native packed hit works without hex projections");
            check(!reopened.matches("compile", first, fourth)
                            && !reopened.matchesInput("compile", first.toUpperCase(java.util.Locale.ROOT))
                            && !reopened.matches("compile", first, null)
                            && !reopened.matches("missing", first, second),
                    "packed comparison preserves exact lower-case/absence semantics");
            String[] detached = reopened.lookup("compile");
            detached[0] = "0".repeat(64);
            check(Arrays.equals(reopened.lookup("compile"), new String[] {first, second}),
                    "lookup projects fresh boundary Strings without exposing internal slots");
        }
        try (ModuleStateDb facade = ModuleStateDb.open(module, "embedded")) {
            check(facade.matchesInput("compile", first)
                            && facade.hit("compile", first, second),
                    "public facade uses the compact SHA matcher");
            check(!facade.hit("compile", first, fourth)
                            && !facade.hit("compile", first, second.toUpperCase(java.util.Locale.ROOT)),
                    "public hit remains exact, not case-folded");
        }
    }

    private static void model(Path module) throws Exception {
        Files.createDirectories(module);
        TreeMap<String, String[]> expected = new TreeMap<>();
        Random random = new Random(0x5A6DB45EL);
        try (M3DbModuleStore state = M3DbModuleStore.open(module, true, ignored -> {})) {
            for (int round = 0; round < 8; round++) {
                TreeMap<String, String[]> batch = new TreeMap<>();
                for (int i = 0; i < 350; i++) {
                    String name = String.format(java.util.Locale.ROOT, "task-%04d", random.nextInt(3000));
                    String input = digest("in:" + round + ":" + i);
                    String output = digest("out:" + round + ":" + i);
                    state.stage(name, input, output);
                    batch.put(name, new String[] {input, output});
                }
                state.commitStaged();
                expected.putAll(batch);
                if (round % 2 == 0) {
                    List<String> excluded = new ArrayList<>();
                    for (int i = 0; i < 80; i++) {
                        excluded.add(String.format(java.util.Locale.ROOT,
                                "task-%04d", random.nextInt(3000)));
                    }
                    state.invalidate(excluded);
                    excluded.forEach(expected::remove);
                }
                compare(state, expected);
            }
        }
        try (M3DbModuleStore reopened = M3DbModuleStore.open(module, true, ignored -> {})) {
            compare(reopened, expected);
            reopened.invalidatePrefix("task-00");
            expected.keySet().removeIf(key -> key.startsWith("task-00"));
            compare(reopened, expected);
        }
        try (M3DbModuleStore reopened = M3DbModuleStore.open(module, true, ignored -> {})) {
            compare(reopened, expected);
        }
    }

    private static void compare(M3DbModuleStore state, TreeMap<String, String[]> expected) {
        for (int i = 0; i < 3000; i++) {
            String name = String.format(java.util.Locale.ROOT, "task-%04d", i);
            check(Arrays.equals(state.lookup(name), expected.get(name)),
                    "M3 packed lane failed oracle for " + name);
        }
    }

    private static String digest(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
        checks++;
    }
}
