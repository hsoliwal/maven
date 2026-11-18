/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Real, explicitly selected RocksJava/JNI backend for one sealed M3 module image.
 *
 * <p>Only stable, documented RocksDB APIs are invoked reflectively, so Maven
 * neither bundles RocksDB JNI/native code nor acquires a mandatory dependency.
 * A synchronous WAL write publishes the image; the M3 owner still validates
 * its SHA-256 seal, generation, module lease and exact read-back.
 */
public final class M3RocksDbBackendProvider implements M3ModuleDbBackendProvider {
    private static final String DIRECTORY = "module-state-ext-rocksdb-v1";
    private static final byte[] KEY = "m3.module.state.v2".getBytes(StandardCharsets.US_ASCII);
    private static final Class<?>[] NONE = new Class<?>[0];
    private static final Class<?>[] BOOL = {boolean.class};
    private static final Class<?>[] KEY_BUFFER = {byte[].class, byte[].class};

    @Override public String id() { return "ext-rocksdb-v1"; }

    @Override public void verifyAvailable(Path root) throws IOException {
        M3PortableDbRuntime.path(root, DIRECTORY, true, false);
        Class<?> rocks = M3PortableDbRuntime.type("org.rocksdb.RocksDB");
        M3PortableDbRuntime.peer(rocks, "org.rocksdb.Options");
        M3PortableDbRuntime.peer(rocks, "org.rocksdb.WriteOptions");
        M3PortableDbRuntime.callStatic(rocks, "loadLibrary", NONE);
    }

    @Override public byte[] read(Path root) throws IOException {
        Path directory = M3PortableDbRuntime.path(root, DIRECTORY, true, false);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return null;
        // An interrupted first write can leave an empty directory. Do not open
        // it with createIfMissing, which would hide an otherwise corrupt store.
        if (!Files.exists(directory.resolve("CURRENT"), LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> entries = Files.list(directory)) {
                if (entries.findAny().isEmpty()) return null;
            }
            throw new IOException("RocksDB module directory lacks its CURRENT manifest");
        }
        return withStore(directory, false, (rocks, database) -> {
            byte[] buffer = new byte[64 * 1024];
            int length = (int) M3PortableDbRuntime.call(database, "get", KEY_BUFFER, KEY, buffer);
            if (length < 0) return null;
            if (length > M3PortableDbRuntime.MAX_IMAGE) throw new IOException("RocksDB module image exceeds limit");
            if (length > buffer.length) {
                buffer = new byte[length];
                int actual = (int) M3PortableDbRuntime.call(database, "get", KEY_BUFFER, KEY, buffer);
                if (actual != length) throw new IOException("RocksDB module image changed during read");
            }
            return Arrays.copyOf(buffer, length);
        });
    }

    @Override public void write(Path root, byte[] image) throws IOException {
        M3PortableDbRuntime.bounded(image);
        Path directory = M3PortableDbRuntime.path(root, DIRECTORY, true, true);
        withStore(directory, true, (rocks, database) -> {
            Class<?> writeType = M3PortableDbRuntime.peer(rocks, "org.rocksdb.WriteOptions");
            Object option = M3PortableDbRuntime.construct(writeType, NONE);
            try {
                // RocksDB's default WAL is deliberately left enabled. The
                // synchronous write waits for WAL durability at commit.
                M3PortableDbRuntime.call(option, "setSync", BOOL, true);
                M3PortableDbRuntime.call(database, "put",
                        new Class<?>[] {writeType, byte[].class, byte[].class},
                        option, KEY, image.clone());
            } finally {
                M3PortableDbRuntime.close(option);
            }
            return null;
        });
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run(Class<?> rocks, Object database) throws IOException;
    }

    private static <T> T withStore(Path directory, boolean create, Operation<T> operation) throws IOException {
        Objects.requireNonNull(operation, "operation");
        Class<?> rocks = M3PortableDbRuntime.type("org.rocksdb.RocksDB");
        M3PortableDbRuntime.callStatic(rocks, "loadLibrary", NONE);
        Class<?> optionsType = M3PortableDbRuntime.peer(rocks, "org.rocksdb.Options");
        Object options = M3PortableDbRuntime.construct(optionsType, NONE);
        try {
            M3PortableDbRuntime.call(options, "setCreateIfMissing", BOOL, create);
            Object database = M3PortableDbRuntime.callStatic(rocks, "open",
                    new Class<?>[] {optionsType, String.class}, options, directory.toString());
            try {
                return operation.run(rocks, database);
            } finally {
                M3PortableDbRuntime.close(database);
            }
        } finally {
            M3PortableDbRuntime.close(options);
        }
    }
}
