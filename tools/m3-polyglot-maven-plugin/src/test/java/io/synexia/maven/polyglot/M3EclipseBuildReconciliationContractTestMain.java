/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Reconciles original Eclipse declarations without executing JDT, CDT or Cargo. */
public final class M3EclipseBuildReconciliationContractTestMain {
    private M3EclipseBuildReconciliationContractTestMain() { }

    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("m3-eclipse-reconciliation-");
        try {
            Path packet = Files.write(root.resolve("handoff.ide"), golden());
            Path project = Files.writeString(root.resolve(".project"), model(
                    "Mixed", "org.eclipse.jdt.core.javabuilder",
                    "org.eclipse.cdt.managedbuilder.core.genmakebuilder", "vendor.rustdt.nature"));
            check(M3EclipseBuildReconciliation.assess(root, packet),
                    M3EclipseBuildReconciliation.Disposition.DECLARATIONS_MATCH, List.of());
            var unchanged = M3EclipseBuildReconciliation.assess(root, packet);
            require(unchanged.toJson().equals(M3EclipseBuildReconciliation.assess(root, packet).toJson()),
                    "deterministic source-owner reconciliation report");
            require(unchanged.toJson().startsWith("{\"schema\":\"m3-eclipse-reconciliation-v1\""),
                    "canonical advisory schema");
            require(!unchanged.authorizesBuildSkipping(), "matched declarations may never skip work");
            require(unchanged.changeCount() == 2 && unchanged.deltaComplete(), "original delta visible");

            Files.writeString(project, model("Different", "org.eclipse.jdt.core.javabuilder",
                    "org.eclipse.cdt.managedbuilder.core.genmakebuilder", "vendor.rustdt.nature"));
            check(M3EclipseBuildReconciliation.assess(root, packet),
                    M3EclipseBuildReconciliation.Disposition.RECONCILE,
                    List.of(M3EclipseBuildReconciliation.Reason.FOREIGN_PROJECT));
            Files.writeString(project, model("Mixed", "vendor.other.builder",
                    "org.eclipse.cdt.managedbuilder.core.genmakebuilder", "vendor.rustdt.nature"));
            check(M3EclipseBuildReconciliation.assess(root, packet),
                    M3EclipseBuildReconciliation.Disposition.RECONCILE,
                    List.of(M3EclipseBuildReconciliation.Reason.BUILDERS_CHANGED));
            Files.writeString(project, model("Mixed", "org.eclipse.jdt.core.javabuilder",
                    "org.eclipse.cdt.managedbuilder.core.genmakebuilder", "vendor.other.nature"));
            check(M3EclipseBuildReconciliation.assess(root, packet),
                    M3EclipseBuildReconciliation.Disposition.RECONCILE,
                    List.of(M3EclipseBuildReconciliation.Reason.NATURES_CHANGED));

            Files.writeString(project, model("Mixed", "org.eclipse.jdt.core.javabuilder",
                    "org.eclipse.cdt.managedbuilder.core.genmakebuilder", "vendor.rustdt.nature"));
            String original = new String(golden(), java.nio.charset.StandardCharsets.UTF_8);
            Files.writeString(packet, original.replace("complete\ttrue", "complete\tfalse"));
            check(M3EclipseBuildReconciliation.assess(root, packet),
                    M3EclipseBuildReconciliation.Disposition.RECONCILE,
                    List.of(M3EclipseBuildReconciliation.Reason.INCOMPLETE_DELTA));
            Files.writeString(packet, original.replace("kind\tINCREMENTAL", "kind\tFULL")
                    .replace("complete\ttrue", "complete\tfalse"));
            check(M3EclipseBuildReconciliation.assess(root, packet),
                    M3EclipseBuildReconciliation.Disposition.RECONCILE,
                    List.of(M3EclipseBuildReconciliation.Reason.FULL_OR_CLEAN,
                            M3EclipseBuildReconciliation.Reason.INCOMPLETE_DELTA));
            require(Files.isRegularFile(project), "original model file not modified by assessment");
            System.out.println("PASS: Eclipse original invocation vs current model reconciliation");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static byte[] golden() throws IOException {
        try (var input = M3EclipseBuildReconciliationContractTestMain.class.getResourceAsStream(
                "/m3-eclipse-build-handoff-v1.fixture")) {
            if (input == null) throw new IOException("missing canonical Eclipse handoff fixture");
            return input.readAllBytes();
        }
    }

    private static String model(String project, String first, String second, String nature) {
        return "<projectDescription><name>" + project + "</name><buildSpec>"
                + "<buildCommand><name>" + first + "</name></buildCommand>"
                + "<buildCommand><name>" + second + "</name></buildCommand>"
                + "</buildSpec><natures><nature>" + nature
                + "</nature></natures></projectDescription>";
    }

    private static void check(M3EclipseBuildReconciliation.Assessment report,
            M3EclipseBuildReconciliation.Disposition wanted,
            List<M3EclipseBuildReconciliation.Reason> reasons) {
        require(report.disposition() == wanted, "unexpected reconciliation disposition");
        require(report.reasons().equals(reasons), "incorrect reconciliation reasons");
        require(!report.authorizesBuildSkipping(), "reconciliation is never cache admission");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
