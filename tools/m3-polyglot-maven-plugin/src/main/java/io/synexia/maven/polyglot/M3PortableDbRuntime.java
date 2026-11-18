/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Strict optional-library and module-local path boundary for portable M3 DB backends.
 *
 * <p>Only the original M3 receipt image crosses the SPI. Backend binaries are
 * supplied by the user and loaded from the selected Maven plugin realm, never
 * downloaded or vendored into the public Maven fork. Neither this class nor a
 * provider decides whether a build is up-to-date.
 */
final class M3PortableDbRuntime {
    static final int MAX_IMAGE = M3DbModuleStore.MAX_PAYLOAD;
    private static final int MAX_FILES = 65_536;
    private M3PortableDbRuntime() {}

    static Path path(Path moduleRoot, String suffix, boolean directory, boolean create) throws IOException {
        Objects.requireNonNull(moduleRoot, "moduleRoot");
        if (!suffix.matches("module-state-ext-[a-z0-9-]{1,48}(?:[.]db)?")) {
            throw new IllegalArgumentException("invalid local portable DB filename");
        }
        if (Files.isSymbolicLink(moduleRoot)) throw new IOException("symlinked module root");
        Path root = moduleRoot.toRealPath();
        Path state = root.resolve(".m3");
        if (Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
            StateFiles.checkDirectory(state);
        } else if (create) {
            Files.createDirectory(state);
        } else {
            return state.resolve(suffix);
        }
        Path target = state.resolve(suffix);
        if (directory) {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                StateFiles.checkDirectory(target);
                try (Stream<Path> items = Files.walk(target)) {
                    var iterator = items.iterator();
                    int count = 0;
                    while (iterator.hasNext()) {
                        Path entry = iterator.next();
                        if (++count > MAX_FILES) throw new IOException("portable DB directory entry budget");
                        if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                                && !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                            throw new IOException("symlink/special file in portable DB: " + entry);
                        }
                    }
                }
            } else if (create) {
                Files.createDirectory(target);
            }
        } else {
            StateFiles.checkFile(target);
            // Journals, lock files and other sidecars are just as security
            // sensitive as the data file. Never follow a user-supplied link.
            try (Stream<Path> entries = Files.list(state)) {
                var iterator = entries.iterator();
                while (iterator.hasNext()) {
                    Path entry = iterator.next();
                    if (entry.getFileName().toString().startsWith(suffix)
                            && !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("symlink/special portable DB sidecar: " + entry);
                    }
                }
            }
        }
        return target;
    }

    static Class<?> type(String name) throws IOException {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        try {
            if (context != null) {
                try { return Class.forName(name, true, context); }
                catch (ClassNotFoundException absent) { /* Check the plugin's own realm. */ }
            }
            return Class.forName(name, true, M3PortableDbRuntime.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError missing) {
            throw new IOException("optional portable DB class is unavailable: " + name, missing);
        }
    }

    static Class<?> peer(Class<?> owner, String name) throws IOException {
        try { return Class.forName(name, true, owner.getClassLoader()); }
        catch (ClassNotFoundException | LinkageError missing) {
            throw new IOException("optional portable DB peer unavailable: " + name, missing);
        }
    }

    static Object construct(Class<?> type, Class<?>[] parameterTypes, Object... args) throws IOException {
        try { return type.getConstructor(parameterTypes).newInstance(args); }
        catch (ReflectiveOperationException | RuntimeException error) {
            throw new IOException("cannot initialize optional DB type: " + type.getName(), cause(error));
        }
    }

    static Object callStatic(Class<?> type, String name, Class<?>[] signature, Object... args)
            throws IOException {
        try { return type.getMethod(name, signature).invoke(null, args); }
        catch (ReflectiveOperationException | RuntimeException error) {
            throw new IOException("portable DB static method failed: " + type.getName() + "." + name, cause(error));
        }
    }

    static Object call(Object receiver, String name, Class<?>[] signature, Object... args)
            throws IOException {
        Objects.requireNonNull(receiver, "receiver");
        try {
            Method method = receiver.getClass().getMethod(name, signature);
            return method.invoke(receiver, args);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IOException("portable DB operation failed: "
                    + receiver.getClass().getName() + "." + name, cause(error));
        }
    }

    static void close(Object value) throws IOException {
        if (value == null) return;
        if (!(value instanceof AutoCloseable handle)) {
            throw new IOException("portable DB did not provide a closeable resource");
        }
        try { handle.close(); }
        catch (Exception | LinkageError error) {
            throw new IOException("portable DB resource cleanup failed", error);
        }
    }

    static void bounded(byte[] image) throws IOException {
        Objects.requireNonNull(image, "image");
        if (image.length < 48 || image.length > MAX_IMAGE) {
            throw new IOException("portable DB state outside sealed snapshot budget");
        }
    }

    private static Throwable cause(Throwable failure) {
        if (failure instanceof InvocationTargetException wrapped && wrapped.getCause() != null) {
            return wrapped.getCause();
        }
        return failure;
    }
}
