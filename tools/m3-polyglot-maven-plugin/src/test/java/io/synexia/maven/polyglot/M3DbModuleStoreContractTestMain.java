/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jdk.m3.indexdb.M3IndexDB;

/** Original-store persistence and corruption tests; also run by Maven verify. */
public final class M3DbModuleStoreContractTestMain {
    private static final String INPUT = "a".repeat(64);
    private static final String OUTPUT = "b".repeat(64);
    private static int checks;

    private M3DbModuleStoreContractTestMain() {}

    public static void main(String[] ignored) throws Exception {
        Path temporary = Files.createTempDirectory("m3-module-store-test-");
        try {
            persistsOneOriginalDatabase(temporary.resolve("persistent"));
            revokesBeforeMutation(temporary.resolve("revoked"));
            disabledRunsOnlyInvalidate(temporary.resolve("disabled"));
            refusesConcurrentOwners(temporary.resolve("locked"));
            bindsCanonicalModule(temporary.resolve("source"), temporary.resolve("destination"));
            recoversCorruptImages(temporary.resolve("corrupt"));
            refusesSignedInvalidSchemas(temporary.resolve("schema"));
            boundsCompressedAndExpandedInputs(temporary.resolve("limits"));
            boundsReceiptResidency(temporary.resolve("capacity"));
            refusesSymlinkPaths(temporary.resolve("links"));
            closesAndRejectsInvalidRecords(temporary.resolve("closed"));
            System.out.println("PASS: " + checks + " M3DB module-store contract assertions");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void persistsOneOriginalDatabase(Path root) throws Exception {
        Files.createDirectories(root);
        try (M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {})) {
            check(store.lookup("compile") == null, "new task must miss");
            check(!Files.exists(root.resolve(M3DbModuleStore.FILE)), "opening alone must not publish success");
            store.record("compile", INPUT, OUTPUT);
            store.record("resources", OUTPUT, INPUT);
            String[] receipt = store.lookup("compile");
            check(Arrays.equals(receipt, new String[] {INPUT, OUTPUT}), "input/output receipt");
            receipt[0] = OUTPUT;
            check(store.lookup("compile")[0].equals(INPUT), "returned lanes must be independent");
        }
        Path file = root.resolve(M3DbModuleStore.FILE);
        byte[] image = Files.readAllBytes(file);
        try (M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {})) {
            check(Arrays.equals(store.lookup("compile"), new String[] {INPUT, OUTPUT}), "reopen compile receipt");
            check(Arrays.equals(store.lookup("resources"), new String[] {OUTPUT, INPUT}), "reopen second receipt");
        }
        check(Arrays.equals(image, Files.readAllBytes(file)), "a lookup must not rewrite the image");
        try (FileSystem archive = FileSystems.newFileSystem(file, Map.of());
                M3IndexDB original = M3IndexDB.open(archive.getPath("/"))) {
            check(original.requireArtifact("successful-tasks").kind().equals("maven.polyglot.module-state"),
                    "published artifact belongs to original M3IndexDB");
        }
        try (var entries = Files.list(root.resolve(".m3"))) {
            check(entries.count() == 2, "one database and one stable lock, no task stamp files");
        }
    }

