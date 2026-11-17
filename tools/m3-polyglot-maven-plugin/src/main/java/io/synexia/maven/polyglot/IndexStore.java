/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The one storage interface of every Maven project database in this fork: named, content-addressed,
 * versioned artifacts. The single owner of the storage logic is M3IndexDB ({@code jdk.m3.indexdb}):
 * the JDK module when Maven runs on M3jdk21, otherwise the library form of the same source carried by
 * this plugin (see {@code META-INF/M3INDEXDB-PROVENANCE.tsv}). Callers never see which one is bound,
 * except through {@link #binding()}.
 */
public interface IndexStore extends AutoCloseable {
    /** One stored artifact; {@code sha256} is the lowercase digest of {@code payload}. */
    record Artifact(String name, String kind, int version, String sha256, byte[] payload) {}

    /** Opens (creating when absent) the store rooted at a directory of any file system provider. */
    static IndexStore open(Path root) throws IOException {
        return IndexStores.open(root, ModuleLayer.boot());
    }

    /** "platform" (the {@code jdk.m3.indexdb} module of the running JDK) or "library". */
    String binding();

    boolean contains(String name) throws IOException;

    /** Reads and verifies one artifact; a missing or corrupt artifact is an {@link IOException}. */
    Artifact read(String name) throws IOException;

    /** Publishes one artifact; the payload is stored once by content digest. */
    Artifact write(String name, String kind, int version, byte[] payload) throws IOException;

    @Override
    void close() throws IOException;
}
