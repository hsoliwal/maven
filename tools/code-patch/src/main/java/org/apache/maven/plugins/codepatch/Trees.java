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
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Deletes files and directory trees of the plugin's own output (staged images, old receipts, temporary files). On
 * Windows a read-only file cannot be deleted, so a delete refused there clears the read-only attribute and tries once
 * more. Links are deleted, never followed.
 */
final class Trees {
    private Trees() { }

    /** Deletes {@code dir} and everything under it, if it exists. */
    static void delete(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(dir)) {
            paths = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (Path path : paths) deleteFile(path);
    }

    /** Deletes a file or an empty directory, if it exists. */
    static void deleteFile(Path path) throws IOException {
        try {
            Files.deleteIfExists(path);
        } catch (AccessDeniedException e) {
            DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (dos == null || !TargetPaths.windows(path)) throw e;
            dos.setReadOnly(false);
            Files.deleteIfExists(path);
        }
    }
}
