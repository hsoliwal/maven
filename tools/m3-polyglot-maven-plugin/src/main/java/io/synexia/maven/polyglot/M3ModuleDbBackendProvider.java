/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Opt-in portable storage SPI for the existing M3 sealed module state image.
 *
 * <p>Implementations are trusted plugin dependencies, not build planners. A provider
 * must perform atomic, crash-consistent publication, verify module-local paths,
 * reject symbolic-link traversal and never authorize execution skipping. The
 * caller holds a module-scope process lock from lookup through task completion.
 * No provider may silently redirect state to a remote host or shared cache.
 *
 * <p>Registered via {@code META-INF/services/io.synexia.maven.polyglot.M3ModuleDbBackendProvider}.
 * Names must begin with {@code ext-}; built-in engine names remain reserved.
 */
public interface M3ModuleDbBackendProvider {
    /** Stable, versioned backend identifier, e.g. ext-rocksdb-v1. */
    String id();

    /**
     * Fail before opening the module's M3 generation if the explicitly named
     * optional driver/native runtime is not installed. Existing third-party
     * providers remain binary/source compatible through this default method.
     */
    default void verifyAvailable(Path moduleRoot) throws IOException { }

    /** Return the sealed image or null on a cold module. Never return partially committed bytes. */
    byte[] read(Path moduleRoot) throws IOException;

    /** Commit a complete sealed image atomically to module-local persistence. */
    void write(Path moduleRoot, byte[] sealedImage) throws IOException;
}
