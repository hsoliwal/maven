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
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The embedded (alias legacy) and file backends: one closed ZIP image holding an {@link IndexStore}
 * ({@code blobs/} and {@code refs/}) with the one receipt artifact. The image is built beside the target, bounded,
 * forced and moved over it atomically; compressed and expanded sizes are bounded before the store opens it.
 */
final class ImageStateBackend implements ModuleStateBackend {
    static final String ARTIFACT = "successful-tasks";
    static final String KIND = "maven.polyglot.module-state";

    private final Path file;
    private final StateFiles files;

    ImageStateBackend(Path file, StateFiles files) throws IOException {
        StateFiles.checkFile(file);
        this.file = file;
        this.files = files;
    }

    @Override
    public byte[] read() throws IOException {
        StateFiles.checkFile(file);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        validate(file);
        try (FileSystem archive = FileSystems.newFileSystem(file, Map.of());
                IndexStore store = IndexStore.open(archive.getPath("/"))) {
            IndexStore.Artifact artifact = store.read(ARTIFACT);
            if (!KIND.equals(artifact.kind()) || artifact.version() != M3DbModuleStore.VERSION) {
                throw new IOException("unsupported module receipt schema");
            }
            return artifact.payload();
        }
    }

    @Override
    public void write(byte[] image) throws IOException {
        StateFiles.checkFile(file);
        Path temporaryDirectory = Files.createTempDirectory(files.directory(), "module-state-");
        Path temporary = temporaryDirectory.resolve("module.m3db");
        StateFiles.withCleanup(() -> publish(temporary, image), temporary, temporaryDirectory);
    }

    private void publish(Path temporary, byte[] image) throws IOException {
        try (FileSystem archive = FileSystems.newFileSystem(temporary, Map.of("create", "true"));
                IndexStore store = IndexStore.open(archive.getPath("/"))) {
            store.write(ARTIFACT, KIND, M3DbModuleStore.VERSION, image);
        }
        validate(temporary);
        StateFiles.forceFile(temporary);
        files.replace(temporary, file);
    }

    /** Exactly the two directories, one blob and one ref, each within its bound when expanded. */
    static void validate(Path image) throws IOException {
        if (Files.size(image) > M3DbModuleStore.MAX_ARCHIVE) throw new IOException("M3DB archive exceeds limit");
        Set<String> seen = new HashSet<>();
        int[] counts = new int[2];
        try (ZipFile zip = new ZipFile(image.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) entry(zip, entries.nextElement(), seen, counts);
        }
        if (counts[0] != 1 || counts[1] != 1 || !seen.contains("blobs/") || !seen.contains("refs/")) {
            throw new IOException("M3DB module image must contain one artifact and both directories");
        }
    }

    private static void entry(ZipFile zip, ZipEntry entry, Set<String> seen, int[] counts) throws IOException {
        String name = entry.getName();
        if (!seen.add(name) || seen.size() > 4) throw new IOException("M3DB archive entry count");
        int limit = limit(entry, counts);
        if (entry.getSize() < 0 || entry.getSize() > limit) throw new IOException("M3DB entry exceeds limit");
        try (InputStream in = zip.getInputStream(entry)) {
            if (expanded(in, limit) != entry.getSize()) {
                throw new IOException("M3DB expanded size disagrees with metadata");
            }
        }
    }

    private static int limit(ZipEntry entry, int[] counts) throws IOException {
        String name = entry.getName();
        if (name.equals("blobs/") || name.equals("refs/")) {
            if (!entry.isDirectory()) throw new IOException("M3DB archive directory type");
            return 0;
        }
        if (name.matches("blobs/[0-9a-f]{64}\\.blob")) {
            counts[0]++;
            return M3DbModuleStore.MAX_PAYLOAD;
        }
        if (!name.matches("refs/[0-9a-f]{64}\\.ref")) throw new IOException("unexpected M3DB archive entry");
        counts[1]++;
        return M3DbModuleStore.MAX_REF;
    }

    private static int expanded(InputStream in, int limit) throws IOException {
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int n; (n = in.read(buffer)) != -1; ) {
            if (n > limit - total) throw new IOException("M3DB expanded entry exceeds limit");
            total += n;
        }
        return total;
    }
}
