/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.ServiceLoader;

/**
 * Real-provider conformance suite. Without optional driver jars, verifies SPI
 * registration/path confinement and reports NOT_RUN; with the pinned Maven
 * donor profile, --require-all makes every native/MapDB transaction mandatory.
 */
public final class M3PortableEngineConformanceTestMain {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);
    private static final List<String> ENGINES =
            List.of("ext-rocksdb-v1", "ext-mapdb-v1", "ext-lmdb-v1");
    private static int checks;

    private M3PortableEngineConformanceTestMain() {}

    public static void main(String[] args) throws Exception {
        boolean required = args.length == 1 && "--require-all".equals(args[0]);
        if (!(args.length == 0 || required)) throw new IllegalArgumentException("expected --require-all");
        List<String> available = new ArrayList<>();
        for (M3ModuleDbBackendProvider provider : ServiceLoader.load(M3ModuleDbBackendProvider.class)) {
            if (ENGINES.contains(provider.id())) available.add(provider.id());
        }
        check(available.size() == ENGINES.size() && Set.copyOf(available).size() == ENGINES.size(),
                "all three real database provider identifiers are registered exactly once");
        Path root = Files.createTempDirectory("m3-real-optional-db-");
        try {
            pathPolicy(root.resolve("paths"));
            int ran = 0;
            for (String engine : ENGINES) {
                Path module = Files.createDirectories(root.resolve(engine));
                try {
                    verify(engine, module);
                    ran++;
                } catch (M3DbModuleStore.BackendUnavailableException absent) {
                    if (required) throw new AssertionError(engine + " required native/JDBC runtime is missing", absent);
                    System.out.println("NOT_RUN " + engine + " (optional donor runtime unavailable)");
                }
            }
            check(required ? ran == ENGINES.size() : ran >= 0,
                    "requested donor profile must exercise every real backend");
            System.out.println("PASS: " + checks + " portable backend contracts; real engines=" + ran);
        } finally {
            try (var walk = Files.walk(root)) {
                for (Path item : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
            }
        }
    }

    private static void verify(String backend, Path module) throws Exception {
        try (ModuleStateDb first = ModuleStateDb.open(module, backend)) {
            check(first.lookup("task-compile") == null, backend + " cold module misses");
            first.record("task-compile", A, B);
            check(first.hit("task-compile", A, B), backend + " immediate commit visible");
            check(!first.hit("task-compile", B, A), backend + " digest mismatch rejected");
        }
        try (ModuleStateDb recovered = ModuleStateDb.open(module, backend)) {
            check(recovered.hit("task-compile", A, B), backend + " committed state survives reopen");
            recovered.stage("task-compile", C, A);
            recovered.stage("task-resource", B, C);
            recovered.commitStaged();
            check(recovered.hit("task-compile", C, A), backend + " staged overwrite wins");
            check(recovered.hit("task-resource", B, C), backend + " staged addition published");
        }
        try (ModuleStateDb recovered = ModuleStateDb.open(module, backend)) {
            check(recovered.hit("task-compile", C, A), backend + " batch survives reopen");
            check(recovered.hit("task-resource", B, C), backend + " second batch item survives reopen");
            recovered.invalidate("task-resource");
            check(recovered.lookup("task-resource") == null, backend + " revocation is immediate");
        }
        try (ModuleStateDb recovered = ModuleStateDb.open(module, backend)) {
            check(recovered.lookup("task-resource") == null, backend + " revocation survives reopen");
            check(recovered.hit("task-compile", C, A), backend + " independent receipt survives revocation");
            recovered.stage("task-transient", A, B);
            // Deliberately close without commit: partial graph successes stay inaccessible.
        }
        try (ModuleStateDb recovered = ModuleStateDb.open(module, backend)) {
            check(recovered.lookup("task-transient") == null, backend + " aborted batch not published");
        }
        try (ModuleStateDb changed = ModuleStateDb.open(module, "file")) {
            check(changed.lookup("task-compile") == null, backend + " backend switch revokes old evidence");
        }
        try (ModuleStateDb returned = ModuleStateDb.open(module, backend)) {
            check(returned.lookup("task-compile") == null, backend + " old backend cannot revive evidence");
        }
    }

    private static void pathPolicy(Path root) throws Exception {
        Files.createDirectories(root);
        var providers = List.of(
                new M3RocksDbBackendProvider(),
                new M3MapDbBackendProvider(),
                new M3LmdbBackendProvider());
        for (M3ModuleDbBackendProvider provider : providers) {
            check(provider.id().matches("ext-[a-z0-9-]{1,48}"), "provider ID local and bounded");
        }
        Path state = Files.createDirectories(root.resolve(".m3"));
        Path outside = Files.writeString(root.resolve("outside"), "private");
        Path symlink = state.resolve("module-state-ext-mapdb-v1.db");
        try {
            Files.createSymbolicLink(symlink, outside);
            expectIo(() -> new M3MapDbBackendProvider().verifyAvailable(root),
                    "MapDB provider rejects a symlinked state file");
            Files.delete(symlink);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            // Some Windows runners forbid creating symlinks.
        }
        Path directory = state.resolve("module-state-ext-rocksdb-v1");
        try {
            Files.createSymbolicLink(directory, outside);
            expectIo(() -> new M3RocksDbBackendProvider().verifyAvailable(root),
                    "RocksDB rejects a linked database directory");
            Files.delete(directory);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) { }
        check(Files.isRegularFile(outside, LinkOption.NOFOLLOW_LINKS), "out-of-module data remains intact");
        expectIo(() -> M3PortableDbRuntime.path(root, "../escape", false, true),
                "portable runtime refuses path traversal");
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void expectIo(Checked action, String description) throws Exception {
        try { action.run(); }
        catch (IOException expected) { check(true, description); return; }
        throw new AssertionError(description);
    }

    private static void check(boolean yes, String description) {
        if (!yes) throw new AssertionError(description);
        checks++;
    }
}
