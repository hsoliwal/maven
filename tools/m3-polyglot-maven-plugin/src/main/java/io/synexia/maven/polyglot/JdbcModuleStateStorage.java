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
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Optional donor backends for one module's sealed M3 state image.
 *
 * <p>The canonical M3DB artifact format is identical in every backend.
 * SQLite, H2 and Derby are operator-selected JDBC engines; none is copied,
 * shaded, downloaded or required by Maven bootstrap. The surrounding
 * ModuleStateDb owns module-wide locking, schema version and integrity seals.
 */
final class JdbcModuleStateStorage {
    private static final int MAX_IMAGE = 32 * 1024 * 1024;
    private static final String TABLE = "M3_MODULE_STATE";
    private static final String CREATE =
            "CREATE TABLE M3_MODULE_STATE (STATE_ID INTEGER NOT NULL PRIMARY KEY,"
                    + " IMAGE BLOB(33554432) NOT NULL)";
    private final String url;

    JdbcModuleStateStorage(Path project, String backend) throws IOException {
        Objects.requireNonNull(project, "project");
        Path state = project.resolve(".m3").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(state)) throw new IOException("module state directory is a symlink");
        try {
            switch (backend) {
                case "sqlite" -> {
                    Class.forName("org.sqlite.JDBC");
                    Path file = state.resolve("module-state.sqlite");
                    refuseSymlink(file);
                    url = "jdbc:sqlite:" + file;
                }
                case "h2" -> {
                    Class.forName("org.h2.Driver");
                    Path file = state.resolve("module-state-h2.mv.db");
                    refuseSymlink(file);
                    url = "jdbc:h2:file:" + state.resolve("module-state-h2");
                }
                case "derby" -> {
                    Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
                    Path directory = state.resolve("module-state-derby");
                    refuseSymlink(directory);
                    url = "jdbc:derby:" + directory + ";create=true";
                }
                default -> throw new IllegalArgumentException("unsupported JDBC module store: " + backend);
            }
        } catch (ClassNotFoundException missing) {
            throw new IOException("optional " + backend + " JDBC driver is missing from the plugin classpath", missing);
        }
    }

    byte[] read() throws IOException {
        try (Connection connection = DriverManager.getConnection(url)) {
            if (!tableExists(connection)) return null;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT IMAGE FROM " + TABLE + " WHERE STATE_ID = 1");
                    ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                Blob blob = rows.getBlob(1);
                if (blob == null || blob.length() > MAX_IMAGE) {
                    throw new IOException("oversized or missing module-state BLOB");
                }
                try (InputStream stream = blob.getBinaryStream()) {
                    byte[] bytes = stream.readNBytes(MAX_IMAGE + 1);
                    if (bytes.length > MAX_IMAGE) throw new IOException("module-state BLOB exceeds size cap");
                    return bytes;
                } finally {
                    blob.free();
                }
            }
        } catch (SQLException error) {
            throw new IOException("cannot read embedded JDBC module-state DB", error);
        }
    }

    void write(byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        if (payload.length > MAX_IMAGE) throw new IOException("module-state payload exceeds size cap");
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            try {
                ensureSchema(connection);
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM " + TABLE + " WHERE STATE_ID = 1");
                        PreparedStatement insert = connection.prepareStatement(
                                "INSERT INTO " + TABLE + " (STATE_ID, IMAGE) VALUES (1, ?)")) {
                    delete.executeUpdate();
                    insert.setBytes(1, payload);
                    if (insert.executeUpdate() != 1) throw new SQLException("module-state insert not committed");
                }
                connection.commit();
            } catch (SQLException | RuntimeException failed) {
                try { connection.rollback(); }
                catch (SQLException rollback) { failed.addSuppressed(rollback); }
                throw failed;
            }
        } catch (SQLException error) {
            throw new IOException("cannot commit embedded JDBC module-state DB", error);
        }
    }

    private static boolean tableExists(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet tables = metadata.getTables(null, null, TABLE, new String[] {"TABLE"})) {
            if (tables.next()) return true;
        }
        // Drivers may expose lower-case SQLite identifiers via getTables.
        try (ResultSet tables = metadata.getTables(null, null, TABLE.toLowerCase(java.util.Locale.ROOT),
                new String[] {"TABLE"})) {
            return tables.next();
        }
    }

    private static void ensureSchema(Connection connection) throws SQLException {
        if (tableExists(connection)) return;
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(CREATE);
        } catch (SQLException error) {
            if (!tableExists(connection)) throw error;
        }
    }

    private static void refuseSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("module-state database is a symlink: " + path);
        Path parent = path.getParent();
        if (parent != null && Files.exists(parent, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(parent)) {
            throw new IOException("module-state database parent is a symlink: " + parent);
        }
    }
}
