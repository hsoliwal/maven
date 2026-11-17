/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

/** Formats a declared provider output may carry; the owner of each format is named. */
public enum M3ReportFormat {
    /** OASIS SARIF 2.1.0 findings. */
    SARIF_2_1_0("sarif-2.1.0"),
    /** M3-STATIC-ANALYSIS-V1, read by Maven's M3StaticAnalysisService. */
    M3_STATIC_ANALYSIS_V1("m3-static-analysis-v1"),
    /** build-events.jsonl v1 lines (diagnostic, test, ...), owned by the build-events extension. */
    BUILD_EVENTS_JSONL_V1("build-events-jsonl-v1"),
    /** ATOM_HASHES.tsv of the code clustering plugin. */
    ATOM_HASHES_TSV("atom-hashes-tsv"),
    /** COMPARE.tsv of the code clustering plugin (and code-patch verify). */
    COMPARE_TSV("compare-tsv"),
    /** PLAN.tsv of the code patch plugin. */
    PLAN_TSV("plan-tsv"),
    /** RECEIPT.tsv of the code patch plugin. */
    RECEIPT_TSV("receipt-tsv"),
    /** git-style unified diff. */
    UNIFIED_DIFF("unified-diff"),
    /** JUnit XML test report. */
    JUNIT_XML("junit-xml");

    private final String slug;

    M3ReportFormat(String slug) {
        this.slug = slug;
    }

    public String slug() {
        return slug;
    }
}
