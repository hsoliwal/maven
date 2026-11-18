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
 * The one interface behind the module-state owner ({@link M3DbModuleStore}): where its sealed receipt image
 * lives. The owner keeps the codec, lease, generation marker and revocation protocol; a backend only stores
 * bytes. Built-in backends store through {@link IndexStore}; JDBC and {@code ext-*} providers are explicit,
 * external engines that the owner verifies by reading back every publication.
 */
interface ModuleStateBackend {
    /** The sealed image, or {@code null} on a cold module. Never partially committed bytes. */
    byte[] read() throws IOException;

    /** Publishes one complete sealed image atomically. */
    void write(byte[] image) throws IOException;

    /** The backend for a canonical backend name of {@link M3DbModuleStore#backend(String)}. */
    static ModuleStateBackend of(String backend, Path root, StateFiles files) throws IOException {
        return switch (backend) {
            case "embedded" -> new ImageStateBackend(root.resolve(M3DbModuleStore.FILE), files);
            case "file" -> new ImageStateBackend(root.resolve(M3DbModuleStore.FILE_BACKEND), files);
            case "m3db" -> DirectoryStateBackend.open(root.resolve(M3DbModuleStore.NATIVE_DIRECTORY), files);
            case "sqlite", "h2" -> new JdbcStateBackend(root, backend);
            default -> ProviderStateBackend.open(root, backend);
        };
    }
}
