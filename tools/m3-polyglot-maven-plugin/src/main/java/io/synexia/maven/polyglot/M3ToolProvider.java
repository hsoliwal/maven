/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Maven-owned, provider-neutral execution SPI. Implementations are discovered via
 * {@link java.util.ServiceLoader} on the build engine classpath. A provider must
 * return an argument vector without executing a tool, reading network resources,
 * or mutating the workspace during planning. Source/AST facts remain with their
 * canonical M3File, M3String and M3AST owners; this SPI does not copy them.
 *
 * <p>Providers are trusted build extensions. Command planning is not a sandbox
 * for third-party Java code, and neither compilation nor atomization alone
 * certifies semantic equivalence of a transformation.</p>
 */
public interface M3ToolProvider {
    int API_VERSION = 1;

    /** Must be a stable, lowercase provider identifier (such as {@code jdk}). */
    String id();

    /** The subset of the five operations this provider actually implements. */
    Set<M3ToolCapability> capabilities();

    /** Lowercase language identifiers (such as java, cpp, rust or python). */
    Set<String> languages();

    /** Override only when intentionally targeting a different, supported SPI version. */
    default int apiVersion() {
        return API_VERSION;
    }

    /**
     * Validate and return the original tool's command vector. Relative paths
     * refer to {@code workspace}; the runner checks declared source and output
     * locations independently. The planner invokes this on dry runs too.
     */
    List<String> command(M3ToolCapability capability, String language,
            TaskManifest.Task task, Path workspace) throws IOException;
}
