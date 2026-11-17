/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Java 17 SPI for additional portable embedded module-state stores.
 *
 * <p>Each selected provider persists exactly one already versioned and
 * SHA-256-sealed M3 module image. Maven owns the lifecycle, integrity checks
 * and exclusive module lock. Providers own their disk layout, transactions
 * and failure handling, never build skip decisions. Optional LMDB, RocksDB,
 * LevelDB, MapDB, Xodus, etc. adapt through this boundary without vendoring
 * native libraries into Maven.
 *
 * <p>Providers are trusted plugin code, installed explicitly on the
 * polyglot plugin's runtime classpath and named with a ServiceLoader
 * descriptor. No provider is downloaded or activated automatically.
 */
public interface M3BuildStateStoreProvider {
    int API_VERSION = 1;

    /** Additive version for future provider contract evolution. */
    default int apiVersion() { return API_VERSION; }

    /** Unique lower-case ID (a-z, 0-9, dash), different from built-in backend IDs. */
    String id();

    /**
     * Opens storage in the selected project/module root; must not escape it.
     * On-disk state must be confined below .m3/module-state-<id> so Maven can
     * exclude the state itself when a task declares the whole project as input.
     */
    Store open(Path moduleRoot) throws IOException;

    interface Store extends AutoCloseable {
        /** Absent payload is a cold cache, never a successful build result. */
        byte[] read() throws IOException;

        /** Publish the complete supplied image atomically or transactionally. */
        void write(byte[] sealedImage) throws IOException;

        @Override
        default void close() throws IOException {
            // Stateless backends need no cleanup; open databases can override.
        }
    }
}
