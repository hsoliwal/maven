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
        } else if (args != null && args.length == 3 && "reconcile".equals(args[0])) {
            // Compare declarations only; a MATCH does not prove toolchain or output completeness.
            System.out.println(M3EclipseBuildReconciliation.assess(
                    Path.of(args[1]), Path.of(args[2])).toJson());
        } else if (args != null && args.length == 3 && "jdt-facts".equals(args[0])) {
            // Original JDT compiler options/classpath belong to the same
            // Eclipse invocation and selected configuration, or are rejected.
            System.out.println(M3JdtCompilerFactsWire.inspect(
                    Path.of(args[1]), Path.of(args[2])).toJson());
        } else if (args != null && args.length == 3 && "cdt-facts".equals(args[0])) {
            // Source-owned scanner, resolved language entries and CDT build
            // settings must agree with the original Eclipse invocation.
            System.out.println(M3CdtCompilerFactsWire.inspect(
                    Path.of(args[1]), Path.of(args[2])).toJson());
        } else if (args != null && args.length == 3 && "cargo-facts".equals(args[0])) {
            // Corrosion and native Cargo own the metadata; this is only a
            // source-bound validation of the original invocation.
            System.out.println(M3CargoBuildEvidenceWire.inspect(
                    Path.of(args[1]), Path.of(args[2])).toJson());
        } else if (args != null && args.length == 3 && "tycho-final-p2".equals(args[0])) {
            // Only the original Tycho FINAL platform is source-owned evidence.
            // Do not compute a target or infer p2 resolution from .target.
            System.out.println(M3TychoFinalP2FactsWire.inspect(
                    Path.of(args[1]), Path.of(args[2])).toJson());
        } else {
            throw new IllegalArgumentException(
                    "usage: EclipseModelCli <project-directory> | owners <project-directory>"
                            + " | context <Eclipse-context-packet> | handoff <Eclipse-invocation-packet>"
                            + " | reconcile <project-directory> <Eclipse-invocation-packet>"
                            + " | jdt-facts <JDT-owner-packet> <Eclipse-invocation-packet>"
                            + " | cdt-facts <CDT-owner-packet> <Eclipse-invocation-packet>"
                            + " | cargo-facts <Corrosion-owner-packet> <Eclipse-invocation-packet>"
                            + " | tycho-final-p2 <Tycho-final-packet> <Eclipse-invocation-packet>");
        }
    }
}
