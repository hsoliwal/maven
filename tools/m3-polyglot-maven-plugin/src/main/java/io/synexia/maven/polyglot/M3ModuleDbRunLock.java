/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;

/**
 * One module lease shared by every storage backend and all facade callers.
 * The lease covers fingerprinting, execution, invalidation and publication.
 * Duplicate owners are refused before opening another file descriptor, including
 * copies loaded in different Maven realms: on POSIX, closing a duplicate
 * descriptor can release the original process's locks on the same inode.
 */
final class M3ModuleDbRunLock implements AutoCloseable {
    static final String FILE = ".m3/module-state.run.lock";
    private static final int MAX_ROOT_CHARS = 8192;
    private static final String JVM_LEASE_PREFIX = "io.synexia.maven.polyglot.ModuleStateDb.lease.v3.";

    private final Path root;
    private final Properties properties;
    private final String property;
    private final String token;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    private M3ModuleDbRunLock(Path root, Properties properties, String property, String token,
            FileChannel channel, FileLock lock) {
        this.root = root;
        this.properties = properties;
        this.property = property;
        this.token = token;
        this.channel = channel;
        this.lock = lock;
    }

    static M3ModuleDbRunLock acquire(Path moduleRoot) throws IOException {
        Path requested = moduleRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("module cache root is not an ordinary directory");
        }
        Path root = requested.toRealPath();
        if (root.toString().length() > MAX_ROOT_CHARS) throw new IOException("module cache root exceeds limit");
        stateDirectory(root);
        Path path = root.resolve(FILE);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("module cache lock is not a regular file");
        }
        Properties properties = System.getProperties();
        String property = leaseProperty(root);
        String token = UUID.randomUUID().toString();
        synchronized (properties) {
            if (properties.containsKey(property)) {
                throw new IOException("module build cache is already in use: " + root);
            }
            // Keep the standard Properties contract: every key and value we add
            // is a String, including while store/storeToXML/list are called.
            properties.setProperty(property, token);
        }
        FileChannel channel = null;
        try {
            channel = FileChannel.open(path, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException busy) {
                throw new IOException("module build cache is already in use: " + root, busy);
            }
            if (lock == null) {
                throw new IOException("module build cache is already in use: " + root);
            }
            return new M3ModuleDbRunLock(root, properties, property, token, channel, lock);
        } catch (IOException | RuntimeException | Error failure) {
            if (channel != null) {
                try { channel.close(); }
                catch (IOException suppressed) { failure.addSuppressed(suppressed); }
            }
            releaseJvmLease(properties, property, token);
            throw failure;
        }
    }

    /** Creates the ordinary {@code .m3} directory; consumer projects need no root .gitignore: it ignores itself. */
    private static void stateDirectory(Path root) throws IOException {
        Path directory = root.resolve(".m3");
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("module cache directory cannot be a symlink");
        }
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("module cache directory is not an ordinary directory");
        }
        StateFiles.ignoreSelf(directory);
    }

    Path root() { return root; }

    synchronized boolean isValid() {
        synchronized (properties) {
            return !closed && lock.isValid() && System.getProperties() == properties
                    && token.equals(properties.get(property));
        }
    }

    static boolean isJvmLeaseProperty(String name) {
        if (name == null || name.length() != JVM_LEASE_PREFIX.length() + 64
                || !name.startsWith(JVM_LEASE_PREFIX)) return false;
        for (int i = JVM_LEASE_PREFIX.length(); i < name.length(); i++) {
            char ch = name.charAt(i);
            if (!(ch >= '0' && ch <= '9') && !(ch >= 'a' && ch <= 'f')) return false;
        }
        return true;
    }

    private static String leaseProperty(Path root) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(root.toString().getBytes(StandardCharsets.UTF_8));
            return JVM_LEASE_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
    }

    private static void releaseJvmLease(Properties properties, String property, String token) {
        synchronized (properties) {
            if (token.equals(properties.get(property))) properties.remove(property);
        }
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        Throwable error = null;
        try {
            lock.release();
        } catch (IOException | RuntimeException | Error failure) {
            error = failure;
        }
        try {
            channel.close();
        } catch (IOException | RuntimeException | Error failure) {
            if (error == null) error = failure;
            else error.addSuppressed(failure);
        }
        releaseJvmLease(properties, property, token);
        if (error instanceof IOException io) throw io;
        if (error instanceof RuntimeException runtime) throw runtime;
        if (error instanceof Error fatal) throw fatal;
    }
}
