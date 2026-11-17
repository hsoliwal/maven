/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Synthetic provider only; real LMDB/RocksDB/MapDB providers are external. */
public final class TestMemoryBuildStateProvider implements M3BuildStateStoreProvider {
    private static final Map<Path, byte[]> IMAGES = new ConcurrentHashMap<>();

    @Override public String id() { return "testmemory"; }

    @Override public Store open(Path moduleRoot) {
        Path key = moduleRoot.toAbsolutePath().normalize();
        return new Store() {
            @Override public byte[] read() {
                byte[] bytes = IMAGES.get(key);
                return bytes == null ? null : bytes.clone();
            }
            @Override public void write(byte[] payload) {
                IMAGES.put(key, payload.clone());
            }
        };
    }
}
