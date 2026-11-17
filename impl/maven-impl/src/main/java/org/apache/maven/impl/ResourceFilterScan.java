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
package org.apache.maven.impl;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * The files and directories of one resource root selected by a {@link PathMatcher}, sorted by path so that the
 * copy order does not depend on the file system. Symbolic links are followed, as plexus-utils
 * {@code DirectoryScanner} does by default; directories the directory matcher rejects are not entered.
 *
 * @param files the selected regular files
 * @param directories the selected directories below the root
 */
record ResourceFilterScan(List<Path> files, List<Path> directories) {

    static ResourceFilterScan of(Path root, PathMatcher matcher, PathMatcher directoryMatcher) throws IOException {
        Visitor visitor = new Visitor(root, matcher, directoryMatcher);
        Files.walkFileTree(root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, visitor);
        Comparator<Path> byPath =
                Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/'));
        return new ResourceFilterScan(sorted(visitor.files, byPath), sorted(visitor.directories, byPath));
    }

    private static List<Path> sorted(List<Path> paths, Comparator<Path> order) {
        return paths.stream().sorted(order).toList();
    }

    private static final class Visitor extends SimpleFileVisitor<Path> {
        private final Path root;
        private final PathMatcher matcher;
        private final PathMatcher directoryMatcher;
        private final List<Path> files = new ArrayList<>();
        private final List<Path> directories = new ArrayList<>();

        Visitor(Path root, PathMatcher matcher, PathMatcher directoryMatcher) {
            this.root = root;
            this.matcher = matcher;
            this.directoryMatcher = directoryMatcher;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (dir.equals(root)) {
                return FileVisitResult.CONTINUE;
            }
            if (!directoryMatcher.matches(dir)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            if (matcher.matches(dir)) {
                directories.add(dir);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isRegularFile() && matcher.matches(file)) {
                files.add(file);
            }
            return FileVisitResult.CONTINUE;
        }
    }
}
