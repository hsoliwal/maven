/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Real embedded-JDBC integration tests. Maven test dependencies supply the
 * SQLite and H2 donor engines, not the Maven plugin's runtime artifact.
 */
public final class JdbcModuleStateContractTestMain {
    private JdbcModuleStateContractTestMain() {}

    public static void main(String[] ignored) throws Exception {
        Path temp = Files.createTempDirectory("m3-jdbc-module-state-");
        try {
            verify(temp.resolve("sqlite"), "sqlite");
            verify(temp.resolve("h2"), "h2");
            try {
                Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
                verify(temp.resolve("derby"), "derby");
            } catch (ClassNotFoundException retiredDonorUnavailable) {
                System.out.println("SKIP: Derby donor driver not on test classpath");
            }
            System.out.println("PASS: real embedded SQLite/H2 module persistence and invalidation");
        } finally {
            try (var tree = Files.walk(temp)) {
                for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void verify(Path module, String backend) throws Exception {
        Files.createDirectories(module);
        Path source = Files.writeString(module.resolve("in.txt"), "first", StandardCharsets.UTF_8);
        Path output = Files.writeString(module.resolve("out.txt"), "result", StandardCharsets.UTF_8);
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
        TaskManifest.Task task = new TaskManifest.Task("compile", java.toString(), ".",
                List.of("-version"), List.of(), List.of("in.txt"), List.of("out.txt"));

        String first;
        try (ModuleStateDb db = ModuleStateDb.open(module, backend)) {
            first = db.stamp(task);
            require(!db.upToDate(task, first), backend + ": first run misses");
            db.record(task, first);
        }
        try (ModuleStateDb db = ModuleStateDb.open(module, backend)) {
            require(db.upToDate(task, db.stamp(task)), backend + ": persisted transaction reloads");
            Files.writeString(output, "changed", StandardCharsets.UTF_8);
            require(!db.upToDate(task, first), backend + ": output modification invalidates");
        }

        Files.writeString(source, "second", StandardCharsets.UTF_8);
        try (ModuleStateDb db = ModuleStateDb.open(module, backend)) {
            String changed = db.stamp(task);
            require(!changed.equals(first), backend + ": content change alters key");
            require(!db.upToDate(task, changed), backend + ": changed source rebuilds");
            db.record(task, changed);
        }
        try (ModuleStateDb db = ModuleStateDb.open(module, backend)) {
            require(db.upToDate(task, db.stamp(task)), backend + ": new state persists");
        }

        Path store = backend.equals("sqlite")
                ? module.resolve(".m3/module-state.sqlite")
                : backend.equals("h2") ? module.resolve(".m3/module-state-h2.mv.db")
                : module.resolve(".m3/module-state-derby");
        require(Files.exists(store), backend + ": module-local embedded DB exists");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
