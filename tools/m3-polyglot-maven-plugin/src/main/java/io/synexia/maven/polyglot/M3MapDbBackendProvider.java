/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

/**
 * Real, opt-in MapDB 3 file-backed transactional map storing one sealed M3 image.
 *
 * <p>The selected user-supplied MapDB runtime owns its WAL, page cache and
 * recovery. Maven retains the SHA-256, generation, module lease and cache
 * admission; it does not copy/ship MapDB or deserialize arbitrary Java objects.
 */
public final class M3MapDbBackendProvider implements M3ModuleDbBackendProvider {
    private static final String FILE = "module-state-ext-mapdb-v1.db";
    private static final String MAP = "m3-module-state-v2";
    private static final String KEY = "sealed-image";
    private static final Class<?>[] NONE = new Class<?>[0];

    @Override public String id() { return "ext-mapdb-v1"; }

    @Override public void verifyAvailable(Path root) throws IOException {
        M3PortableDbRuntime.path(root, FILE, false, false);
        Class<?> maker = M3PortableDbRuntime.type("org.mapdb.DBMaker");
        Class<?> serializer = M3PortableDbRuntime.peer(maker, "org.mapdb.Serializer");
        M3PortableDbRuntime.peer(maker, "org.mapdb.DB");
        M3PortableDbRuntime.constant(serializer, "STRING");
        M3PortableDbRuntime.constant(serializer, "BYTE_ARRAY");
    }

    @Override public byte[] read(Path root) throws IOException {
        Path file = M3PortableDbRuntime.path(root, FILE, false, false);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        return withMap(file, (db, values) -> {
            Object value = values.get(KEY);
            if (value == null) return null;
            if (!(value instanceof byte[] image)) {
                throw new IOException("MapDB module value is not a raw byte-array snapshot");
            }
            M3PortableDbRuntime.bounded(image);
            return image.clone();
        });
    }

    @Override public void write(Path root, byte[] image) throws IOException {
        M3PortableDbRuntime.bounded(image);
        Path file = M3PortableDbRuntime.path(root, FILE, false, true);
        withMap(file, (db, values) -> {
            try {
                values.put(KEY, image.clone());
                M3PortableDbRuntime.call(db, "commit", NONE);
            } catch (IOException | RuntimeException failure) {
                try { M3PortableDbRuntime.call(db, "rollback", NONE); }
                catch (IOException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
            return null;
        });
    }

    @FunctionalInterface
    private interface Action<T> {
        T run(Object database, Map<String, Object> values) throws IOException;
    }

    private static <T> T withMap(Path file, Action<T> action) throws IOException {
        Class<?> makerType = M3PortableDbRuntime.type("org.mapdb.DBMaker");
        Class<?> serializerType = M3PortableDbRuntime.peer(makerType, "org.mapdb.Serializer");
        Object maker = M3PortableDbRuntime.callStatic(makerType, "fileDB",
                new Class<?>[] {File.class}, file.toFile());
        // MapDB's transactional mode enables its WAL; the default non-WAL
        // mode would not give the module state a recoverable commit boundary.
        Object transactional = M3PortableDbRuntime.call(maker, "transactionEnable", NONE);
        Object database = M3PortableDbRuntime.call(transactional, "make", NONE);
        try {
            Object string = M3PortableDbRuntime.constant(serializerType, "STRING");
            Object bytes = M3PortableDbRuntime.constant(serializerType, "BYTE_ARRAY");
            Object collectionMaker = M3PortableDbRuntime.call(database, "hashMap",
                    new Class<?>[] {String.class, serializerType, serializerType}, MAP, string, bytes);
            Object opened = M3PortableDbRuntime.call(collectionMaker, "createOrOpen", NONE);
            if (!(opened instanceof Map<?, ?> map)) {
                throw new IOException("MapDB did not return a standard Java map");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> values = (Map<String, Object>) map;
            return action.run(database, values);
        } finally {
            M3PortableDbRuntime.close(database);
        }
    }
}
