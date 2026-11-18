/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Cross-checks one Eclipse-owned invocation against the present, read-only
 * Eclipse descriptor snapshot. This check does not compute a compiler graph,
 * run an Eclipse builder, authenticate a workspace generation or admit any
 * Maven/Gradle cache hit. M3ExternalBuildService still owns generation/delta
 * classification, and native Eclipse builders own real incremental state.
 */
public final class M3EclipseBuildReconciliation {
    private M3EclipseBuildReconciliation() { }

    public enum Disposition { DECLARATIONS_MATCH, RECONCILE }
    public enum Reason {
        FOREIGN_PROJECT,
        BUILDERS_CHANGED,
        NATURES_CHANGED,
        FULL_OR_CLEAN,
        INCOMPLETE_DELTA
    }

    public record Assessment(String modelProject, String ownerProject,
            String selectedConfiguration, String activeConfiguration,
            String invocation, String buildKind, boolean deltaComplete,
            int changeCount, String descriptorSha256, Disposition disposition,
            List<Reason> reasons) {
        public Assessment {
            Objects.requireNonNull(disposition, "disposition");
            reasons = List.copyOf(reasons);
        }

        /** Canonical, versioned decision evidence, never an execution command. */
        public String toJson() {
            StringBuilder out = new StringBuilder("{\"schema\":\"m3-eclipse-reconciliation-v1\"");
            field(out, "modelProject", modelProject);
            field(out, "ownerProject", ownerProject);
            field(out, "selectedConfiguration", selectedConfiguration);
            field(out, "activeConfiguration", activeConfiguration);
            field(out, "invocation", invocation);
            field(out, "buildKind", buildKind);
            out.append(",\"deltaComplete\":").append(deltaComplete);
            out.append(",\"changeCount\":").append(changeCount);
            field(out, "descriptorSha256", descriptorSha256);
            field(out, "disposition", disposition.name());
            out.append(",\"reasons\":[");
            for (int i = 0; i < reasons.size(); i++) {
                if (i != 0) out.append(',');
                quote(out, reasons.get(i).name());
            }
            return out.append("]}").toString();
        }

        public boolean authorizesBuildSkipping() { return false; }
    }

    /** Opens no Eclipse runtime and writes no source or build files. */
    public static Assessment assess(Path eclipseProject, Path handoffPacket) throws IOException {
        Objects.requireNonNull(eclipseProject, "eclipseProject");
        Objects.requireNonNull(handoffPacket, "handoffPacket");
        return assess(EclipseProjectModel.inspect(eclipseProject),
                M3EclipseBuildHandoffWire.read(handoffPacket));
    }

    /** Testable with detached immutable models, without running a compiler. */
    static Assessment assess(EclipseProjectModel.Snapshot model,
            M3EclipseBuildHandoffWire.Snapshot handoff) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(handoff, "handoff");
        List<Reason> reasons = new ArrayList<>(5);
        if (!model.name().equals(handoff.project())) reasons.add(Reason.FOREIGN_PROJECT);
        if (!model.builders().equals(handoff.builders())) reasons.add(Reason.BUILDERS_CHANGED);
        if (!model.natureIds().equals(handoff.natures())) reasons.add(Reason.NATURES_CHANGED);
        if (handoff.buildKind().equals("FULL") || handoff.buildKind().equals("CLEAN")) {
            reasons.add(Reason.FULL_OR_CLEAN);
        }
        if (!handoff.deltaComplete()) reasons.add(Reason.INCOMPLETE_DELTA);
        return new Assessment(model.name(), handoff.project(),
                handoff.selectedConfiguration(), handoff.activeConfiguration(),
                handoff.invocation(), handoff.buildKind(), handoff.deltaComplete(),
                handoff.size(), model.descriptorSha256().getOrDefault(".project", ""),
                reasons.isEmpty() ? Disposition.DECLARATIONS_MATCH : Disposition.RECONCILE,
                reasons);
    }

    private static void field(StringBuilder out, String name, String value) {
        out.append(',');
        quote(out, name);
        out.append(':');
        quote(out, value);
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) {
                            out.append(Character.forDigit((c >>> shift) & 15, 16));
                        }
                    } else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
