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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Empties the scratch directory of the tests, also when a test left a file read-only (Windows) or a directory without
 * write permission (POSIX), and says whether the tests run on Windows.
 */
final class Scratch {
    private Scratch() { }

    static boolean windows() {
        return File.separatorChar == '\\';
    }

    static void clean(Path dir) throws IOException {
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            List<Path> paths;
            try (Stream<Path> walk = Files.walk(dir)) {
                paths = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            }
            for (Path path : paths) delete(path);
        }
        Files.createDirectories(dir);
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        writable(path);
        if (path.getParent() != null) writable(path.getParent());
        Files.deleteIfExists(path);
    }

    /** Clears the read-only attribute (Windows) or gives the owner write permission (POSIX directories). */
    static void writable(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) return;
        DosFileAttributeView dos = windows() ? Files.getFileAttributeView(path, DosFileAttributeView.class) : null;
        if (dos != null) dos.setReadOnly(false);
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Set<PosixFilePermission> permissions = EnumSet.copyOf(posix.readAttributes().permissions());
            permissions.addAll(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            posix.setPermissions(permissions);
        }
    }
}
