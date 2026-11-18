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
package org.apache.maven.plugins.codepatch;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Writes a file through a temporary file in the same directory, forced to disk, then moved over the target with an
 * atomic move where the file system allows it (a plain replacing move otherwise). Readers see the old or the new
 * content, never a part. POSIX permissions of the file being replaced are kept. After a move or a delete the directory
 * is synced, and so is the parent of every directory created on the way, where the platform allows it (not on
 * Windows, whose file systems journal their metadata). Each force is a FINE record of this class's logger:
 * {@code force-file <target>} and {@code force-directory <dir> ok|unsupported|failed: <exception>}.
 */
final class FileWrites {
    private static final Logger LOG = Logger.getLogger(FileWrites.class.getName());

    private FileWrites() { }

    /** Writes {@code bytes} to {@code target}; {@code permissionsFrom} (may be absent or null) gives the permissions. */
    static void write(Path target, byte[] bytes, Path permissionsFrom) throws IOException {
        Path temp = temp(target);
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            LOG.fine(() -> "force-file\t" + target);
            copyPermissions(permissionsFrom, temp);
            promote(temp, target);
        } finally {
            Trees.deleteFile(temp);
        }
    }

    /** Copies {@code source} to {@code target} with its attributes (permissions, modification time), the same way. */
    static void copy(Path source, Path target) throws IOException {
        Path temp = temp(target);
        try {
            Files.copy(source, temp, StandardCopyOption.COPY_ATTRIBUTES);
            force(temp, target);
            promote(temp, target);
        } finally {
            Trees.deleteFile(temp);
        }
    }

    /** Deletes {@code target} if it exists and syncs its directory. */
    static void delete(Path target) throws IOException {
        Files.deleteIfExists(target);
        forceDirectory(target.getParent());
    }

    /** Syncs a directory, so that an entry created, replaced or removed in it survives a crash. */
    static void forceDirectory(Path dir) {
        if (dir == null) return;
        if (TargetPaths.windows(dir)) {
            LOG.fine(() -> "force-directory\t" + dir + "\tunsupported");
            return;
        }
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
            LOG.fine(() -> "force-directory\t" + dir + "\tok");
        } catch (IOException e) {
            LOG.fine(() -> "force-directory\t" + dir + "\tfailed: " + e);
        }
    }

    /** Forces a copied file: opened for writing (Windows needs that), or for reading when it is read-only (POSIX). */
    private static void force(Path temp, Path target) throws IOException {
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            channel.force(true);
        } catch (AccessDeniedException e) {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
        LOG.fine(() -> "force-file\t" + target);
    }

    private static void promote(Path temp, Path target) throws IOException {
        move(temp, target);
        forceDirectory(target.getParent());
    }

    private static Path temp(Path target) throws IOException {
        directories(target.toAbsolutePath().getParent());
        return target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
    }

    /** Creates the missing directories of {@code dir} and syncs the parent of each one it created. */
    private static void directories(Path dir) throws IOException {
        Path existing = dir;
        while (existing != null && !Files.isDirectory(existing)) existing = existing.getParent();
        Files.createDirectories(dir);
        for (Path at = dir; at != null && !at.equals(existing); at = at.getParent()) forceDirectory(at.getParent());
    }

    private static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void copyPermissions(Path from, Path to) throws IOException {
        if (from == null || !Files.isRegularFile(from)) return;
        PosixFileAttributeView view = Files.getFileAttributeView(from, PosixFileAttributeView.class);
        if (view != null) Files.setPosixFilePermissions(to, view.readAttributes().permissions());
    }
}
