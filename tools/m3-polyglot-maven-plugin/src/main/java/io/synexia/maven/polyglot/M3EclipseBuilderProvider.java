/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.util.Set;

/**
 * Read-only Eclipse builder/nature ownership descriptor, discovered by
 * ServiceLoader on the selected polyglot engine classpath.
 *
 * <p>Builder IDs are exact Eclipse extension IDs, never guesses based on file
 * extensions or nature names. Nature-only providers (for example LSP adapters)
 * are valid. Implementing this SPI does not execute or replace an Eclipse
 * IncrementalProjectBuilder, JDT, CDT, Rust tooling, or any language server.
 * The Eclipse host remains responsible for build configurations, IResourceDelta,
 * generated resources, markers, and progress/cancellation.</p>
 *
 * <p>Adapters are trusted installed extensions; discovery is not a sandbox.
 * Language compiler commands use the separate M3ToolProvider SPI.</p>
 */
public interface M3EclipseBuilderProvider {
    int API_VERSION = 1;

    /** Stable lowercase adapter ID, unique on the selected classpath. */
    String id();

    /** Eclipse org.eclipse.core.resources.builders extension IDs owned by this adapter. */
    Set<String> builderIds();

    /** Eclipse project nature IDs recognized by this adapter (may be nature-only). */
    Set<String> natureIds();

    default int apiVersion() {
        return API_VERSION;
    }
}