    private static void revokesBeforeMutation(Path root) throws Exception {
        Files.createDirectories(root);
        try (M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {})) {
            for (String name : List.of("compile", "package", "resources")) store.record(name, INPUT, OUTPUT);
            store.invalidate(List.of("compile", "package", "absent"));
            check(store.lookup("compile") == null && store.lookup("package") == null, "affected tasks revoked");
            check(store.lookup("resources") != null, "independent receipt retained");
        }
        try (M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {})) {
            check(store.lookup("compile") == null && store.lookup("package") == null, "revocation survives close");
            check(store.lookup("resources") != null, "independent receipt survives close");
            store.invalidate("resources");
        }
        try (M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {})) {
            check(store.lookup("resources") == null, "single-task revocation persisted");
        }
    }

    private static void disabledRunsOnlyInvalidate(Path root) throws Exception {
        Files.createDirectories(root);
        try (M3DbModuleStore store = M3DbModuleStore.open(root, false, ignored -> {})) {
            store.invalidateAll();
            store.record("compile", INPUT, OUTPUT);
        }
        check(!Files.exists(root.resolve(M3DbModuleStore.FILE)), "disabled run creates no database");
        check(Files.isRegularFile(root.resolve(M3DbModuleStore.LOCK)), "disabled run still coordinates execution");
        try (M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {})) {
            store.record("compile", INPUT, OUTPUT);
        }
        try (M3DbModuleStore store = M3DbModuleStore.open(root, false, ignored -> {})) {
            store.invalidateAll();
            store.record("compile", OUTPUT, INPUT);
        }
        try (M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {})) {
            check(store.lookup("compile") == null, "nonincremental execution revokes without new success");
        }
    }

    private static void refusesConcurrentOwners(Path root) throws Exception {
        Files.createDirectories(root);
        try (M3DbModuleStore first = M3DbModuleStore.open(root, true, ignored -> {})) {
            check(first.lookup("compile") == null, "first owner acquired");
            expectIo(() -> {
                try (M3DbModuleStore second = M3DbModuleStore.open(root, true, ignored -> {})) {
                    second.lookup("compile");
                }
            });
            expectIo(() -> {
                try (M3DbModuleStore second = M3DbModuleStore.open(root, false, ignored -> {})) {
                    second.lookup("compile");
                }
            });
        }
        try (M3DbModuleStore next = M3DbModuleStore.open(root, true, ignored -> {})) {
            next.record("compile", INPUT, OUTPUT);
            check(next.lookup("compile") != null, "closed owner releases lock");
        }
    }

    private static void bindsCanonicalModule(Path source, Path destination) throws Exception {
        Files.createDirectories(source);
        Files.createDirectories(destination.resolve(".m3"));
        try (M3DbModuleStore store = M3DbModuleStore.open(source, true, ignored -> {})) {
            store.record("compile", INPUT, OUTPUT);
        }
        Files.copy(source.resolve(M3DbModuleStore.FILE), destination.resolve(M3DbModuleStore.FILE));
        try (M3DbModuleStore store = M3DbModuleStore.open(destination, true, ignored -> {})) {
            check(store.lookup("compile") == null, "copied module state cannot authorize another module");
            store.invalidate("compile");
            store.record("compile", OUTPUT, INPUT);
        }
        try (M3DbModuleStore store = M3DbModuleStore.open(source, true, ignored -> {})) {
            check(store.lookup("compile")[0].equals(INPUT), "separate module state remains independent");
        }
    }

    private static void recoversCorruptImages(Path root) throws Exception {
        Files.createDirectories(root.resolve(".m3"));
        Path file = root.resolve(M3DbModuleStore.FILE);
        Files.writeString(file, "incomplete database publication");
        List<String> messages = new ArrayList<>();
        try (M3DbModuleStore store = M3DbModuleStore.open(root, true, messages::add)) {
            check(store.lookup("compile") == null, "corrupt archive is a miss");
            check(!messages.isEmpty(), "corruption is reported");
            store.invalidate("compile");
            store.record("compile", INPUT, OUTPUT);
        }
        try (FileSystem archive = FileSystems.newFileSystem(file, Map.of());
                M3IndexDB original = M3IndexDB.open(archive.getPath("/"))) {
            String hash = original.requireArtifact("successful-tasks").contentSha256();
            Files.write(archive.getPath("/blobs/" + hash + ".blob"), new byte[] {1, 2, 3});
        }
        try (M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {})) {
            check(store.lookup("compile") == null, "original database digest mismatch is a miss");
            store.invalidateAll();
        }
        try (M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {})) {
            check(store.lookup("compile") == null, "corrupt record cannot reappear after revocation");
        }
    }

    private static void refusesSignedInvalidSchemas(Path root) throws Exception {
        Files.createDirectories(root.resolve(".m3"));
        byte[] valid = payload(root, 1, List.of("compile"), false);
        for (String kind : List.of("foreign.module", "maven.polyglot.module-state")) {
            writeArtifact(root, kind, kind.equals("foreign.module") ? 1 : 2, valid);
            assertMiss(root, "wrong artifact kind/version");
        }
        for (byte[] malformed : List.of(
                payload(root, 257, List.of(), false),
                payload(root, 2, List.of("same", "same"), false),
                payload(root, 2, List.of("z-last", "a-first"), false),
                payload(root, 1, List.of("../invalid"), false),
                payload(root, 1, List.of("compile"), true),
                Arrays.copyOf(valid, valid.length - 1))) {
            writeArtifact(root, "maven.polyglot.module-state", 1, malformed);
            assertMiss(root, "malformed signed payload");
        }
    }

    private static void boundsCompressedAndExpandedInputs(Path root) throws Exception {
        Files.createDirectories(root.resolve(".m3"));
        Path file = root.resolve(M3DbModuleStore.FILE);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("blobs/" + "a".repeat(64) + ".blob"));
            zip.write(new byte[128 * 1024 + 1]);
            zip.closeEntry();
        }
        check(Files.size(file) < 1024, "compressed expansion fixture is small on disk");
        assertMiss(root, "expanded payload limit");
        Files.write(file, new byte[256 * 1024 + 1]);
        assertMiss(root, "compressed archive limit");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("../escape"));
            zip.write(1);
            zip.closeEntry();
        }
        assertMiss(root, "unexpected entry path");
    }

    private static void boundsReceiptResidency(Path root) throws Exception {
        Files.createDirectories(root);
        try (M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {})) {
            for (int i = 0; i < 257; i++) store.record(String.format(java.util.Locale.ROOT, "task-%03d", i), INPUT, OUTPUT);
            check(store.lookup("task-000") == null, "full store evicts an old receipt");
            check(store.lookup("task-256") != null, "full store records latest success");
        }
        try (M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {})) {
            check(store.lookup("task-000") == null, "eviction is durable");
            check(store.lookup("task-001") != null && store.lookup("task-256") != null, "bounded receipts survive reopen");
        }
    }

    private static void refusesSymlinkPaths(Path root) throws Exception {
        Files.createDirectories(root);
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path directory = Files.createDirectory(root.resolve("module"));
        try {
            Files.createSymbolicLink(directory.resolve(".m3"), outside);
        } catch (UnsupportedOperationException | SecurityException | java.nio.file.FileSystemException unavailable) {
            System.out.println("SKIP: symlink creation unavailable: " + unavailable.getClass().getSimpleName());
            return;
        }
        expectIo(() -> {
            try (M3DbModuleStore store = M3DbModuleStore.open(directory, true, ignored -> {})) {
                store.lookup("compile");
            }
        });
        Files.delete(directory.resolve(".m3"));
        Files.createDirectory(directory.resolve(".m3"));
        Path target = Files.writeString(outside.resolve("target"), "must remain unchanged");
        for (String reserved : List.of(M3DbModuleStore.FILE, M3DbModuleStore.LOCK)) {
            Files.deleteIfExists(directory.resolve(reserved));
            Files.createSymbolicLink(directory.resolve(reserved), target);
            expectIo(() -> {
                try (M3DbModuleStore store = M3DbModuleStore.open(directory, true, ignored -> {})) {
                    store.lookup("compile");
                }
            });
            Files.delete(directory.resolve(reserved));
        }
        check(Files.readString(target).equals("must remain unchanged"), "symlink target not modified");
    }

    private static void closesAndRejectsInvalidRecords(Path root) throws Exception {
        Files.createDirectories(root);
        M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {});
        try {
            store.record("compile", INPUT, OUTPUT);
            byte[] before = Files.readAllBytes(root.resolve(M3DbModuleStore.FILE));
            try { store.record("../escape", INPUT, OUTPUT); throw new AssertionError("invalid name accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
            try { store.record("compile", "not-a-digest", OUTPUT); throw new AssertionError("invalid digest accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
            check(Arrays.equals(before, Files.readAllBytes(root.resolve(M3DbModuleStore.FILE))),
                    "invalid record does not change committed image");
        } finally {
            store.close();
        }
        store.close();
        try { store.lookup("compile"); throw new AssertionError("closed store accepted lookup"); }
        catch (IllegalStateException expected) { checks++; }
    }

    private static byte[] payload(Path root, int count, List<String> names, boolean trailing) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x4d334d44);
            out.writeInt(1);
            out.writeUTF(root.toRealPath().toString());
            out.writeInt(count);
            for (String name : names) {
                out.writeUTF(name);
                out.write(new byte[64]);
            }
            if (trailing) out.write(1);
            out.flush();
            return bytes.toByteArray();
        }
    }

    private static void writeArtifact(Path root, String kind, int version, byte[] payload) throws IOException {
        Path file = root.resolve(M3DbModuleStore.FILE);
        Files.deleteIfExists(file);
        try (FileSystem archive = FileSystems.newFileSystem(file, Map.of("create", "true"));
                M3IndexDB original = M3IndexDB.open(archive.getPath("/"))) {
            original.putArtifact("successful-tasks", kind, version, payload);
        }
    }

    private static void assertMiss(Path root, String reason) throws IOException {
        try (M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {})) {
            check(store.lookup("compile") == null, reason);
        }
    }

    private static void expectIo(IoAction action) throws Exception {
        try { action.run(); throw new AssertionError("expected I/O refusal"); }
        catch (IOException expected) { checks++; }
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
        checks++;
    }

    @FunctionalInterface
    private interface IoAction { void run() throws Exception; }
}
