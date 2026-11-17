/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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

    public static void main(String[] args) throws Exception {
        if (args.length == 2) {
            Path root = Path.of(args[1]);
            if ("lock".equals(args[0])) {
                expectIo(() -> {
                    try (M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {})) {
                        store.lookup("compile");
                    }
                });
                return;
            }
            M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {});
            if ("publish".equals(args[0])) store.record("compile", INPUT, OUTPUT);
            else store.lookup("compile");
            Runtime.getRuntime().halt(23); // deliberately omit close: real process-crash recovery
        }
        Path temporary = Files.createTempDirectory("m3-module-store-test-");
        try {
            persistsOneOriginalDatabase(temporary.resolve("persistent"));
            revokesBeforeMutation(temporary.resolve("revoked"));
            revokesOnlySelectedNamespace(temporary.resolve("namespaces"));
            disabledRunsOnlyInvalidate(temporary.resolve("disabled"));
            refusesConcurrentOwners(temporary.resolve("locked"));
            refusesCrossClassLoaderOwners(temporary.resolve("classloaders"));
            bindsCanonicalModule(temporary.resolve("source"), temporary.resolve("destination"));
            recoversCorruptImages(temporary.resolve("corrupt"));
            refusesSignedInvalidSchemas(temporary.resolve("schema"));
            boundsCompressedAndExpandedInputs(temporary.resolve("limits"));
            boundsReceiptResidency(temporary.resolve("capacity"));
            refusesSymlinkPaths(temporary.resolve("links"));
            closesAndRejectsInvalidRecords(temporary.resolve("closed"));
            recoversInterruptedSessions(temporary.resolve("interrupted"));
            refusesFailedPublication(temporary.resolve("publication"));
            backendSwitchRevokesOldImages(temporary.resolve("backends"));
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
            check(entries.count() == 4, "one database, one lock, one recovery marker and one self-ignore file,"
                    + " no task stamp files");
        }
        check(Files.readString(root.resolve(".m3/.gitignore")).equals("*\n"), "the state directory ignores itself");
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

    private static void revokesOnlySelectedNamespace(Path root) throws Exception {
        Files.createDirectories(root);
        try (M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {})) {
            for (String name : List.of("task-compile", "task-package", "lifecycle-compile", "lifecycle-package")) {
                store.record(name, INPUT, OUTPUT);
            }
            store.invalidatePrefix("lifecycle-");
            check(store.lookup("lifecycle-compile") == null && store.lookup("lifecycle-package") == null,
                    "prefix invalidation revokes every lifecycle record");
            check(store.lookup("task-compile") != null && store.lookup("task-package") != null,
                    "prefix invalidation preserves task records");
            byte[] prior = Files.readAllBytes(root.resolve(M3DbModuleStore.FILE));
            for (String invalid : List.of("", "../")) {
                try { store.invalidatePrefix(invalid); throw new AssertionError("invalid prefix accepted"); }
                catch (IllegalArgumentException expected) { checks++; }
            }
            check(Arrays.equals(prior, Files.readAllBytes(root.resolve(M3DbModuleStore.FILE))),
                    "rejected prefix cannot change persisted state");
        }
        try (M3DbModuleStore store = M3DbModuleStore.open(root, false, ignored -> {})) {
            check(store.lookup("lifecycle-compile") == null && store.lookup("task-compile") != null,
                    "prefix revocation and retained task both survive reopening");
            store.invalidatePrefix("task-");
        }
        try (M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {})) {
            check(store.lookup("task-compile") == null, "disabled store can durably revoke one namespace");
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
            check(child("lock", root) == 0, "module lease rejects a second JVM");
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
        Files.copy(source.resolve(M3DbModuleStore.MARKER), destination.resolve(M3DbModuleStore.MARKER));
        try (M3DbModuleStore store = M3DbModuleStore.open(destination, true, ignored -> {})) {
            check(store.lookup("compile") == null, "copied module state cannot authorize another module");
            store.invalidate("compile");
            store.record("compile", OUTPUT, INPUT);
        }
        try (M3DbModuleStore store = M3DbModuleStore.open(source, true, ignored -> {})) {
            check(store.lookup("compile")[0].equals(INPUT), "separate module state remains independent");
        }
    }

    private static void refusesCrossClassLoaderOwners(Path root) throws Exception {
        Files.createDirectories(root);
        URL code = ModuleStateDb.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader first = new URLClassLoader(new URL[] {code}, ClassLoader.getPlatformClassLoader());
                URLClassLoader second = new URLClassLoader(new URL[] {code}, ClassLoader.getPlatformClassLoader())) {
            Class<?> firstType = Class.forName(ModuleStateDb.class.getName(), true, first);
            Class<?> secondType = Class.forName(ModuleStateDb.class.getName(), true, second);
            check(firstType != secondType, "fixture uses independent Maven-like class realms");
            try (AutoCloseable lease = (AutoCloseable) firstType.getMethod("open", Path.class, String.class)
                    .invoke(null, root, "embedded")) {
                check(lease != null, "first realm holds module lease");
                try {
                    Object unexpected = secondType.getMethod("open", Path.class, String.class)
                            .invoke(null, root, "file");
                    ((AutoCloseable) unexpected).close();
                    throw new AssertionError("a second class realm acquired the module lease");
                } catch (InvocationTargetException refused) {
                    check(refused.getCause() instanceof IOException, "second realm is refused before opening a descriptor");
                }
                check(child("lock", root) == 0, "cross-realm refusal preserves native lock against another JVM");
            }
        }
        try (ModuleStateDb store = ModuleStateDb.open(root, "embedded")) {
            check(store.lookup("compile") == null, "closed foreign class realm releases shared lease");
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
        try (M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {})) {
            check(store.lookup("compile") == null, "schema fixture has an admitted generation");
        }
        byte[] valid = payload(root, 1, List.of("compile"), false);
        for (String kind : List.of("foreign.module", "maven.polyglot.module-state")) {
            writeArtifact(root, kind, kind.equals("foreign.module") ? 2 : 3, valid);
            assertMiss(root, "wrong artifact kind/version");
        }
        for (byte[] malformed : List.of(
                payload(root, 257, List.of(), false),
                payload(root, 2, List.of("same", "same"), false),
                payload(root, 2, List.of("z-last", "a-first"), false),
                payload(root, 1, List.of("../invalid"), false),
                payload(root, 1, List.of("compile"), true),
                Arrays.copyOf(valid, valid.length - 1))) {
            writeArtifact(root, "maven.polyglot.module-state", 2, malformed);
            assertMiss(root, "malformed signed payload");
        }
    }

    private static void boundsCompressedAndExpandedInputs(Path root) throws Exception {
        Files.createDirectories(root.resolve(".m3"));
        Path file = root.resolve(M3DbModuleStore.FILE);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("blobs/" + "a".repeat(64) + ".blob"));
            zip.write(new byte[32 * 1024 * 1024 + 1]);
            zip.closeEntry();
        }
        check(Files.size(file) < 64 * 1024, "compressed expansion fixture is small on disk");
        assertMiss(root, "expanded payload limit");
        Files.write(file, new byte[32 * 1024 * 1024 + 128 * 1024 + 1]);
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
        for (String reserved : List.of(M3DbModuleStore.FILE, M3DbModuleStore.LOCK, M3DbModuleStore.MARKER)) {
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

    private static void recoversInterruptedSessions(Path root) throws Exception {
        Files.createDirectories(root);
        try (M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {})) {
            store.record("compile", INPUT, OUTPUT);
        }
        check(child("read", root) == 23, "reader process halted without closing its session");
        assertMiss(root, "interrupted session cannot reuse earlier success");
        check(child("publish", root) == 23, "writer process halted after image publication");
        assertMiss(root, "unclean published image cannot authorize a build");
        Files.write(root.resolve(M3DbModuleStore.MARKER), new byte[] {(byte) 0xff});
        assertMiss(root, "malformed backend marker is a cold generation");
    }

    private static void refusesFailedPublication(Path root) throws Exception {
        Files.createDirectories(root);
        byte[] prior;
        Path database = root.resolve(M3DbModuleStore.FILE);
        try (M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {})) {
            store.record("compile", INPUT, OUTPUT);
            prior = Files.readAllBytes(database);
            Files.delete(database);
            Files.createDirectory(database);
            expectIo(() -> store.invalidate("compile"));
            try { store.lookup("compile"); throw new AssertionError("failed publisher remained usable"); }
            catch (IllegalStateException expected) { checks++; }
        }
        check(Files.readString(root.resolve(M3DbModuleStore.MARKER)).endsWith("dirty\n"),
                "failed publication cannot mark the session clean");
        Files.delete(database);
        Files.write(database, prior);
        assertMiss(root, "old image restored after failed publication remains revoked");
    }

    private static void backendSwitchRevokesOldImages(Path root) throws Exception {
        Files.createDirectories(root);
        try (M3DbModuleStore file = M3DbModuleStore.open(root, "file", true, ignored -> {})) {
            file.record("compile", INPUT, OUTPUT);
        }
        try (M3DbModuleStore embedded = M3DbModuleStore.open(root, "embedded", false, ignored -> {})) {
            check(embedded.lookup("compile") == null, "backend switching is a miss");
            embedded.invalidateAll();
        }
        try (M3DbModuleStore file = M3DbModuleStore.open(root, "file", true, ignored -> {})) {
            check(file.lookup("compile") == null, "disabled execution through another backend revokes old image");
            file.record("compile", OUTPUT, INPUT);
        }
        try (M3DbModuleStore file = M3DbModuleStore.open(root, "file", true, ignored -> {})) {
            check(file.lookup("compile")[0].equals(OUTPUT), "unchanged backend preserves clean successes");
        }
        try (M3DbModuleStore embedded = M3DbModuleStore.open(root, "embedded", true, ignored -> {})) {
            embedded.record("compile", INPUT, OUTPUT);
        }
        try (M3DbModuleStore legacy = M3DbModuleStore.open(root, "legacy", true, ignored -> {})) {
            check(legacy.lookup("compile") != null, "legacy compatibility alias shares embedded generation");
        }
    }

    private static int child(String action, Path root) throws Exception {
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
        Process process = new ProcessBuilder(javaExecutable.toString(), "-ea", "-cp", System.getProperty("java.class.path"),
                M3DbModuleStoreContractTestMain.class.getName(), action, root.toString()).inheritIO().start();
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("store child process timed out");
        }
        return process.exitValue();
    }

    private static byte[] payload(Path root, int count, List<String> names, boolean trailing) throws Exception {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x4d334d44);
            out.writeInt(2);
            out.writeUTF(root.toRealPath().toString());
            out.writeUTF(Files.readAllLines(root.resolve(M3DbModuleStore.MARKER)).get(2));
            out.writeInt(count);
            for (String name : names) {
                out.writeUTF(name);
                out.write(new byte[64]);
            }
            out.writeInt(0);
            if (trailing) out.write(1);
            out.flush();
            out.write(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
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
