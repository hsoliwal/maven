/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Real opt-in LmdbJava 0.9.3-backed module image, with explicit ACID transactions.
 *
 * <p>All LMDB off-heap/native resources are scoped to this operation; a
 * returned read buffer is copied before releasing its read transaction.
 * The runtime is user-supplied, and the only persistent value is the original
 * M3 sealed v2 receipt image (never a duplicate Maven cache format).
 */
public final class M3LmdbBackendProvider implements M3ModuleDbBackendProvider {
    private static final String DIRECTORY = "module-state-ext-lmdb-v1";
    private static final String DATABASE = "m3-module-state";
    private static final byte[] KEY = "sealed-image-v2".getBytes(StandardCharsets.US_ASCII);
    // LMDB reserves virtual address space, not an equivalent Java heap array.
    // Bound snapshots stay <=32 MiB; repeated transactions can reuse free pages.
    private static final long MAP_BYTES = 512L * 1024 * 1024;
    private static final Class<?>[] NONE = new Class<?>[0];

    @Override public String id() { return "ext-lmdb-v1"; }

    @Override public void verifyAvailable(Path root) throws IOException {
        M3PortableDbRuntime.path(root, DIRECTORY, true, false);
        Class<?> env = M3PortableDbRuntime.type("org.lmdbjava.Env");
        Class<?> flags = M3PortableDbRuntime.peer(env, "org.lmdbjava.DbiFlags");
        Class<?> envFlags = M3PortableDbRuntime.peer(env, "org.lmdbjava.EnvFlags");
        Class<?> putFlags = M3PortableDbRuntime.peer(env, "org.lmdbjava.PutFlags");
        Class<?> db = M3PortableDbRuntime.peer(env, "org.lmdbjava.Dbi");
        Class<?> tx = M3PortableDbRuntime.peer(env, "org.lmdbjava.Txn");
        Class<?> builder = M3PortableDbRuntime.peer(env, "org.lmdbjava.Env$Builder");
        try {
            // Pinned 0.9.3 exposes Env.Builder.open(File, EnvFlags...) and
            // Env.openDbi(String, DbiFlags...), not the 1.0-era Path and
            // createDbi/Stage3 builder API.
            builder.getMethod("open", File.class, Array.newInstance(envFlags, 0).getClass());
            env.getMethod("openDbi", String.class, Array.newInstance(flags, 0).getClass());
            db.getMethod("get", tx, Object.class);
            db.getMethod("put", tx, Object.class, Object.class,
                    Array.newInstance(putFlags, 0).getClass());
        } catch (NoSuchMethodException incompatible) {
            throw new IOException("LmdbJava 0.9.3 API incompatible with M3 portable provider", incompatible);
        }
        M3PortableDbRuntime.callStatic(env, "create", NONE);
    }

    @Override public byte[] read(Path root) throws IOException {
        Path dir = M3PortableDbRuntime.path(root, DIRECTORY, true, false);
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.exists(dir.resolve("data.mdb"), LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.list(dir)) {
                if (entries.findAny().isEmpty()) return null;
            }
            throw new IOException("LMDB module directory lacks its native data file");
        }
        return withDatabase(dir, (environment, database, transactionType) -> {
            Object tx = M3PortableDbRuntime.call(environment, "txnRead", NONE);
            try {
                Object value = M3PortableDbRuntime.call(database, "get",
                        new Class<?>[] {transactionType, Object.class}, tx, direct(KEY));
                if (value == null) return null;
                if (!(value instanceof ByteBuffer buffer)) {
                    throw new IOException("LMDB returned a non-ByteBuffer module image");
                }
                ByteBuffer copy = buffer.asReadOnlyBuffer();
                if (copy.remaining() > M3PortableDbRuntime.MAX_IMAGE) {
                    throw new IOException("LMDB module image exceeds limit");
                }
                byte[] image = new byte[copy.remaining()];
                copy.get(image);
                M3PortableDbRuntime.bounded(image);
                return image;
            } finally {
                M3PortableDbRuntime.close(tx);
            }
        });
    }

    @Override public void write(Path root, byte[] image) throws IOException {
        M3PortableDbRuntime.bounded(image);
        Path dir = M3PortableDbRuntime.path(root, DIRECTORY, true, true);
        withDatabase(dir, (environment, database, transactionType) -> {
            Object tx = M3PortableDbRuntime.call(environment, "txnWrite", NONE);
            try {
                Class<?> putFlagType = M3PortableDbRuntime.peer(database.getClass(),
                        "org.lmdbjava.PutFlags");
                Object emptyFlags = Array.newInstance(putFlagType, 0);
                Object saved = M3PortableDbRuntime.call(database, "put",
                        new Class<?>[] {transactionType, Object.class, Object.class,
                                emptyFlags.getClass()},
                        tx, direct(KEY), direct(image), emptyFlags);
                if (!Boolean.TRUE.equals(saved)) throw new IOException("LMDB module image was not stored");
                // The default LMDB flags preserve its synchronous two-page
                // ACID commit; no MDB_NOSYNC/MDB_NOMETASYNC/WRITEMAP flags.
                M3PortableDbRuntime.call(tx, "commit", NONE);
            } finally {
                M3PortableDbRuntime.close(tx);
            }
            return null;
        });
    }

    private static ByteBuffer direct(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }

    @FunctionalInterface
    private interface Action<T> {
        T run(Object environment, Object database, Class<?> transactionType) throws IOException;
    }

    private static <T> T withDatabase(Path dir, Action<T> action) throws IOException {
        Class<?> envType = M3PortableDbRuntime.type("org.lmdbjava.Env");
        Class<?> flagsType = M3PortableDbRuntime.peer(envType, "org.lmdbjava.DbiFlags");
        Class<?> envFlagsType = M3PortableDbRuntime.peer(envType, "org.lmdbjava.EnvFlags");
        Class<?> txnType = M3PortableDbRuntime.peer(envType, "org.lmdbjava.Txn");
        Object builder = M3PortableDbRuntime.callStatic(envType, "create", NONE);
        Object sized = M3PortableDbRuntime.call(builder, "setMapSize",
                new Class<?>[] {long.class}, MAP_BYTES);
        Object namedDatabases = M3PortableDbRuntime.call(sized, "setMaxDbs",
                new Class<?>[] {int.class}, 1);
        // LmdbJava 0.9.3 deliberately takes File + EnvFlags[].
        Object noEnvironmentFlags = Array.newInstance(envFlagsType, 0);
        Object env = M3PortableDbRuntime.call(namedDatabases, "open",
                new Class<?>[] {File.class, noEnvironmentFlags.getClass()},
                dir.toFile(), noEnvironmentFlags);
        try {
            // LmdbJava 0.9.3 uses Env.openDbi. The createDbi() fluent API was
            // introduced after the pinned release and cannot be used here.
            Object dbFlags = Array.newInstance(flagsType, 1);
            Array.set(dbFlags, 0, M3PortableDbRuntime.constant(flagsType, "MDB_CREATE"));
            Object db = M3PortableDbRuntime.call(env, "openDbi",
                    new Class<?>[] {String.class, dbFlags.getClass()}, DATABASE, dbFlags);
            try {
                return action.run(env, db, txnType);
            } finally {
                // LmdbJava Dbi.close() is intentionally not AutoCloseable.
                M3PortableDbRuntime.call(db, "close", NONE);
            }
        } finally {
            M3PortableDbRuntime.close(env);
        }
    }

}
