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
import java.security.MessageDigest;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * An explicit {@code ext-*} backend: one {@link M3ModuleDbBackendProvider} registered with the plugin (context
 * realm first, then this plugin), bounded and verified by reading back each publication.
 */
final class ProviderStateBackend implements ModuleStateBackend {
    private final Path root;
    private final M3ModuleDbBackendProvider provider;

    private ProviderStateBackend(Path root, M3ModuleDbBackendProvider provider) {
        this.root = root;
        this.provider = provider;
    }

    static ProviderStateBackend open(Path root, String backend) throws IOException {
        ClassLoader own = M3ModuleDbBackendProvider.class.getClassLoader();
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        M3ModuleDbBackendProvider selected = find(backend, context == null ? own : context);
        if (selected == null && context != null && context != own) selected = find(backend, own);
        if (selected == null) {
            throw new M3DbModuleStore.BackendUnavailableException(
                    "requested M3 portable DB provider is absent: " + backend, null);
        }
        return new ProviderStateBackend(root, selected);
    }

    private static M3ModuleDbBackendProvider find(String backend, ClassLoader loader) throws IOException {
        M3ModuleDbBackendProvider selected = null;
        try {
            for (M3ModuleDbBackendProvider candidate : ServiceLoader.load(M3ModuleDbBackendProvider.class, loader)) {
                selected = select(backend, candidate, selected);
            }
        } catch (ServiceConfigurationError | RuntimeException malformed) {
            throw new IOException("M3 portable DB provider registration failed", malformed);
        }
        return selected;
    }

    private static M3ModuleDbBackendProvider select(String backend, M3ModuleDbBackendProvider candidate,
            M3ModuleDbBackendProvider selected) throws IOException {
        String id = candidate.id();
        if (!M3DbModuleStore.externalBackend(id)) throw new IOException("invalid M3 portable DB provider identity");
        if (!backend.equals(id)) return selected;
        if (selected != null) throw new IOException("duplicate M3 portable DB provider: " + id);
        return candidate;
    }

    @Override
    public byte[] read() throws IOException {
        byte[] image = provider.read(root);
        if (image == null) return null;
        if (image.length > M3DbModuleStore.MAX_PAYLOAD) throw new IOException("M3 portable DB image exceeds limit");
        return image.clone();
    }

    @Override
    public void write(byte[] image) throws IOException {
        provider.write(root, image.clone());
        byte[] published = read();
        if (published == null || !MessageDigest.isEqual(image, published)) {
            throw new IOException("M3 portable DB publication mismatch");
        }
    }
}
