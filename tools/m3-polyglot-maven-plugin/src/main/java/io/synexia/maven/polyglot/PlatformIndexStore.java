/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * {@link IndexStore} over the {@code jdk.m3.indexdb} module of the running JDK (M3jdk21). The plugin is
 * compiled for stock JDK 17, so the exported public API is bound reflectively; the method set is the one
 * of the library form, which is the same source.
 */
final class PlatformIndexStore implements IndexStore {
    private static final Class<?>[] NAME = {String.class};
    private static final Class<?>[] PUT = {String.class, String.class, int.class, byte[].class};

    private final Class<?> type;
    private final Object database;

    private PlatformIndexStore(Class<?> type, Object database) {
        this.type = type;
        this.database = database;
    }

    static IndexStore open(Class<?> type, Path root) throws IOException {
        return new PlatformIndexStore(type, invoke(type, null, "open", new Class<?>[] {Path.class}, root));
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
        invoke(type, database, "close", new Class<?>[0]);
    }

    private static Artifact artifact(Object stored) throws IOException {
        Class<?> record = stored.getClass();
        return new Artifact((String) invoke(record, stored, "name", new Class<?>[0]),
                (String) invoke(record, stored, "kind", new Class<?>[0]),
                (Integer) invoke(record, stored, "formatVersion", new Class<?>[0]),
                (String) invoke(record, stored, "contentSha256", new Class<?>[0]),
                (byte[]) invoke(record, stored, "payload", new Class<?>[0]));
    }

    /** Unwraps the platform's own I/O and argument failures; anything else is a binding failure. */
    private static Object invoke(Class<?> owner, Object target, String name, Class<?>[] types, Object... arguments)
            throws IOException {
        try {
            Method method = owner.getMethod(name, types);
            return method.invoke(target, arguments);
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
