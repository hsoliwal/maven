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
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Explicit, pure-Java Apache Derby 10.16 embedded store for the canonical
 * M3 module-state image. The selected Derby JARs are optional Maven plugin
 * dependencies; the original sealed v2 payload and the M3 module lease remain
 * the sole cache authority.
 *
 * <p>Derby is a directory database. Reads never create a database; writes
 * create it if needed and commit one bounded BLOB transactionally. After each
 * operation the embedded database is shut down to release its JVM-owned file
 * lock, so a different Maven JVM can reopen it.
 */
public final class M3DerbyBackendProvider implements M3ModuleDbBackendProvider {
    private static final String DIRECTORY = "module-state-ext-derby-v1";
    private static final String DRIVER = "org.apache.derby.jdbc.EmbeddedDriver";
    private static final String TABLE = "M3_MODULE_SNAPSHOT";
    private static final String DUPLICATE_TABLE = "X0Y32";
    private static final String CLEAN_SHUTDOWN = "08006";
    private static final int MAX_IMAGE = M3PortableDbRuntime.MAX_IMAGE;

    @Override
    public String id() {
        return "ext-derby-v1";
    }

    @Override
    public void verifyAvailable(Path moduleRoot) throws IOException {
        Path directory = M3PortableDbRuntime.path(moduleRoot, DIRECTORY, true, false);
        // Reject URL delimiters before Maven acquires/rotates a cache
        // generation or runs any output-producing build task.
        url(directory);
        Class<?> type = M3PortableDbRuntime.type(DRIVER);
        if (!Driver.class.isAssignableFrom(type)) {
            throw new IOException("Derby embedded runtime is not a JDBC Driver");
        }
        try {
            type.getConstructor();
        } catch (NoSuchMethodException missing) {
            throw new IOException("Apache Derby embedded driver has no public constructor", missing);
        }
    }

    @Override
    public byte[] read(Path moduleRoot) throws IOException {
        Path dir = M3PortableDbRuntime.path(moduleRoot, DIRECTORY, true, false);
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return null;
        // An interrupted CREATE may leave an empty directory. Never allow a
        // read to reconstruct the database or silently accept an unknown layout.
        Path catalog = dir.resolve("service.properties");
        if (!Files.isRegularFile(catalog, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> listing = Files.list(dir)) {
                if (listing.findAny().isEmpty()) return null;
            }
            throw new IOException("Derby module catalog is missing or not regular");
        }
        try (Session session = Session.open(driver(), dir, false);
                PreparedStatement query = session.connection.prepareStatement(
                        "SELECT image FROM " + TABLE + " WHERE slot = 1");
                ResultSet rows = query.executeQuery()) {
            if (!rows.next()) return null;
            Blob blob = rows.getBlob(1);
            if (blob == null) throw new IOException("Derby module image is NULL");
            try {
                long size = blob.length();
                if (size < 48 || size > MAX_IMAGE) {
                    throw new IOException("Derby module image exceeds sealed budget");
                }
                try (InputStream stream = blob.getBinaryStream()) {
                    byte[] image = stream.readNBytes(MAX_IMAGE + 1);
                    if (image.length != size || rows.next()) {
                        throw new IOException("Derby module image size/uniqueness mismatch");
                    }
                    return image;
                }
            } finally {
                blob.free();
            }
        } catch (SQLException error) {
            throw new IOException("Derby module-state read failed", error);
        }
    }

    @Override
    public void write(Path moduleRoot, byte[] sealedImage) throws IOException {
        M3PortableDbRuntime.bounded(sealedImage);
        // The Derby engine creates this directory. Precreating the path with
        // Files.createDirectory can make Derby's create=true fail or misidentify
        // a half-created store.
        Path dir = M3PortableDbRuntime.path(moduleRoot, DIRECTORY, true, false);
        boolean create = !Files.exists(dir, LinkOption.NOFOLLOW_LINKS);
        try (Session session = Session.open(driver(), dir, create)) {
            Connection connection = session.connection;
            connection.setAutoCommit(false);
            try {
                try (Statement setup = connection.createStatement()) {
                    try {
                        setup.execute("CREATE TABLE " + TABLE
                                + " (slot INTEGER PRIMARY KEY, image BLOB(32M) NOT NULL)");
                    } catch (SQLException existing) {
                        if (!DUPLICATE_TABLE.equals(existing.getSQLState())) throw existing;
                    }
                }
                try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM " + TABLE + " WHERE slot = 1");
                        PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO " + TABLE + " (slot, image) VALUES (1, ?)")) {
                    delete.executeUpdate();
                    insert.setBytes(1, sealedImage.clone());
                    if (insert.executeUpdate() != 1) {
                        throw new SQLException("Derby receipt image insert did not update exactly one row");
                    }
                }
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
            throw new IOException("Derby module-state commit failed", error);
        }
    }

    private static Driver driver() throws IOException {
        Class<?> type = M3PortableDbRuntime.type(DRIVER);
        if (!Driver.class.isAssignableFrom(type)) {
            throw new IOException("Derby embedded runtime does not implement JDBC Driver");
        }
        try {
            return type.asSubclass(Driver.class).getConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError error) {
            throw new IOException("Apache Derby 10.16 embedded driver unavailable", error);
        }
    }

    private static String url(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        String local = directory.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (local.indexOf(';') >= 0 || local.indexOf('?') >= 0 || local.indexOf('#') >= 0
                || local.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("unsafe Derby database location");
        }
        // No host, port or JDBC network subprotocol is ever accepted.
        return "jdbc:derby:" + local;
    }

    private static final class Session implements AutoCloseable {
        final Connection connection;
        private final Driver driver;
        private final String url;

        private Session(Connection connection, Driver driver, String url) {
            this.connection = connection;
            this.driver = driver;
            this.url = url;
        }

        static Session open(Driver driver, Path dir, boolean create) throws IOException {
            String url = url(dir);
            try {
                Connection connection = driver.connect(url + (create ? ";create=true" : ""),
                        new Properties());
                if (connection == null) throw new SQLException("Derby refused embedded file URL");
                return new Session(connection, driver, url);
            } catch (SQLException error) {
                throw new IOException("Derby database could not be opened", error);
            }
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                connection.close();
            } catch (SQLException error) {
                failure = new IOException("Derby connection close failed", error);
            }
            try {
                Connection unexpected = driver.connect(url + ";shutdown=true", new Properties());
                if (unexpected != null) {
                    try {
                        unexpected.close();
                    } catch (SQLException error) {
                        throw new IOException("unexpected Derby shutdown connection close failure", error);
                    }
                }
                throw new IOException("Derby did not confirm embedded database shutdown");
            } catch (SQLException shutdown) {
                // An SQLState 08006 is Derby's documented successful shutdown
                // signal; unexpected failures must not be masked.
                if (!CLEAN_SHUTDOWN.equals(shutdown.getSQLState())) {
                    IOException invalid = new IOException("Derby embedded database shutdown failed", shutdown);
                    if (failure != null) failure.addSuppressed(invalid);
                    else failure = invalid;
                }
            } catch (IOException error) {
                if (failure != null) failure.addSuppressed(error);
                else failure = error;
            }
            if (failure != null) throw failure;
        }
    }
}
