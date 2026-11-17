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

/**
 * The one storage layer of the module state and of the project database: named, content-addressed, versioned
 * artifacts. The storage logic has one owner, M3IndexDB ({@code jdk.m3.indexdb}): the JDK's own form when Maven
 * runs on M3jdk21, otherwise the library form of the same source carried by this plugin (see
 * {@code META-INF/M3INDEXDB-PROVENANCE.tsv}). Callers see which form is bound only through {@link #binding()}.
 */
public interface IndexStore extends AutoCloseable {
    /** One stored artifact; {@code sha256} is the lowercase digest of {@code payload}. */
    record Artifact(String name, String kind, int version, String sha256, byte[] payload) {}

    /** Opens (creating when absent) the store rooted at a directory of any file system provider. */
    static IndexStore open(Path root) throws IOException {
        return IndexStores.engine(ModuleLayer.boot()).open(root);
    }

    /** "platform" (the running JDK's {@code jdk.m3.indexdb}), "library" or "donor" (an installed runtime). */
    String binding();

    boolean contains(String name) throws IOException;

    /** Reads and verifies one artifact; a missing or corrupt artifact is an {@link IOException}. */
    Artifact read(String name) throws IOException;

    /** Publishes one artifact; the payload is stored once by content digest. */
    Artifact write(String name, String kind, int version, byte[] payload) throws IOException;

    @Override
    void close() throws IOException;
}
