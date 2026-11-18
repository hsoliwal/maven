/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Opt-in pure-Java LevelDB storage for the same canonical M3 sealed image.
 *
 * <p>The original Iq80 LevelDB 0.12 implementation is supplied as a plugin
 * dependency and keeps ownership of its LSM index and write-ahead log.
 * This adapter never stores custom serialized Java objects, permits no remote
 * paths and cannot decide whether a task is cacheable.
 */
public final class M3LevelDbBackendProvider implements M3ModuleDbBackendProvider {
    private static final String DIRECTORY = "module-state-ext-leveldb-v1";
    private static final byte[] KEY = "m3.module.state.v2".getBytes(StandardCharsets.US_ASCII);
    private static final Class<?>[] NONE = new Class<?>[0];
    private static final Class<?>[] BOOL = {boolean.class};

    @Override public String id() { return "ext-leveldb-v1"; }

    @Override public void verifyAvailable(Path moduleRoot) throws IOException {
        M3PortableDbRuntime.path(moduleRoot, DIRECTORY, true, false);
        Class<?> factory = M3PortableDbRuntime.type("org.iq80.leveldb.impl.Iq80DBFactory");
        Class<?> options = M3PortableDbRuntime.peer(factory, "org.iq80.leveldb.Options");
        Class<?> write = M3PortableDbRuntime.peer(factory, "org.iq80.leveldb.WriteOptions");
        Class<?> db = M3PortableDbRuntime.peer(factory, "org.iq80.leveldb.DB");
        try {
            factory.getMethod("open", File.class, options);
            options.getMethod("createIfMissing", boolean.class);
            db.getMethod("get", byte[].class);
            db.getMethod("put", byte[].class, byte[].class, write);
            write.getMethod("sync", boolean.class);
        } catch (NoSuchMethodException incompatible) {
            throw new IOException("Iq80 LevelDB 0.12 API is incompatible with M3 state", incompatible);
        }
        M3PortableDbRuntime.constant(factory, "factory");
    }

    @Override public byte[] read(Path moduleRoot) throws IOException {
        Path path = M3PortableDbRuntime.path(moduleRoot, DIRECTORY, true, false);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.exists(path.resolve("CURRENT"), LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> listing = Files.list(path)) {
                if (listing.findAny().isEmpty()) return null;
            }
            throw new IOException("LevelDB state directory lacks the CURRENT manifest");
        }
        return withDb(path, false, database -> {
            Object value = M3PortableDbRuntime.call(database, "get",
                    new Class<?>[] {byte[].class}, KEY);
            if (value == null) return null;
            if (!(value instanceof byte[] image)) {
                throw new IOException("LevelDB returned a non-byte[] module image");
            }
            M3PortableDbRuntime.bounded(image);
            return image.clone();
        });
    }

    @Override public void write(Path moduleRoot, byte[] sealedImage) throws IOException {
        M3PortableDbRuntime.bounded(sealedImage);
        Path path = M3PortableDbRuntime.path(moduleRoot, DIRECTORY, true, true);
        withDb(path, true, database -> {
            Class<?> writeType = M3PortableDbRuntime.peer(database.getClass(),
                    "org.iq80.leveldb.WriteOptions");
            Object options = M3PortableDbRuntime.construct(writeType, NONE);
            Object sync = M3PortableDbRuntime.call(options, "sync", BOOL, true);
            // The sync option makes the log update durable before returning.
            // The M3 owner still checks the published sealed image by readback.
            M3PortableDbRuntime.call(database, "put",
                    new Class<?>[] {byte[].class, byte[].class, writeType},
                    KEY, sealedImage.clone(), sync);
            return null;
        });
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run(Object database) throws IOException;
    }

    private static <T> T withDb(Path directory, boolean create, Operation<T> operation) throws IOException {
        Objects.requireNonNull(operation, "operation");
        Class<?> factoryType = M3PortableDbRuntime.type("org.iq80.leveldb.impl.Iq80DBFactory");
        Class<?> optionsType = M3PortableDbRuntime.peer(factoryType, "org.iq80.leveldb.Options");
        Object options = M3PortableDbRuntime.construct(optionsType, NONE);
        Object configured = M3PortableDbRuntime.call(options, "createIfMissing", BOOL, create);
        Object factory = M3PortableDbRuntime.constant(factoryType, "factory");
        Object db = M3PortableDbRuntime.call(factory, "open",
                new Class<?>[] {File.class, optionsType}, directory.toFile(), configured);
        try {
            return operation.run(db);
        } finally {
            M3PortableDbRuntime.close(db);
        }
    }
}
