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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.stream.Stream;

/**
 * The m3db (alias native) backend: an {@link IndexStore} directory {@code .m3/maven-build-db} written by the
 * runtime's own M3IndexDB engine, never by the library copy this plugin carries. The engine is the running JDK's
 * {@code jdk.m3.indexdb} (M3jdk21), else an installed MIndexDB runtime; without either the choice fails and
 * nothing falls back to another backend.
 */
final class DirectoryStateBackend implements ModuleStateBackend {
    static final String ARTIFACT = "maven-module-build-state-v1";
    static final String KIND = "maven-module-state";
    private static final int MAX_ENTRIES = 16384;

    private final Path path;
    private final StateFiles files;
    private final IndexStores.Engine engine;

    private DirectoryStateBackend(Path path, StateFiles files, IndexStores.Engine engine) {
        this.path = path;
        this.files = files;
        this.engine = engine;
    }

    static DirectoryStateBackend open(Path path, StateFiles files) throws IOException {
        StateFiles.checkDirectory(path);
        try {
            return new DirectoryStateBackend(path, files, IndexStores.runtimeEngine(ModuleLayer.boot()));
        } catch (ReflectiveOperationException | LinkageError missing) {
            throw new M3DbModuleStore.BackendUnavailableException("the m3db backend needs jdk.m3.indexdb in the"
                    + " running JDK or MIndexDB installed on the polyglot plugin classpath", missing);
        }
    }

    @Override
    public byte[] read() throws IOException {
        check(false);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try (IndexStore store = engine.open(path)) {
            if (!store.contains(ARTIFACT)) return null;
            byte[] payload = store.read(ARTIFACT).payload();
            if (payload.length > M3DbModuleStore.MAX_PAYLOAD) throw new IOException("invalid module artifact size");
            return payload;
        }
    }

    @Override
    public void write(byte[] image) throws IOException {
        check(false);
        try (IndexStore store = engine.open(path)) {
            store.write(ARTIFACT, KIND, M3DbModuleStore.VERSION, image);
        }
        check(true);
        byte[] published = read();
        if (published == null || !MessageDigest.isEqual(image, published)) {
            throw new IOException("module directory artifact publication mismatch");
        }
    }

    /** Bounds entry count and bytes, refuses links and special files, and forces every file when asked. */
    private void check(boolean force) throws IOException {
        StateFiles.checkDirectory(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            long[] totals = new long[2];
            for (Iterator<Path> entries = walk.iterator(); entries.hasNext(); ) entry(entries.next(), totals, force);
        }
        if (force) files.forceDirectory(path);
    }

    private static void entry(Path entry, long[] totals, boolean force) throws IOException {
        if (++totals[0] > MAX_ENTRIES) throw new IOException("module directory entry limit");
        if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) return;
        StateFiles.checkFile(entry);
        long length = Files.size(entry);
        if (length > M3DbModuleStore.MAX_ARCHIVE || (totals[1] += length) > 4L * M3DbModuleStore.MAX_ARCHIVE) {
            throw new IOException("module directory size limit");
        }
        if (force) StateFiles.forceFile(entry);
    }
}
