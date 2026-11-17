/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Whole-build module cache coordination, including fingerprinting and tool execution.
 * A lock held only for database writes cannot prevent reuse of half-written outputs.
 */
final class M3ModuleDbRunLock implements AutoCloseable {
    static final String FILE = ".m3/module-state.run.lock";

    private final FileChannel channel;
    private final FileLock lock;

    private M3ModuleDbRunLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static M3ModuleDbRunLock acquire(Path moduleRoot) throws IOException {
        Path root = moduleRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("module cache root is not an ordinary directory");
        }
        Path directory = root.resolve(".m3");
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("module cache directory cannot be a symlink");
        }
        Files.createDirectories(directory);
        Path path = root.resolve(FILE);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("module cache lock is not a regular file");
        }
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException busy) {
                throw new IOException("module build cache is already in use: " + root, busy);
            }
            if (lock == null) {
                throw new IOException("module build cache is already in use: " + root);
            }
            return new M3ModuleDbRunLock(channel, lock);
        } catch (IOException | RuntimeException failure) {
            try {
                channel.close();
            } catch (IOException suppressed) {
                failure.addSuppressed(suppressed);
            }
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        IOException error = null;
        try {
            lock.release();
        } catch (IOException failure) {
            error = failure;
        }
        try {
            channel.close();
        } catch (IOException failure) {
            if (error == null) error = failure;
            else error.addSuppressed(failure);
        }
        if (error != null) throw error;
    }
}
