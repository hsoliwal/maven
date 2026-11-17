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
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;

/**
 * {@link IndexStore} over the running JDK's {@code jdk.m3.indexdb} (M3jdk21). The plugin is compiled for stock
 * JDK 17, so the exported public API is bound reflectively; its method set is the library form's, the same source.
 */
final class PlatformIndexStore implements IndexStore {
    private static final Class<?>[] NONE = {};
    private static final Class<?>[] NAME = {String.class};
    private static final Class<?>[] PUT = {String.class, String.class, int.class, byte[].class};

    private final Class<?> type;
    private final Object database;

    private PlatformIndexStore(Class<?> type, Object database) {
        this.type = type;
        this.database = database;
    }

    static IndexStores.Engine engine(Class<?> type) {
        return new IndexStores.Engine() {
            @Override
            public String binding() {
                return "platform";
            }

            @Override
            public IndexStore open(Path root) throws IOException {
                return new PlatformIndexStore(type, invoke(type, null, "open", new Class<?>[] {Path.class}, root));
            }
        };
    }

    @Override
    public String binding() {
        return "platform";
    }

    @Override
    public boolean contains(String name) throws IOException {
        return (Boolean) invoke(type, database, "containsArtifact", NAME, name);
    }

    @Override
    public Artifact read(String name) throws IOException {
        return artifact(invoke(type, database, "requireArtifact", NAME, name));
    }

    @Override
    public Artifact write(String name, String kind, int version, byte[] payload) throws IOException {
        return artifact(invoke(type, database, "putArtifact", PUT, name, kind, version, payload));
    }

    @Override
    public void close() throws IOException {
        invoke(type, database, "close", NONE);
    }

    private static Artifact artifact(Object stored) throws IOException {
        Class<?> record = stored.getClass();
        return new Artifact((String) invoke(record, stored, "name", NONE),
                (String) invoke(record, stored, "kind", NONE),
                (Integer) invoke(record, stored, "formatVersion", NONE),
                (String) invoke(record, stored, "contentSha256", NONE),
                (byte[]) invoke(record, stored, "payload", NONE));
    }

    /** Unwraps the platform's own I/O and argument failures; anything else is a binding failure. */
    private static Object invoke(Class<?> owner, Object target, String name, Class<?>[] types, Object... arguments)
            throws IOException {
        try {
            return owner.getMethod(name, types).invoke(target, arguments);
        } catch (InvocationTargetException thrown) {
            Throwable cause = thrown.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException("jdk.m3.indexdb failure in " + name, cause);
        } catch (ReflectiveOperationException unavailable) {
            throw new IOException("jdk.m3.indexdb binding lacks " + name, unavailable);
        }
    }
}
