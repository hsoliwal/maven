/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Explicit positive gate using actual JDBC drivers supplied solely through a plugin context realm. */
public final class M3JdbcDbContractTestMain {
    private static final String INPUT = "a".repeat(64);
    private static final String OUTPUT = "b".repeat(64);
    private static int passed;

    private M3JdbcDbContractTestMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && args[0].equals("--child")) {
            child(args);
            return;
        }
        if (args.length < 3 || !args[0].equals("--driver-classpath")) {
            throw new IllegalArgumentException("--driver-classpath <actual driver/runtime jars> h2 sqlite");
        }
        String driverClasspath = args[1];
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Path temporary = Files.createTempDirectory("m3-real-jdbc-");
        try (URLClassLoader realm = driverRealm(driverClasspath)) {
            Thread.currentThread().setContextClassLoader(realm);
            for (int i = 2; i < args.length; i++) {
                String backend = args[i];
                String driver = driverName(backend);
                try {
                    Class.forName(driver, false, M3ModuleJdbcStorage.class.getClassLoader());
                    throw new AssertionError("driver must be context-only to qualify the Maven realm boundary: " + backend);
                } catch (ClassNotFoundException expected) {
                    check(true, "defining realm cannot load " + backend);
                }
                Class<?> actual = Class.forName(driver, true, realm);
                check(actual.getClassLoader() == realm, "actual driver is loaded from plugin context: " + backend);
                var source = actual.getProtectionDomain().getCodeSource();
                check(source != null, "driver has source artifact identity");
                Path jar = Path.of(source.getLocation().toURI());
                check(Files.isRegularFile(jar), "driver comes from an actual jar");
                System.out.println("DRIVER " + backend + " " + jar + " SHA256="
                        + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
                Path root = Files.createDirectories(temporary.resolve(backend));
                persistsAndRevokes(root.resolve("persistent"), backend, driverClasspath);
                corruptPayloadMisses(root.resolve("corrupt"), backend);
                oversizedPayloadMisses(root.resolve("oversized"), backend);
                failedInsertRollsBack(root.resolve("rollback"), backend);
                interruptedInvalidationMisses(root.resolve("interrupted"), backend, driverClasspath);
                refusesSidecarLinks(root.resolve("sidecars"), backend);
                System.out.println("QUALIFIED actual " + backend + " persistence, context realm, corruption, switching, rollback and crash recovery");
            }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("PASS: " + passed + " real JDBC module-state checks");
    }

    private static void persistsAndRevokes(Path root, String backend, String driverClasspath) throws Exception {
        Files.createDirectories(root);
        try (ModuleStateDb state = ModuleStateDb.open(root, backend)) {
            check(state.lookup("task-alpha") == null, "cold JDBC module misses");
            state.record("task-alpha", INPUT, OUTPUT);
            state.record("lifecycle-alpha", INPUT, OUTPUT);
        }
        check(Files.isRegularFile(root.resolve(backend.equals("sqlite") ? ModuleStateDb.SQLITE_FILE : ModuleStateDb.H2_FILE)),
                "selected database is module-local");
        check(!Files.exists(root.resolve(ModuleStateDb.EMBEDDED_FILE)), "JDBC never silently writes embedded fallback");
        check(childProcess("read", root, backend, driverClasspath) == 0, "JDBC success persists in a fresh JVM");
        String marker = Files.readString(root.resolve(ModuleStateDb.MARKER));
        ModuleStateDb.revokePrefix(root, "lifecycle-", ignored -> {});
        check(marker.equals(Files.readString(root.resolve(ModuleStateDb.MARKER))), "selective revocation preserves active JDBC generation");
        try (ModuleStateDb state = ModuleStateDb.open(root, backend)) {
            check(state.hit("task-alpha", INPUT, OUTPUT), "selective revocation preserves task namespace");
            check(state.lookup("lifecycle-alpha") == null, "selective revocation removes lifecycle success");
            state.invalidate("task-alpha");
        }
        try (ModuleStateDb state = ModuleStateDb.open(root, backend)) {
            check(state.lookup("task-alpha") == null, "pre-execution revocation persists");
            state.record("task-alpha", INPUT, OUTPUT);
        }
        try (ModuleStateDb file = ModuleStateDb.open(root, "file")) {
            check(file.lookup("task-alpha") == null, "backend switching starts cold");
            file.record("task-file", INPUT, OUTPUT);
        }
        try (ModuleStateDb state = ModuleStateDb.open(root, backend)) {
            check(state.lookup("task-alpha") == null, "switching back cannot revive old JDBC success");
            check(state.lookup("task-file") == null, "backend generations do not merge independent images");
        }
    }

    private static void corruptPayloadMisses(Path root, String backend) throws Exception {
        Files.createDirectories(root);
        try (ModuleStateDb state = ModuleStateDb.open(root, backend)) { state.record("task-alpha", INPUT, OUTPUT); }
        try (Connection connection = connection(root, backend);
                PreparedStatement update = connection.prepareStatement("UPDATE M3_MODULE_SNAPSHOT SET image = ? WHERE slot = 1")) {
            update.setBytes(1, new byte[] {1, 2, 3, 4});
            check(update.executeUpdate() == 1, "actual JDBC payload corruption applied");
        }
        try (ModuleStateDb state = ModuleStateDb.open(root, backend)) {
            check(state.lookup("task-alpha") == null, "malformed sealed JDBC image cannot authorize success");
            state.record("task-repaired", INPUT, OUTPUT);
        }
        try (ModuleStateDb state = ModuleStateDb.open(root, backend)) {
            check(state.hit("task-repaired", INPUT, OUTPUT), "a fresh generation replaces corrupt payload");
        }
    }

    private static void failedInsertRollsBack(Path root, String backend) throws Exception {
        Files.createDirectories(root);
        try (ModuleStateDb state = ModuleStateDb.open(root, backend)) { state.record("task-alpha", INPUT, OUTPUT); }
        byte[] before = image(root, backend);
        // A real database CHECK constraint makes INSERT fail after the adapter's DELETE.
        // The old row satisfies it. No fake Driver or scripted JDBC result is involved.
        try (Connection connection = connection(root, backend); Statement sql = connection.createStatement()) {
            long length;
            try (ResultSet result = sql.executeQuery("SELECT LENGTH(image) FROM M3_MODULE_SNAPSHOT WHERE slot = 1")) {
                check(result.next(), "original JDBC row exists before rollback witness");
                length = result.getLong(1);
            }
            sql.execute("ALTER TABLE M3_MODULE_SNAPSHOT RENAME TO M3_PREVIOUS_SNAPSHOT");
            sql.execute("CREATE TABLE M3_MODULE_SNAPSHOT (slot INTEGER PRIMARY KEY, image BLOB NOT NULL CHECK (LENGTH(image) = " + length + "))");
            sql.execute("INSERT INTO M3_MODULE_SNAPSHOT SELECT slot, image FROM M3_PREVIOUS_SNAPSHOT");
            sql.execute("DROP TABLE M3_PREVIOUS_SNAPSHOT");
        }
        try (ModuleStateDb state = ModuleStateDb.open(root, backend)) {
            check(state.hit("task-alpha", INPUT, OUTPUT), "original sealed image remains readable");
            expectIo(() -> state.record("task-extra", INPUT, OUTPUT), "real JDBC constraint rejects INSERT after DELETE");
        }
        check(Arrays.equals(before, image(root, backend)), "failed JDBC publication rolls back deletion of prior row");
        check(Files.readAllLines(root.resolve(ModuleStateDb.MARKER)).get(3).equals("dirty"), "failed commit leaves generation dirty");
        try (ModuleStateDb state = ModuleStateDb.open(root, backend)) {
            check(state.lookup("task-alpha") == null, "rollback cannot revive old task success after a failed session");
            check(state.lookup("task-extra") == null, "failed insertion cannot authorize a new task");
        }
    }

    private static void oversizedPayloadMisses(Path root, String backend) throws Exception {
        Files.createDirectories(root);
        try (ModuleStateDb state = ModuleStateDb.open(root, backend)) { state.record("task-alpha", INPUT, OUTPUT); }
        try (Connection connection = connection(root, backend);
                PreparedStatement update = connection.prepareStatement("UPDATE M3_MODULE_SNAPSHOT SET image = ? WHERE slot = 1")) {
            update.setBytes(1, new byte[32 * 1024 * 1024 + 1]);
            check(update.executeUpdate() == 1, "actual JDBC oversized payload inserted");
        }
        try (ModuleStateDb state = ModuleStateDb.open(root, backend)) {
            check(state.lookup("task-alpha") == null, "SQL projection rejects oversized payload before binary-stream materialization");
            state.record("task-repaired", INPUT, OUTPUT);
        }
        try (ModuleStateDb state = ModuleStateDb.open(root, backend)) {
            check(state.hit("task-repaired", INPUT, OUTPUT), "fresh bounded image replaces oversized JDBC payload");
        }
    }

    private static void interruptedInvalidationMisses(Path root, String backend, String driverClasspath) throws Exception {
        Files.createDirectories(root);
        try (ModuleStateDb state = ModuleStateDb.open(root, backend)) { state.record("task-alpha", INPUT, OUTPUT); }
        check(childProcess("invalidate-halt", root, backend, driverClasspath) == 73, "fresh JVM halts after actual JDBC invalidation");
        check(Files.readAllLines(root.resolve(ModuleStateDb.MARKER)).get(3).equals("dirty"), "interrupted session retains dirty marker");
        try (ModuleStateDb state = ModuleStateDb.open(root, backend)) {
            check(state.lookup("task-alpha") == null, "interrupted JDBC execution cannot revive prior success");
        }
    }

    private static void refusesSidecarLinks(Path root, String backend) throws Exception {
        Files.createDirectories(root.resolve(".m3"));
        Path outside = Files.writeString(root.resolve("outside"), "untouched");
        Path sidecar = root.resolve(backend.equals("sqlite") ? ModuleStateDb.SQLITE_FILE + "-journal" : ".m3/module-state.trace.db");
        try {
            Files.createSymbolicLink(sidecar, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            System.out.println("UNAVAILABLE sidecar symlink setup: " + unsupported.getClass().getSimpleName());
            return;
        }
        expectIo(() -> ModuleStateDb.open(root, backend), "JDBC sidecar symlink is refused before driver access");
        check(Files.readString(outside).equals("untouched"), "JDBC sidecar refusal preserves outside file");
    }

    private static byte[] image(Path root, String backend) throws Exception {
        try (Connection connection = connection(root, backend); Statement sql = connection.createStatement();
                ResultSet result = sql.executeQuery("SELECT image FROM M3_MODULE_SNAPSHOT WHERE slot = 1")) {
            if (!result.next()) throw new AssertionError("expected committed JDBC row");
            return result.getBytes(1);
        }
    }

    private static Connection connection(Path root, String backend) throws Exception {
        Driver driver = (Driver) Class.forName(driverName(backend), true, Thread.currentThread().getContextClassLoader())
                .getConstructor().newInstance();
        String url = backend.equals("sqlite") ? "jdbc:sqlite:" + root.resolve(ModuleStateDb.SQLITE_FILE)
                : "jdbc:h2:file:" + root.resolve(".m3/module-state") + ";WRITE_DELAY=0";
        Connection result = driver.connect(url, new Properties());
        if (result == null) throw new AssertionError("actual driver rejected test database URL");
        return result;
    }

    private static String driverName(String backend) {
        return switch (backend) {
            case "h2" -> "org.h2.Driver";
            case "sqlite" -> "org.sqlite.JDBC";
            default -> throw new IllegalArgumentException("unsupported positive JDBC gate: " + backend);
        };
    }

    private static URLClassLoader driverRealm(String classpath) throws IOException {
        List<URL> urls = new ArrayList<>();
        for (String part : classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1)) {
            if (part.isBlank()) throw new IOException("empty JDBC driver classpath entry");
            Path path = Path.of(part).toAbsolutePath().normalize();
            if (path.getFileName().toString().equals("*")) {
                try (var jars = Files.list(path.getParent())) {
                    for (Path jar : jars.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                        urls.add(jar.toUri().toURL());
                    }
                }
            } else {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("missing actual JDBC runtime jar: " + path);
                urls.add(path.toUri().toURL());
            }
        }
        if (urls.isEmpty()) throw new IOException("no actual JDBC runtime jars supplied");
        return new URLClassLoader(urls.toArray(URL[]::new), M3JdbcDbContractTestMain.class.getClassLoader());
    }

    private static int childProcess(String action, Path root, String backend, String classpath) throws Exception {
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"), M3JdbcDbContractTestMain.class.getName(),
                "--child", action, root.toString(), backend, classpath).inheritIO().start();
        if (!child.waitFor(45, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new AssertionError("JDBC child did not finish");
        }
        return child.exitValue();
    }

    private static void child(String[] args) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader realm = driverRealm(args[4])) {
            Thread.currentThread().setContextClassLoader(realm);
            try (ModuleStateDb state = ModuleStateDb.open(Path.of(args[2]), args[3])) {
                if (args[1].equals("read")) {
                    if (!state.hit("task-alpha", INPUT, OUTPUT)) throw new AssertionError("fresh JVM missed JDBC success");
                } else if (args[1].equals("invalidate-halt")) {
                    state.invalidate("task-alpha");
                    Runtime.getRuntime().halt(73);
                } else throw new IllegalArgumentException("unknown JDBC child action");
            }
        } finally { Thread.currentThread().setContextClassLoader(previous); }
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
    private static void expectIo(Checked callback, String what) throws Exception {
        try { callback.run(); }
        catch (IOException expected) { check(true, what); return; }
        throw new AssertionError(what);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
