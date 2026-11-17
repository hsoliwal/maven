/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;

/** Offline JDK-only portability contract; JDBC runtime drivers require separate qualification. */
public final class M3PortableDbContractTestMain {
    private static int passed;

    private M3PortableDbContractTestMain() {}

    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("m3-portable-db-");
        try {
            Path first = Files.createDirectories(root.resolve("a"));
            Path second = Files.createDirectories(root.resolve("b"));
            try (M3ModuleDbRunLock lock = M3ModuleDbRunLock.acquire(first)) {
                check(lock != null, "module lock acquired");
                check(Files.isRegularFile(first.resolve(M3ModuleDbRunLock.FILE)), "module lock is local");
                expectFailure(() -> M3ModuleDbRunLock.acquire(first), "same process lock rejects overlap");
                try (M3ModuleDbRunLock independent = M3ModuleDbRunLock.acquire(second)) {
                    check(independent != null, "independent lock acquired");
                    check(Files.isRegularFile(second.resolve(M3ModuleDbRunLock.FILE)), "independent module lock works");
                }
            }
            try (M3ModuleDbRunLock released = M3ModuleDbRunLock.acquire(first)) {
                check(released != null, "module reacquired lock");
                check(true, "closed lock can be reacquired");
            }
            Path outside = Files.createDirectories(root.resolve("outside"));
            Path linked = root.resolve("linked");
            try {
                Files.createSymbolicLink(linked, outside);
                expectFailure(() -> M3ModuleDbRunLock.acquire(linked), "linked module root must fail");
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                // Symlink setup is optional on Windows.
            }
            Path module = Files.createDirectories(root.resolve("symlink-state"));
            Path symlinkState = module.resolve(".m3");
            try {
                Files.createSymbolicLink(symlinkState, outside);
                expectFailure(() -> M3ModuleDbRunLock.acquire(module), "linked cache state must fail");
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                // Symlink setup is optional on Windows.
            }
            check(!Files.exists(root.resolve("elsewhere"), LinkOption.NOFOLLOW_LINKS), "no out-of-scope writes");
            rejectsUnapprovedJdbcBackends(first);
            System.out.println("PASS: " + passed + " M3 portable module DB lock cases");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void rejectsUnapprovedJdbcBackends(Path module) throws Exception {
        boolean refused = false;
        try {
            new M3ModuleJdbcStorage(module, "remote-postgres");
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        check(refused, "network JDBC backend ids are not accepted");
        for (String[] option : new String[][] {
                {"sqlite", "org.sqlite.JDBC"}, {"h2", "org.h2.Driver"}}) {
            try {
                Class.forName(option[1]);
            } catch (ClassNotFoundException unavailable) {
                expectFailure(() -> new M3ModuleJdbcStorage(module, option[0]),
                        "missing explicit " + option[0] + " driver refuses selection");
            }
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }

    private static void expectFailure(Checked callback, String what) throws Exception {
        try {
            callback.run();
        } catch (IOException expected) {
            check(true, what);
            return;
        }
        throw new AssertionError(what);
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
