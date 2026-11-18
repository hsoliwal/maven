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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Makes a write fail where the file system can express it, for tests of interrupted transactions: an ACL entry that
 * denies creating files (NTFS), a directory without write permission (POSIX, not as root), or the read-only attribute
 * (Windows). Empty when the file system cannot; the caller then skips the check.
 */
final class Blocks {
    /** Undoes a block. */
    interface Undo extends AutoCloseable {
        @Override void close() throws IOException;
    }

    private Blocks() { }

    /** No new file can be created in {@code dir}. */
    static Optional<Undo> noNewFiles(Path dir) throws IOException {
        if (Files.getFileStore(dir).supportsFileAttributeView(AclFileAttributeView.class)) return denyAddFile(dir);
        if (Files.getFileStore(dir).supportsFileAttributeView(PosixFileAttributeView.class)) return readOnlyDirectory(dir);
        return Optional.empty();
    }

    /** {@code file} cannot be replaced: read-only on Windows, a parent directory without write permission on POSIX. */
    static Optional<Undo> noReplace(Path file) throws IOException {
        if (Scratch.windows()) {
            DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class);
            dos.setReadOnly(true);
            return Optional.of(() -> dos.setReadOnly(false));
        }
        if (Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class)) {
            return readOnlyDirectory(file.getParent());
        }
        return Optional.empty();
    }

    /**
     * An explicit deny entry for creating files, added and removed with icacls: a round trip through
     * {@link java.nio.file.attribute.AclFileAttributeView} would drop the generic rights of the inherited entries, and
     * new files in the directory would then lose their write access.
     */
    private static Optional<Undo> denyAddFile(Path dir) throws IOException {
        String user = System.getProperty("user.name");
        if (!Scratch.windows() || icacls(dir, "/deny", user + ":(WD)") != 0) return Optional.empty();
        return Optional.of(() -> {
            if (icacls(dir, "/remove:d", user) != 0) throw new IOException("icacls could not remove the deny entry of " + dir);
        });
    }

    private static int icacls(Path dir, String option, String entry) throws IOException {
        try {
            return new ProcessBuilder("icacls", dir.toString(), option, entry).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while running icacls", e);
        }
    }

    private static Optional<Undo> readOnlyDirectory(Path dir) throws IOException {
        Set<PosixFilePermission> before = Files.getPosixFilePermissions(dir);
        Set<PosixFilePermission> blocked = EnumSet.copyOf(before);
        blocked.removeAll(Set.of(PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_WRITE));
        Files.setPosixFilePermissions(dir, blocked);
        if (!Files.isWritable(dir)) return Optional.of(() -> Files.setPosixFilePermissions(dir, before));
        Files.setPosixFilePermissions(dir, before);
        return Optional.empty();
    }
}
