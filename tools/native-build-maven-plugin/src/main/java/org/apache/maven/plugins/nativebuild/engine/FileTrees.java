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
package org.apache.maven.plugins.nativebuild.engine;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Deleting plugin-owned directory trees and listing the stage, never through a link: a symbolic link, a Windows
 * junction or any other reparse point ({@code FILE_ATTRIBUTE_REPARSE_POINT}, which Java reports as "other" and, for a
 * junction, also as a directory) is deleted as the link itself, never followed into its target.
 */
public final class FileTrees {
    private static final int REPARSE_POINT = 0x400;

    private FileTrees() {}

    /** Deletes {@code root} and everything below it, links as links; nothing when it does not exist. */
    public static void delete(Path root) throws IOException {
        BasicFileAttributes attributes = attributes(root);
        if (attributes == null) {
            return;
        }
        if (linkLike(root, attributes) || !attributes.isDirectory()) {
            Files.delete(root);
            return;
        }
        Files.walkFileTree(root, new Deleter());
    }

    /**
     * Every regular file below {@code root} (relative, forward slashes, sorted). A link whose target lies inside
     * {@code root} is listed as the file it names (a directory link is skipped: its files are listed under their own
     * path); a link that leaves {@code root} fails.
     */
    public static List<String> regularFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        Lister lister = new Lister(root, root.toRealPath());
        Files.walkFileTree(root, lister);
        return lister.files.stream().sorted().toList();
    }

    /** True for a symbolic link, a junction or another reparse point. */
    public static boolean linkLike(Path path, BasicFileAttributes attributes) {
        return attributes.isSymbolicLink() || attributes.isOther() || reparsePoint(path);
    }

    /** True when {@code path} exists and is a link of any kind (not followed). */
    public static boolean linkLike(Path path) throws IOException {
        BasicFileAttributes attributes = attributes(path);
        return attributes != null && linkLike(path, attributes);
    }

    /** True when {@code child} lies strictly below {@code parent} (both normalized, absolute). */
    public static boolean strictlyBelow(Path child, Path parent) {
        Path c = child.toAbsolutePath().normalize();
        Path p = parent.toAbsolutePath().normalize();
        return c.startsWith(p) && !c.equals(p);
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean reparsePoint(Path path) {
        try {
            Object dos = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
            return dos instanceof Integer bits && (bits & REPARSE_POINT) != 0;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException noDosView) {
            return false;
        }
    }

    /** Deletes files, removes links without entering them, then each emptied directory. */
    private static final class Deleter extends SimpleFileVisitor<Path> {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            if (linkLike(dir, attrs)) {
                Files.delete(dir);
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
            if (failure != null) {
                throw failure;
            }
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
        }
    }

    /** Lists regular files; checks every link's target against the real root. */
    private static final class Lister extends SimpleFileVisitor<Path> {
        private final Path root;
        private final Path realRoot;
        private final List<String> files = new ArrayList<>();

        Lister(Path root, Path realRoot) {
            this.root = root;
            this.realRoot = realRoot;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            if (!dir.equals(root) && linkLike(dir, attrs)) {
                confined(dir);
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            boolean link = linkLike(file, attrs);
            Path target = link ? confined(file) : file;
            if (link ? Files.isRegularFile(target) : attrs.isRegularFile()) {
                files.add(WindowsPaths.relative(root, file));
            }
            return FileVisitResult.CONTINUE;
        }

        private Path confined(Path link) throws IOException {
            Path target = link.toRealPath();
            if (!target.startsWith(realRoot)) {
                throw new IOException("refused " + link + ": it links to " + target + ", outside " + realRoot);
            }
            return target;
        }
    }
}
