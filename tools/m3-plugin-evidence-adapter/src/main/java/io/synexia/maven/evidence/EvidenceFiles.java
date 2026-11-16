/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.synexia.maven.evidence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** Project-owned bounded byte access; callers must exclusively own the workspace. */
final class EvidenceFiles {
    static final long MAX_FILE_BYTES = 32L * 1024 * 1024;
    private static final int MAX_EVIDENCE_BYTES = 16 * 1024 * 1024;

    private EvidenceFiles() {}

    /** Byte identity of a regular file, or explicit absence when permitted. */
    record FileDigest(String sha256, long size) {}

    /** Rejects escaping paths and existing symlink ancestors before any read/write. */
    static Path checked(Path projectRoot, Path path, boolean allowMissing) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(path, "path");
        rejectTraversal(projectRoot);
        rejectTraversal(path);
        Path root = projectRoot.toAbsolutePath().normalize();
        checkAncestors(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("project root must be an existing directory");
        }
        Path candidate = (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
        if (!candidate.startsWith(root)) {
            throw new IOException("evidence path escapes the invoked project");
        }
        checkAncestors(candidate);
        if (!allowMissing && !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("required evidence path is absent");
        }
        return candidate;
    }

    /** Digests the bytes actually read, with file/aggregate budgets supplied by the caller. */
    static FileDigest digest(Path projectRoot, Path path, boolean missingAllowed, long maxBytes)
            throws IOException {
        if (maxBytes < 0) {
            throw new IOException("resource evidence byte budget exceeded");
        }
        Path file = checked(projectRoot, path, true);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (missingAllowed) {
                return new FileDigest("ABSENT", -1);
            }
            throw new IOException("required resource file is absent");
        }
        long bound = Math.min(MAX_FILE_BYTES, maxBytes);
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() > bound) {
            throw new IOException("resource evidence requires a bounded regular file");
        }
        MessageDigest digest = newDigest();
        long count = 0;
        try (InputStream input = Channels.newInputStream(
                Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            byte[] buffer = new byte[16_384];
            for (int n; (n = input.read(buffer)) != -1; ) {
                if (n == 0) {
                    throw new IOException("resource evidence read stalled");
                }
                count += n;
                if (count > bound) {
                    throw new IOException("resource evidence byte budget exceeded");
                }
                digest.update(buffer, 0, n);
            }
        }
        BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile()
                || count != before.size()
                || count != after.size()
                || !Objects.equals(before.fileKey(), after.fileKey())
                || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
            throw new IOException("resource evidence file changed during reading");
        }
        return new FileDigest(HexFormat.of().formatHex(digest.digest()), count);
    }

    /** Creates an immutable project-local artifact; identical existing bytes are reused. */
    static Path publish(Path projectRoot, Path directory, String filename, byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_EVIDENCE_BYTES
                || filename == null
                || !filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IOException("invalid evidence artifact name or size");
        }
        Path dir = checked(projectRoot, directory, true);
        if (dir.equals(projectRoot.toAbsolutePath().normalize())) {
            throw new IOException("evidence artifacts require a dedicated project subdirectory");
        }
        Files.createDirectories(dir);
        checked(projectRoot, dir, false);
        Path file = checked(projectRoot, dir.resolve(filename), true);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            requireSameBytes(projectRoot, file, bytes);
            return file;
        }
        try {
            Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException concurrentCreation) {
            requireSameBytes(projectRoot, file, bytes);
        }
        requireSameBytes(projectRoot, file, bytes);
        return file;
    }

    static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(bytes));
    }

    private static void requireSameBytes(Path projectRoot, Path file, byte[] bytes) throws IOException {
        FileDigest actual = digest(projectRoot, file, false, MAX_EVIDENCE_BYTES);
        if (actual.size() != bytes.length || !actual.sha256().equals(sha256(bytes))) {
            throw new IOException("conflicting immutable evidence artifact");
        }
    }

    private static void rejectTraversal(Path path) throws IOException {
        for (Path component : path) {
            if (component.toString().equals("..")) {
                throw new IOException("parent traversal is not admitted in evidence paths");
            }
        }
    }

    private static void checkAncestors(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path component : path) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("symlink evidence path is not admitted");
            }
            if (!current.equals(path)
                    && Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("non-directory evidence ancestor");
            }
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
