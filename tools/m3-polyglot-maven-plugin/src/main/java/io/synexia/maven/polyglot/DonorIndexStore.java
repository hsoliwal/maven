/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * {@link IndexStore} over an installed MIndexDB runtime ({@code com.synexia.mindex.db.MIndexDB}, the donor of
 * {@code jdk.m3.indexdb}), bound reflectively from the plugin's context realm first, then from this plugin. It is
 * reached only when the m3db backend is chosen and the running JDK has no {@code jdk.m3.indexdb}.
 */
final class DonorIndexStore implements IndexStore {
    static final String TYPE = "com.synexia.mindex.db.MIndexDB";

    private final Method find;
    private final Method put;
    private final AutoCloseable database;

    private DonorIndexStore(Method find, Method put, AutoCloseable database) {
        this.find = find;
        this.put = put;
        this.database = database;
    }

    /** Resolves the donor type and its three methods; any absence is a reflective failure. */
    static IndexStores.Engine engine() throws ReflectiveOperationException {
        Class<?> type = donorType();
        Method open = type.getMethod("open", Path.class);
        Method find = type.getMethod("findArtifact", CharSequence.class);
        Method put = type.getMethod("putArtifact", CharSequence.class, CharSequence.class, int.class, byte[].class);
        return new IndexStores.Engine() {
            @Override
            public String binding() {
                return "donor";
            }

            @Override
            public IndexStore open(Path root) throws IOException {
                return new DonorIndexStore(find, put, (AutoCloseable) call(open, null, "open", root));
            }
        };
    }

    private static Class<?> donorType() throws ClassNotFoundException {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) {
            try {
                return Class.forName(TYPE, true, context);
            } catch (ClassNotFoundException absentFromContext) {
                // The core extension's shared state facade may own the runtime instead.
            }
        }
        return Class.forName(TYPE, true, DonorIndexStore.class.getClassLoader());
    }

    @Override
    public String binding() {
        return "donor";
    }

    @Override
    public boolean contains(String name) throws IOException {
        return stored(name).isPresent();
    }

    @Override
    public Artifact read(String name) throws IOException {
        Object stored = stored(name).orElseThrow(() -> new IOException("Missing MIndexDB artifact: " + name));
        Object payload;
        try {
            payload = stored.getClass().getMethod("payload").invoke(stored);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            throw new IOException("cannot read MIndexDB artifact payload", failure);
        }
        if (!(payload instanceof byte[] bytes)) throw new IOException("invalid MIndexDB artifact payload");
        return new Artifact(name, text(stored, "kind"), version(stored), sha256(bytes), bytes);
    }

    @Override
    public Artifact write(String name, String kind, int version, byte[] payload) throws IOException {
        call(put, database, "putArtifact", name, kind, version, payload);
        return new Artifact(name, kind, version, sha256(payload), payload.clone());
    }

    @Override
    public void close() throws IOException {
        try {
            database.close();
        } catch (IOException | RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("cannot close MIndexDB", failure);
        }
    }

    private Optional<?> stored(String name) throws IOException {
        Object value = call(find, database, "findArtifact", name);
        return value instanceof Optional<?> found ? found : Optional.empty();
    }

    /** Artifact metadata the donor type exposes; an absent accessor reads as empty. */
    private static String text(Object stored, String accessor) {
        try {
            return String.valueOf(stored.getClass().getMethod(accessor).invoke(stored));
        } catch (ReflectiveOperationException | LinkageError absent) {
            return "";
        }
    }

    private static int version(Object stored) {
        try {
            return ((Number) stored.getClass().getMethod("formatVersion").invoke(stored)).intValue();
        } catch (ReflectiveOperationException | LinkageError | ClassCastException absent) {
            return 0;
        }
    }

    private static Object call(Method method, Object target, String name, Object... arguments) throws IOException {
        try {
            return method.invoke(target, arguments);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            throw new IOException("MIndexDB failure in " + name, failure);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
