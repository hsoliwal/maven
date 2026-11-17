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
import java.nio.file.Path;

import jdk.m3.indexdb.M3IndexDB;
import jdk.m3.indexdb.M3IndexDbArtifact;

/** {@link IndexStore} over the library form of M3IndexDB (exact M3JDK21 source, Apache-2.0). */
final class LibraryIndexStore implements IndexStore {
    static final IndexStores.Engine ENGINE = new IndexStores.Engine() {
        @Override
        public String binding() {
            return "library";
        }

        @Override
        public IndexStore open(Path root) throws IOException {
            return new LibraryIndexStore(M3IndexDB.open(root));
        }
    };

    private final M3IndexDB database;

    private LibraryIndexStore(M3IndexDB database) {
        this.database = database;
    }

    static Class<?> engineType() {
        return M3IndexDB.class;
    }

    @Override
    public String binding() {
        return ENGINE.binding();
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
