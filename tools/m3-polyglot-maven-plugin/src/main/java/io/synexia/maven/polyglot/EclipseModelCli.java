/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.nio.file.Path;

/** Portable read-only Eclipse descriptor entry point; requires neither Maven nor Eclipse runtime. */
public final class EclipseModelCli {
    private EclipseModelCli() { }

    public static void main(String[] args) throws Exception {
        if (args != null && args.length == 1) {
            // Preserve the existing portable CLI and m3-eclipse-project-v1 schema.
            System.out.println(EclipseProjectModel.inspect(Path.of(args[0])).toJson());
        } else if (args != null && args.length == 2 && "owners".equals(args[0])) {
            // Deliberately read-only: no Eclipse builder, Gradle task or Maven goal is executed.
            System.out.println(M3EclipseBuilderRegistry.inspect(Path.of(args[1])).toJson());
        } else if (args != null && args.length == 2 && "context".equals(args[0])) {
            // Original Eclipse host writes this packet. It is not a cache or build success receipt.
            System.out.println(M3EclipseBuildContextWire.read(Path.of(args[1])).toJson());
        } else if (args != null && args.length == 2 && "handoff".equals(args[0])) {
            // Both original facts are bound by the same m2e builder invocation.
            // The detached record cannot authorize a Maven/Gradle cache hit.
            System.out.println(M3EclipseBuildHandoffWire.read(Path.of(args[1])).toJson());
        } else {
            throw new IllegalArgumentException(
                    "usage: EclipseModelCli <project-directory> | owners <project-directory>"
                            + " | context <Eclipse-context-packet> | handoff <Eclipse-invocation-packet>");
        }
    }
}
