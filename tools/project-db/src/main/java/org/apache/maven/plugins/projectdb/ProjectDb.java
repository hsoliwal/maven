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
package org.apache.maven.plugins.projectdb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import io.synexia.maven.polyglot.IndexStore;

/**
 * The per-project build database. Its only storage is {@link IndexStore} (M3IndexDB: the {@code jdk.m3.indexdb}
 * module on M3jdk21, the library form on a stock JDK). Artifacts: {@code builds} (the build list), one
 * {@code catalog/<build>} per build (source, content hash, first build), {@code chunks/v1/<sha>} per distinct source
 * content (parsed once), and {@code derived/<build>} (changes since the last green build, flaky tests).
 */
public final class ProjectDb implements AutoCloseable {
    static final String BUILDS = "builds";
    private static final String KIND_BUILDS = "project-db.builds";
    private static final String KIND_CATALOG = "project-db.catalog";
    private static final String KIND_CHUNKS = "project-db.chunks";
    private static final int VERSION = 1;

    private final IndexStore store;

    private ProjectDb(IndexStore store) {
        this.store = store;
    }

    /** Opens (creating) the database in a directory that is never committed (see {@link StoreLocation}). */
    public static ProjectDb open(Path directory) throws IOException {
        Files.createDirectories(directory);
        StoreLocation.guard(directory);
        return new ProjectDb(IndexStore.open(directory.resolve("db")));
    }

    /** "platform" on M3jdk21 ({@code jdk.m3.indexdb}), "library" on a stock JDK. */
    public String binding() {
        return store.binding();
    }

    /** Result of one ingest: the recorded build and how many chunks it can answer from. */
    public record Ingest(Catalog.Build build, int chunks) {}

    /** Ingests the build data of the given module directories (and an optional build log) as one new build. */
    public Ingest ingest(Path root, List<Path> modules, Path buildLog) throws IOException {
        Path project = root.toAbsolutePath().normalize();
        List<Source> sources = new ArrayList<>();
        for (Path module : modules) sources.addAll(Sources.discover(project, module.toAbsolutePath().normalize()));
        if (buildLog != null) sources.add(Sources.buildLog(project, buildLog));
        List<Catalog.Build> builds = builds();
        Map<String, Catalog.Entry> previous = builds.isEmpty() ? Map.of() : catalog(builds.get(builds.size() - 1).id());
        Map<String, Catalog.Entry> entries = new TreeMap<>();
        int parsed = 0;
        for (Source source : sources) parsed += admit(project, source, previous, entries) ? 1 : 0;
        return record(builds, assign(entries, buildId(builds.size() + 1, entries)), parsed);
    }

    /** Reuses an unchanged source's entry; parses a new content once into its content-addressed chunk artifact. */
    private boolean admit(Path project, Source source, Map<String, Catalog.Entry> previous,
            Map<String, Catalog.Entry> entries) throws IOException {
        Catalog.Entry old = previous.get(source.key());
        if (old != null && old.sha().equals(source.sha()) && store.contains(old.chunks())) {
            entries.put(source.key(), old);
            return false;
        }
        String artifact = Catalog.chunksArtifact(source.sha());
        boolean parse = !store.contains(artifact);
        if (parse) store.write(artifact, KIND_CHUNKS, VERSION, Catalog.encodeChunks(Parsers.parse(project, source)));
        entries.put(source.key(), new Catalog.Entry(source.key(), source.module(), source.path(), source.kind(),
                source.sha(), ""));
        return parse;
    }

    /** Writes the build's catalog, its derived chunks and the extended build list; the build list goes last. */
    private Ingest record(List<Catalog.Build> builds, Map<String, Catalog.Entry> entries, int parsed)
            throws IOException {
        String id = buildId(builds.size() + 1, entries);
        List<Chunk> chunks = chunks(entries);
        List<Chunk> derived = Derived.of(this, entries, id, builds);
        store.write("catalog/" + id, KIND_CATALOG, VERSION, Catalog.encodeEntries(entries));
        store.write("derived/" + id, KIND_CHUNKS, VERSION, Catalog.encodeChunks(derived));
        Catalog.Build build = new Catalog.Build(builds.size() + 1, id, Derived.status(chunks), entries.size(),
                parsed, entries.size() - parsed);
        List<Catalog.Build> all = new ArrayList<>(builds);
        all.add(build);
        store.write(BUILDS, KIND_BUILDS, VERSION, Catalog.encodeBuilds(all));
        return new Ingest(build, chunks.size() + derived.size());
    }

    /** New entries (no build yet) belong to this build. */
    private static Map<String, Catalog.Entry> assign(Map<String, Catalog.Entry> entries, String id) {
        Map<String, Catalog.Entry> assigned = new TreeMap<>();
        entries.forEach((key, e) -> assigned.put(key, e.build().isEmpty()
                ? new Catalog.Entry(e.key(), e.module(), e.path(), e.kind(), e.sha(), id) : e));
        return assigned;
    }

    /** {@code b<sequence>-<first 8 hex of the catalog's source hashes>}: deterministic for the same data. */
    private static String buildId(int sequence, Map<String, Catalog.Entry> entries) {
        StringBuilder keys = new StringBuilder();
        new TreeMap<>(entries).forEach((key, e) -> keys.append(key).append('\t').append(e.sha()).append('\n'));
        return String.format(Locale.ROOT, "b%04d-%s", sequence, Source.sha256(keys.toString()).substring(0, 8));
    }

    /** Answers a question from the latest build; every hit carries file, line and build id. */
    public Answer query(String question, int k) throws IOException {
        List<Catalog.Build> builds = builds();
        if (builds.isEmpty()) return new Answer(question, "", List.of());
        String id = builds.get(builds.size() - 1).id();
        List<Chunk> corpus = new ArrayList<>(chunks(catalog(id)));
        corpus.addAll(derived(id));
        return new Answer(question, id, new Retriever(corpus).search(question, k));
    }

    /** Every recorded build, oldest first. */
    public List<Catalog.Build> builds() throws IOException {
        return store.contains(BUILDS) ? Catalog.decodeBuilds(store.read(BUILDS).payload()) : List.of();
    }

    Map<String, Catalog.Entry> catalog(String build) throws IOException {
        String name = "catalog/" + build;
        return store.contains(name) ? Catalog.decodeEntries(store.read(name).payload()) : Map.of();
    }

    List<Chunk> derived(String build) throws IOException {
        String name = "derived/" + build;
        return store.contains(name) ? Catalog.decodeChunks(store.read(name).payload()) : List.of();
    }

    /** The chunks of catalog entries, each stamped with the build that first ingested its content. */
    List<Chunk> chunks(Map<String, Catalog.Entry> entries) throws IOException {
        List<Chunk> chunks = new ArrayList<>();
        for (Catalog.Entry entry : entries.values()) chunks.addAll(chunks(entry));
        return chunks;
    }

    List<Chunk> chunks(Catalog.Entry entry) throws IOException {
        List<Chunk> chunks = new ArrayList<>();
        for (Chunk chunk : Catalog.decodeChunks(store.read(entry.chunks()).payload())) {
            chunks.add(chunk.inBuild(entry.build()));
        }
        return chunks;
    }

    @Override
    public void close() throws IOException {
        store.close();
    }
}
