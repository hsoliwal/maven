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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Optional local JDBC storage for the canonical sealed ModuleStateDb image.
 * No driver is bundled. SQLite/H2 handle their own transaction journaling.
 */
final class M3ModuleJdbcStorage {
    private static final int MAX_IMAGE = 32 * 1024 * 1024;
    private static final String TABLE = "M3_MODULE_SNAPSHOT";
    private final Path file;
    private final String url;
    private final boolean sqlite;

    M3ModuleJdbcStorage(Path moduleRoot, String backend) throws IOException {
        Objects.requireNonNull(moduleRoot, "moduleRoot");
        Path root = moduleRoot.toAbsolutePath().normalize();
        sqlite = "sqlite".equals(backend);
        if (!sqlite && !"h2".equals(backend)) {
            throw new IllegalArgumentException("unsupported JDBC module backend: " + backend);
        }
        String driver = sqlite ? "org.sqlite.JDBC" : "org.h2.Driver";
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException missing) {
            throw new IOException("explicit " + backend + " JDBC driver is absent from the plugin classpath", missing);
        }
        file = root.resolve(sqlite ? ".m3/module-state.sqlite" : ".m3/module-state.mv.db");
        Path base = root.resolve(".m3/module-state");
        String location = (sqlite ? file : base).toString().replace('\\', '/');
        // Embedded database URL parsers treat these as parameters, not filename characters.
        if (location.indexOf(';') >= 0 || location.indexOf('?') >= 0
                || location.indexOf('#') >= 0 || location.indexOf('\n') >= 0) {
            throw new IOException("unsafe character in embedded JDBC module path");
        }
        url = sqlite ? "jdbc:sqlite:" + location : "jdbc:h2:file:" + location;
        checkPaths();
    }

    byte[] read() throws IOException {
        checkPaths();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try (Connection connection = DriverManager.getConnection(url);
                PreparedStatement query = connection.prepareStatement(
                        "SELECT image FROM " + TABLE + " WHERE slot = 1");
                ResultSet found = query.executeQuery()) {
            if (!found.next()) return null;
            try (InputStream in = found.getBinaryStream(1)) {
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
        try (Connection connection = DriverManager.getConnection(url)) {
            // SQLite's rollback journal is local and transient; avoid WAL mode's extra long-lived files.
            if (sqlite) {
                try (Statement pragma = connection.createStatement()) {
                    pragma.execute("PRAGMA journal_mode=DELETE");
                    pragma.execute("PRAGMA synchronous=FULL");
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
            } catch (SQLException | RuntimeException error) {
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

    private void checkPaths() throws IOException {
        Path directory = file.getParent();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("module JDBC state directory is not an ordinary directory");
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("module JDBC state cannot be a symlink or special file");
        }
    }
}
