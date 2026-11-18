/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Properties;

/**
 * Optional local JDBC storage for the canonical sealed ModuleStateDb image.
 * No driver is bundled. SQLite/H2 handle their own transaction journaling.
 * The common M3 module lease and generation marker surround these image commits.
 */
final class M3ModuleJdbcStorage {
    static final String SQLITE_FILE = ".m3/module-state.sqlite";
    static final String H2_FILE = ".m3/module-state.mv.db";
    private static final int MAX_IMAGE = 32 * 1024 * 1024;
    private static final String TABLE = "M3_MODULE_SNAPSHOT";
    private final Path file;
    private final String url;
    private final boolean sqlite;
    private final Driver driver;

    M3ModuleJdbcStorage(Path moduleRoot, String backend) throws IOException {
        Objects.requireNonNull(moduleRoot, "moduleRoot");
        Path root = moduleRoot.toAbsolutePath().normalize();
        sqlite = "sqlite".equals(backend);
        if (!sqlite && !"h2".equals(backend)) {
            throw new IllegalArgumentException("unsupported JDBC module backend: " + backend);
        }
        file = root.resolve(sqlite ? SQLITE_FILE : H2_FILE);
        Path base = root.resolve(".m3/module-state");
        String location = (sqlite ? file : base).toString().replace('\\', '/');
        // Embedded database URL parsers treat these as parameters, not filename characters.
        if (location.indexOf(';') >= 0 || location.indexOf('?') >= 0
                || location.indexOf('#') >= 0 || location.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("unsafe character in embedded JDBC module path");
        }
        // H2 otherwise permits a delay between commit and forcing its transaction log.
        url = sqlite ? "jdbc:sqlite:" + location : "jdbc:h2:file:" + location + ";WRITE_DELAY=0";
        checkPaths();
        driver = loadDriver(backend, sqlite ? "org.sqlite.JDBC" : "org.h2.Driver");
    }

    byte[] read() throws IOException {
        checkPaths();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        String length = sqlite ? "length(CAST(image AS BLOB))" : "OCTET_LENGTH(image)";
        // SQLite's getBinaryStream first calls getBytes. Bound the projected
        // column inside this same SELECT, before a JDBC driver can materialize it.
        try (Connection connection = connect();
                PreparedStatement query = connection.prepareStatement(
                        "SELECT " + length + ", CASE WHEN " + length + " BETWEEN 1 AND " + MAX_IMAGE
                                + " THEN image ELSE NULL END FROM " + TABLE + " WHERE slot = 1");
                ResultSet found = query.executeQuery()) {
            if (!found.next()) return null;
            long size = found.getLong(1);
            if (found.wasNull() || size < 1 || size > MAX_IMAGE) {
                throw new IOException("module JDBC state image outside size limit");
            }
            try (InputStream in = found.getBinaryStream(2)) {
                if (in == null) throw new IOException("module JDBC state has null image");
                byte[] image = in.readNBytes(MAX_IMAGE + 1);
                if (image.length > MAX_IMAGE) throw new IOException("module JDBC state exceeds limit");
                if (found.next()) throw new IOException("duplicate module JDBC state");
                return image;
            }
        } catch (SQLException error) {
            throw new IOException("module JDBC state read failed", error);
        }
    }

    void write(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > MAX_IMAGE) {
            throw new IOException("module JDBC image outside sealed snapshot budget");
        }
        checkPaths();
        try (Connection connection = connect()) {
            // SQLite's rollback journal is local and transient; avoid WAL mode's extra long-lived files.
            if (sqlite) {
                try (Statement pragma = connection.createStatement();
                        ResultSet mode = pragma.executeQuery("PRAGMA journal_mode=DELETE")) {
                    if (!mode.next() || !"delete".equalsIgnoreCase(mode.getString(1))) {
                        throw new SQLException("SQLite did not enable its local rollback journal");
                    }
                }
                // EXTRA includes the containing-directory sync when DELETE commits by
                // unlinking the rollback journal; FULL alone need not preserve that unlink.
                try (Statement pragma = connection.createStatement()) {
                    pragma.execute("PRAGMA synchronous=EXTRA");
                    try (ResultSet setting = pragma.executeQuery("PRAGMA synchronous")) {
                        if (!setting.next() || setting.getInt(1) != 3) {
                            throw new SQLException("SQLite did not enable synchronous EXTRA");
                        }
                    }
                }
            }
            try (Statement setup = connection.createStatement()) {
                setup.execute("CREATE TABLE IF NOT EXISTS " + TABLE
                        + " (slot INTEGER PRIMARY KEY, image BLOB NOT NULL)");
            }
            connection.setAutoCommit(false);
            try (PreparedStatement erase = connection.prepareStatement(
                    "DELETE FROM " + TABLE + " WHERE slot = 1");
                    PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO " + TABLE + " (slot, image) VALUES (1, ?)")) {
                erase.executeUpdate();
                insert.setBytes(1, bytes);
                if (insert.executeUpdate() != 1) throw new SQLException("module snapshot insert count");
                connection.commit();
            } catch (SQLException | RuntimeException | Error error) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    error.addSuppressed(rollback);
                }
                throw error;
            }
        } catch (SQLException error) {
            throw new IOException("module JDBC state commit failed", error);
        }
    }

    private static Driver loadDriver(String backend, String name) throws IOException {
        try {
            ClassLoader own = M3ModuleJdbcStorage.class.getClassLoader();
            ClassLoader context = Thread.currentThread().getContextClassLoader();
            Class<?> type = null;
            if (context != null) {
                try { type = Class.forName(name, true, context); }
                catch (ClassNotFoundException absentFromPlugin) {
                    // The shared core-extension realm may also provide this explicit driver.
                }
            }
            if (type == null) type = Class.forName(name, true, own);
            return type.asSubclass(Driver.class).getConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError | ClassCastException unavailable) {
            throw new M3DbModuleStore.BackendUnavailableException(
                    "explicit " + backend + " JDBC driver is unavailable on the plugin classpath", unavailable);
        }
    }

    private Connection connect() throws SQLException, IOException {
        checkPaths();
        // DriverManager filters registered drivers using its caller's defining realm;
        // the selected actual Driver can instead reside solely in the plugin context realm.
        Connection connection = driver.connect(url, new Properties());
        if (connection == null) throw new SQLException("selected JDBC driver rejected its local module URL");
        return connection;
    }

    static boolean statePath(String relative) {
        String name = relative.replace('\\', '/');
        return name.equals(SQLITE_FILE) || name.startsWith(SQLITE_FILE + "-")
                || name.equals(H2_FILE) || name.startsWith(H2_FILE + ".")
                || name.equals(".m3/module-state.lock.db") || name.equals(".m3/module-state.trace.db")
                || name.equals(".m3/module-state.temp.db") || name.startsWith(".m3/module-state.temp.db.")
                || name.equals(".m3/module-state.h2.db") || name.equals(".m3/module-state.lobs.db");
    }

    private void checkPaths() throws IOException {
        Path directory = file.getParent();
        for (Path at = directory; at != null; at = at.getParent()) {
            if (Files.isSymbolicLink(at)) throw new IOException("symlink in module JDBC path");
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("module JDBC state directory is not an ordinary directory");
        }
        // Journals, WAL/shared-memory and H2 lock/recovery files are part of the same
        // storage authority; a safe main file alone does not make these paths safe.
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (statePath(".m3/" + entry.getFileName())
                        && !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("module JDBC state or sidecar is not an ordinary file: " + entry.getFileName());
                }
            }
        }
    }
}
