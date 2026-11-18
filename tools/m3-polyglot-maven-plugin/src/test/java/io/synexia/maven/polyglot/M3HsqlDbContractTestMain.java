/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * HSQLDB 2.7.x transactional module-state contract. Without the explicitly
 * opted-in JDBC driver, only the missing-driver and path-safety tests run.
 * Maven -Pm3-hsqldb runs this with --require-driver and real JDBC I/O.
 */
public final class M3HsqlDbContractTestMain {
    private static final String INPUT = "a".repeat(64);
    private static final String OUTPUT = "b".repeat(64);
    private static int passed;

    private M3HsqlDbContractTestMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--crash".equals(args[0])) {
            ModuleStateDb state = ModuleStateDb.open(Path.of(args[1]), "hsqldb");
            state.record("task-abandoned", INPUT, OUTPUT);
            Runtime.getRuntime().halt(31);
        }
        if (args.length == 2 && "--warm".equals(args[0])) {
            try (ModuleStateDb state = ModuleStateDb.open(Path.of(args[1]), "hsqldb")) {
                require(state.hit("task-compile", INPUT, OUTPUT),
                        "fresh JVM reads the HSQLDB committed receipt");
            }
            return;
        }
        boolean required = args.length == 1 && "--require-driver".equals(args[0]);
        if (!(args.length == 0 || required)) throw new IllegalArgumentException("use --require-driver");
        Path root = Files.createTempDirectory("m3-hsqldb-");
        try {
            require("hsqldb".equals(M3DbModuleStore.backend("hsqldb")), "canonical JDBC backend");
            require(ModuleStateDb.statePath(ModuleStateDb.HSQLDB_FILE), "HSQLDB main file excluded from inputs");
            require(ModuleStateDb.statePath(".m3/module-state-hsqldb.log"),
                    "HSQLDB transaction log excluded from inputs");
            Path linked = Files.createDirectories(root.resolve("linked"));
            Path state = Files.createDirectories(linked.resolve(".m3"));
            Path outside = Files.writeString(root.resolve("outside"), "do not touch");
            Path malicious = state.resolve("module-state-hsqldb.script");
            try {
                Files.createSymbolicLink(malicious, outside);
                expectIo(() -> ModuleStateDb.open(linked, "hsqldb"),
                        "HSQLDB rejects linked recovery sidecar before driver loading");
                Files.delete(malicious);
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
                // Windows tests may lack permission to create symlinks.
            }
            require(Files.readString(outside).equals("do not touch"), "no out-of-module writes");

            try {
                Class.forName("org.hsqldb.jdbc.JDBCDriver");
            } catch (ClassNotFoundException unavailable) {
                if (required) throw new AssertionError("required HSQLDB 2.7.4 driver absent", unavailable);
                Path absent = Files.createDirectories(root.resolve("missing-driver"));
                expectIo(() -> ModuleStateDb.open(absent, "hsqldb"),
                        "missing explicit HSQLDB driver is not silently replaced");
                require(!Files.exists(absent.resolve(ModuleStateDb.EMBEDDED_FILE)),
                        "missing driver cannot publish a fallback embedded state");
                System.out.println("NOT_RUN: real HSQLDB JDBC I/O (driver not installed)");
                System.out.println("PASS: " + passed + " HSQLDB offline admission and safety checks");
                return;
            }

            Path module = Files.createDirectories(root.resolve("real"));
            try (ModuleStateDb database = ModuleStateDb.open(module, "hsqldb")) {
                require(database.lookup("task-compile") == null, "cold HSQLDB miss");
                database.record("task-compile", INPUT, OUTPUT);
                database.record("lifecycle-compile", INPUT, OUTPUT);
            }
            require(Files.isRegularFile(module.resolve(ModuleStateDb.HSQLDB_FILE), LinkOption.NOFOLLOW_LINKS),
                    "real HSQLDB metadata file exists under selected module");
            require(!Files.exists(module.resolve(ModuleStateDb.EMBEDDED_FILE)),
                    "HSQLDB never substitutes an embedded M3IndexDB ZIP");

            child("warm", module, 0);
            List<ModuleStateView.TaskState> view = ModuleStateView.read(module);
            require(view.size() == 2 && view.stream().allMatch(row -> row.backend().equals("hsqldb")
                    && row.source().equals(ModuleStateDb.HSQLDB_FILE)),
                    "project DB reads the original HSQLDB backend and source");

            String marker = Files.readString(module.resolve(ModuleStateDb.MARKER));
            ModuleStateDb.revokePrefix(module, "lifecycle-", ignored -> {});
            require(marker.equals(Files.readString(module.resolve(ModuleStateDb.MARKER))),
                    "selective revocation preserves active backend and generation");
            try (ModuleStateDb database = ModuleStateDb.open(module, "hsqldb")) {
                require(database.hit("task-compile", INPUT, OUTPUT),
                        "independent task receipt survives selective revocation");
                require(database.lookup("lifecycle-compile") == null,
                        "lifecycle receipt durably revoked");
                database.stage("task-compile", OUTPUT, INPUT);
                database.stage("task-resources", INPUT, OUTPUT);
                database.commitStaged();
            }
            try (ModuleStateDb database = ModuleStateDb.open(module, "hsqldb")) {
                require(database.hit("task-compile", OUTPUT, INPUT),
                        "transactional batch overwrite persisted");
                require(database.hit("task-resources", INPUT, OUTPUT),
                        "transactional batch addition persisted");
                database.invalidate("task-resources");
                database.stage("task-uncommitted", INPUT, OUTPUT);
                // No commitStaged: closing discards this successful-looking task.
            }
            try (ModuleStateDb database = ModuleStateDb.open(module, "hsqldb")) {
                require(database.lookup("task-resources") == null
                        && database.lookup("task-uncommitted") == null,
                        "pre-mutation revocation and uncommitted changes remain cold");
                database.record("task-compile", INPUT, OUTPUT);
            }
            try (Connection conn = DriverManager.getConnection(url(module));
                    PreparedStatement statement = conn.prepareStatement(
                            "UPDATE M3_MODULE_SNAPSHOT SET image=? WHERE slot=1")) {
                statement.setBytes(1, new byte[] {1, 2, 3});
                require(statement.executeUpdate() == 1, "real JDBC row corruption applied");
            }
            try (ModuleStateDb database = ModuleStateDb.open(module, "hsqldb")) {
                require(database.lookup("task-compile") == null,
                        "corrupt sealed state never authorizes a skip");
                database.record("task-compile", INPUT, OUTPUT);
            }
            child("crash", module, 31);
            try (ModuleStateDb database = ModuleStateDb.open(module, "hsqldb")) {
                require(database.lookup("task-compile") == null
                        && database.lookup("task-abandoned") == null,
                        "unclean process termination rotates the whole HSQLDB evidence generation");
            }
            try (ModuleStateDb switched = ModuleStateDb.open(module, "file")) {
                require(switched.lookup("task-compile") == null, "backend switch starts cold");
                switched.record("task-file", INPUT, OUTPUT);
            }
            try (ModuleStateDb database = ModuleStateDb.open(module, "hsqldb")) {
                require(database.lookup("task-abandoned") == null
                        && database.lookup("task-file") == null,
                        "switching back cannot revive previously invalidated HSQLDB receipts");
            }
            System.out.println("PASS: " + passed
                    + " real HSQLDB transaction, corruption, restart, crash and switching checks");
        } finally {
            try (var walk = Files.walk(root)) {
                for (Path item : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
            }
        }
    }

    private static String url(Path module) {
        return "jdbc:hsqldb:file:" + module.resolve(".m3/module-state-hsqldb")
                .toAbsolutePath().toString().replace('\\', '/')
                + ";shutdown=true;hsqldb.write_delay=false;hsqldb.full_log_replay=true";
    }

    private static void child(String action, Path module, int exit) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
        Process process = new ProcessBuilder(java.toString(), "-classpath", System.getProperty("java.class.path"),
                M3HsqlDbContractTestMain.class.getName(), "--" + action, module.toString())
                .inheritIO().start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("HSQLDB child process timeout");
        }
        require(process.exitValue() == exit, "HSQLDB child " + action + " returned " + exit);
    }

    @FunctionalInterface private interface IoTask { void run() throws Exception; }

    private static void expectIo(IoTask task, String message) throws Exception {
        try { task.run(); }
        catch (IOException expected) { require(true, message); return; }
        throw new AssertionError(message);
    }

    private static void require(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        passed++;
    }
}
