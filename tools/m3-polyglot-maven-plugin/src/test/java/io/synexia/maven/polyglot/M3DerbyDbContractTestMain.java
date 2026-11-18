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
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Real Apache Derby 10.16.1.1 regression suite for a module-local M3 image.
 *
 * <p>The default offline invocation qualifies only the optional SPI and
 * filesystem refusal contracts. -Pm3-derby passes --require-driver and must
 * execute actual embedded JDBC transactions, independent JVMs and recovery.
 */
public final class M3DerbyDbContractTestMain {
    private static final String ID = "ext-derby-v1";
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);
    private static final String DRIVER = "org.apache.derby.jdbc.EmbeddedDriver";
    private static int checks;

    private M3DerbyDbContractTestMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--warm".equals(args[0])) {
            try (ModuleStateDb db = ModuleStateDb.open(Path.of(args[1]), ID)) {
                require(db.hit("task-compile", A, B), "fresh Java process sees committed Derby image");
            }
            return;
        }
        if (args.length == 2 && "--crash".equals(args[0])) {
            ModuleStateDb db = ModuleStateDb.open(Path.of(args[1]), ID);
            db.record("task-after-crash", C, B);
            Runtime.getRuntime().halt(53);
        }
        boolean required = args.length == 1 && "--require-driver".equals(args[0]);
        if (!(args.length == 0 || required)) throw new IllegalArgumentException("expected --require-driver");
        Path root = Files.createTempDirectory("m3-derby-verify-");
        try {
            paths(root.resolve("security"));
            M3DerbyBackendProvider provider = new M3DerbyBackendProvider();
            require(ID.equals(provider.id()), "versioned local Derby provider identity");
            checkIo(() -> provider.write(root, new byte[0]),
                    "empty Derby snapshot refused before opening any driver");
            checkIo(() -> provider.write(root, new byte[M3DbModuleStore.MAX_PAYLOAD + 1]),
                    "oversized Derby snapshot refused before opening any driver");

            try {
                Class.forName(DRIVER);
            } catch (ClassNotFoundException missing) {
                if (required) throw new AssertionError("Apache Derby 10.16 embedded JDBC driver missing", missing);
                Path empty = Files.createDirectories(root.resolve("without-driver"));
                checkIo(() -> ModuleStateDb.open(empty, ID), "missing optional Derby driver is explicit");
                require(!Files.exists(empty.resolve(ModuleStateDb.EMBEDDED_FILE)),
                        "Derby driver absence does not fall back to M3IndexDB");
                System.out.println("NOT_RUN: real Derby 10.16.1.1 engine not installed");
                System.out.println("PASS: " + checks + " offline Derby SPI and path contracts");
                return;
            }

            Path module = Files.createDirectories(root.resolve("module"));
            try (ModuleStateDb db = ModuleStateDb.open(module, ID)) {
                require(db.lookup("task-compile") == null, "cold Derby module returns no receipts");
                db.record("task-compile", A, B);
                db.record("lifecycle-compile", C, A);
                require(db.hit("task-compile", A, B), "M3 sealed record visible after SQL commit");
            }
            Path directory = module.resolve(".m3/module-state-ext-derby-v1");
            require(Files.isRegularFile(directory.resolve("service.properties"), LinkOption.NOFOLLOW_LINKS),
                    "actual embedded Derby database manifest exists under module .m3");
            require(!Files.exists(module.resolve(ModuleStateDb.EMBEDDED_FILE)),
                    "Derby never silently writes default M3IndexDB backend");
            child("--warm", module, 0);
            var view = ModuleStateView.read(module);
            require(view.size() == 2 && view.stream().allMatch(row -> row.backend().equals(ID)),
                    "read-only project DB view preserves all Derby task identities");

            // Confirm the *actual* JDBC transaction can abort a deletion
            // independently of the M3 sealed image and its generation marker.
            Driver driver = driver();
            String url = derbyUrl(directory);
            try (Connection db = connect(driver, url)) {
                db.setAutoCommit(false);
                try (PreparedStatement delete = db.prepareStatement(
                        "DELETE FROM M3_MODULE_SNAPSHOT WHERE slot = 1")) {
                    require(delete.executeUpdate() == 1, "actual SQL deletion executed inside open transaction");
                }
                db.rollback();
            }
            shutdown(driver, url);
            try (ModuleStateDb db = ModuleStateDb.open(module, ID)) {
                require(db.hit("task-compile", A, B), "Derby rollback preserved original sealed image");
                db.invalidatePrefix("lifecycle-");
                require(db.lookup("lifecycle-compile") == null
                        && db.hit("task-compile", A, B), "prefix invalidation retains unrelated entry");
                db.stage("task-compile", B, C);
                db.stage("task-resource", A, B);
                db.commitStaged();
            }
            try (ModuleStateDb db = ModuleStateDb.open(module, ID)) {
                require(db.hit("task-compile", B, C), "batch replacement survived Derby restart");
                require(db.hit("task-resource", A, B), "new batch entry survived Derby restart");
                db.invalidate("task-resource");
                db.stage("task-aborted", A, C); // intentionally do not commit
            }
            try (ModuleStateDb db = ModuleStateDb.open(module, ID)) {
                require(db.lookup("task-resource") == null && db.lookup("task-aborted") == null,
                        "revoked and uncommitted Derby receipts are not reusable");
                db.record("task-compile", A, B);
            }

            // Corrupt the actual SQL blob, not a test fake. The M3 owner must
            // refuse a successfully committed but unsealed payload.
            driver = driver();
            try (Connection db = connect(driver, url);
                    PreparedStatement replace = db.prepareStatement(
                            "UPDATE M3_MODULE_SNAPSHOT SET image = ? WHERE slot = 1")) {
                replace.setBytes(1, new byte[] {1, 2, 3});
                require(replace.executeUpdate() == 1, "actual Derby payload corruption applied");
            }
            shutdown(driver, url);
            try (ModuleStateDb db = ModuleStateDb.open(module, ID)) {
                require(db.lookup("task-compile") == null, "corrupt Derby image is a cache miss");
                db.record("task-compile", A, B);
            }

            child("--crash", module, 53);
            try (ModuleStateDb db = ModuleStateDb.open(module, ID)) {
                require(db.lookup("task-compile") == null && db.lookup("task-after-crash") == null,
                        "unclean JVM exit cannot revive sealed receipts from dirty generation");
            }
            try (ModuleStateDb other = ModuleStateDb.open(module, "file")) {
                require(other.lookup("task-compile") == null, "switching to file backend starts cold");
                other.record("task-file", A, B);
            }
            try (ModuleStateDb derby = ModuleStateDb.open(module, ID)) {
                require(derby.lookup("task-after-crash") == null
                        && derby.lookup("task-file") == null, "switching back cannot revive Derby evidence");
            }
            System.out.println("PASS: " + checks + " actual Derby JDBC transaction/restart/recovery assertions");
        } finally {
            try (var entries = Files.walk(root)) {
                for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void paths(Path root) throws Exception {
        Files.createDirectories(root);
        Path state = Files.createDirectories(root.resolve(".m3"));
        Path database = Files.createDirectories(state.resolve("module-state-ext-derby-v1"));
        Path outside = Files.writeString(root.resolve("outside"), "unmodified");
        Path malicious = database.resolve("service.properties");
        try {
            Files.createSymbolicLink(malicious, outside);
            checkIo(() -> new M3DerbyBackendProvider().verifyAvailable(root),
                    "Derby refuses symlinked database catalog before driver preflight");
            Files.delete(malicious);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            // On Windows the test runner may lack symlink privileges.
        }
        require("unmodified".equals(Files.readString(outside)), "symlink target was untouched");
        checkIo(() -> M3PortableDbRuntime.path(root, "../derby", true, false),
                "portable Derby path traversal is refused");
        Path forbidden = Files.createDirectories(root.resolve("m3;remote-url"));
        checkIo(() -> new M3DerbyBackendProvider().verifyAvailable(forbidden),
                "embedded JDBC URL delimiter rejected before engine load");
    }

    private static Driver driver() throws Exception {
        return Class.forName(DRIVER).asSubclass(Driver.class).getConstructor().newInstance();
    }

    private static String derbyUrl(Path dir) {
        return "jdbc:derby:" + dir.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static Connection connect(Driver driver, String url) throws SQLException {
        Connection db = driver.connect(url, new Properties());
        if (db == null) throw new SQLException("Derby rejected embedded database URL");
        return db;
    }

    private static void shutdown(Driver driver, String url) throws Exception {
        try {
            driver.connect(url + ";shutdown=true", new Properties());
            throw new AssertionError("Derby shutdown did not confirm database checkpoint");
        } catch (SQLException expected) {
            require("08006".equals(expected.getSQLState()), "Derby shut down with documented SQLState 08006");
        }
    }

    private static void child(String mode, Path module, int expected) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
        Process process = new ProcessBuilder(java.toString(), "-cp",
                System.getProperty("java.class.path"), M3DerbyDbContractTestMain.class.getName(),
                mode, module.toString()).inheritIO().start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Derby child process did not terminate");
        }
        require(process.exitValue() == expected, "Derby " + mode + " child returned expected exit code");
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }

    private static void checkIo(Checked checked, String description) throws Exception {
        try {
            checked.run();
        } catch (IOException expected) {
            require(true, description);
            return;
        }
        throw new AssertionError(description);
    }

    private static void require(boolean okay, String description) {
        if (!okay) throw new AssertionError(description);
        checks++;
    }
}
