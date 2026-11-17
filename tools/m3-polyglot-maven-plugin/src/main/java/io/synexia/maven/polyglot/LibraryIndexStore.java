/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Path;

import jdk.m3.indexdb.M3IndexDB;
import jdk.m3.indexdb.M3IndexDbArtifact;

/** {@link IndexStore} over the library form of M3IndexDB (exact M3JDK21 source, Apache-2.0). */
final class LibraryIndexStore implements IndexStore {
    private final M3IndexDB database;

    private LibraryIndexStore(M3IndexDB database) {
        this.database = database;
    }

    static IndexStore open(Path root) throws IOException {
        return new LibraryIndexStore(M3IndexDB.open(root));
    }

    static Class<?> engineType() {
        return M3IndexDB.class;
    }

    @Override
    public String binding() {
        return "library";
    }

    @Override
    public boolean contains(String name) {
        return database.containsArtifact(name);
    }

    @Override
    public Artifact read(String name) throws IOException {
        return artifact(database.requireArtifact(name));
    }

    @Override
    public Artifact write(String name, String kind, int version, byte[] payload) throws IOException {
        return artifact(database.putArtifact(name, kind, version, payload));
    }

    @Override
    public void close() {
        database.close();
    }

    private static Artifact artifact(M3IndexDbArtifact stored) {
        return new Artifact(stored.name(), stored.kind(), stored.formatVersion(), stored.contentSha256(),
                stored.payload());
    }
}
