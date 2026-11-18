/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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

    /** The subset of the supported operations this provider implements. */
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

    /**
     * Optional, complete additional input and output-ownership contract for the
     * runner's explicit provider-reuse mode. The default retains unconditional
     * execution for all existing providers. Returning a contract is a trusted
     * provider assertion, not an automatically established hermeticity proof.
     *
     * <p>The runner hashes these inputs with the actual command, environment,
     * declared inputs and prerequisites before and after execution. A provider
     * must decline when it cannot account for every output-affecting dependency.
     * Qualified outputs must be determined by this captured content and execution
     * context. File timestamps are not part of content identity: an invocation
     * that selects dependencies by mtime, uses undeclared network or process-global
     * state, or depends on uncaptured clock/random state must decline reuse.</p>
     *
     * <p>Planning must not execute tools or mutate the workspace. The registry
     * also binds this provider's implementation code source. That binding does
     * not discover transitive dependencies: every dependent provider library,
     * toolchain resource and configuration read remains the provider's explicit
     * responsibility. Returning an assertion does not sandbox or independently
     * prove the behavior of trusted provider code.</p>
     */
    default Optional<M3ToolReuseInputs> reuseInputs(M3ToolCapability capability, String language,
            TaskManifest.Task task, Path workspace) throws IOException {
        return Optional.empty();
    }
}
