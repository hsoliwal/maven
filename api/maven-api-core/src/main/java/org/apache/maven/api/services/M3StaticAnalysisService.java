/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.api.services;

// SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.maven.api.Service;
import org.apache.maven.api.Session;
import org.apache.maven.api.annotations.Experimental;

/**
 * Native, read-only convergence point for original static-analyzer findings.
 *
 * <p>Checkstyle, PMD/CPD, SpotBugs, Error Prone, Semgrep, CodeQL and other
 * original engines continue to own their rules, scans, plugin goals and
 * licensing. An adapter may publish a versioned normalized report through
 * {@link M3PluginEvidenceService}; this service authenticates its receipt
 * bytes and parses only that report. It neither executes analyzers nor
 * validates their semantic claims. Findings cannot authorize recipes,
 * Mojo skipping, build-cache reuse or success statuses.
 */
@Experimental
public interface M3StaticAnalysisService extends Service {
    String SCHEMA = "m3.static-analysis.findings.v1";
    int CAPABILITIES = M3PluginEvidenceService.ANALYSIS;
    int MAX_PRODUCERS = 64;
    int MAX_FINDINGS = 65_536;

    /** Original engine severity, normalized only by the producing adapter. */
    enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    /** Provenance-aware analyzer execution status; missing is never success. */
    enum Status {
        COMPLETE,
        PARTIAL,
        FAILED,
        MISSING
    }

    /**
     * A finding reported by the original analyzer, not a Maven assertion.
     *
     * @param ruleId original analyzer-specific rule identifier
     * @param severity severity claimed by the original analyzer adapter
     * @param relativePath canonical project-relative source path or empty for project-wide findings
     * @param line original one-based line, or zero for file/project-wide findings
     * @param column original one-based column, or zero when unspecified
     * @param message bounded untrusted explanation supplied by the analyzer
     */
    record Finding(String ruleId, Severity severity, String relativePath, int line, int column, String message) {
        public Finding {
            ruleId = requireText(ruleId, "ruleId", 256);
            severity = Objects.requireNonNull(severity, "severity");
            relativePath = requirePath(relativePath);
            message = requireText(message, "message", 2_048);
            if (line < 0
                    || column < 0
                    || (line == 0 && column != 0)
                    || (relativePath.isEmpty() && (line != 0 || column != 0))) {
                throw new IllegalArgumentException("invalid finding position");
            }
        }
    }

    /**
     * Source-authenticated, immutable report over compact indexed finding data.
     * Its status is a producer claim, not proof that scanning really finished.
     */
    interface Manifest {
        /** @return the exact original authenticated producer receipt */
        M3PluginEvidenceService.Receipt receipt();

        /** @return the execution status claimed by the adapter */
        Status status();

        /** @return number of bounded findings in the authenticated manifest */
        int size();

        /** @return finding at the requested index, materialized on demand */
        Finding entry(int index);
    }

    /**
     * The exact invocation expected from the build and its reporting status.
     *
     * @param identity original project/plugin/goal/context/generation identity
     * @param status COMPLETE, PARTIAL, FAILED or MISSING
     */
    record Coverage(M3PluginEvidenceService.Identity identity, Status status) {
        public Coverage {
            identity = Objects.requireNonNull(identity, "identity");
            status = Objects.requireNonNull(status, "status");
        }
    }

    /**
     * A finding associated with its original source-bound producer.
     *
     * @param identity original project/plugin/goal/context/generation identity
     * @param finding original analyzer finding without cross-engine severity changes
     */
    record SourcedFinding(M3PluginEvidenceService.Identity identity, Finding finding) {
        public SourcedFinding {
            identity = Objects.requireNonNull(identity, "identity");
            finding = Objects.requireNonNull(finding, "finding");
        }
    }

    /**
     * Deterministic aggregate of exact requested analysis invocations.
     *
     * <p>Complete means every requested receipt exists and claims COMPLETE;
     * it does not authenticate analyzer execution or prove semantic safety.
     *
     * @param coverage ordered provenance/status rows, including missing analyzers
     * @param findings ordered per-analyzer original findings
     * @param complete true only if every expected invocation reports COMPLETE
     */
    record Report(List<Coverage> coverage, List<SourcedFinding> findings, boolean complete) {
        public Report {
            coverage = List.copyOf(Objects.requireNonNull(coverage, "coverage"));
            findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
            boolean allComplete =
                    !coverage.isEmpty() && coverage.stream().allMatch(row -> row.status() == Status.COMPLETE);
            if (complete != allComplete) {
                throw new IllegalArgumentException("inconsistent analysis completion claim");
            }
        }
    }

    /**
     * Read only a matching original analyzer receipt; do not run a plugin
     * when it is missing. Invalid/mutated authenticated data fails closed.
     *
     * @param session original Maven session
     * @param identity exact original analyzer invocation identity
     * @return authenticated typed manifest or empty when unpublished
     * @throws IOException on malformed, untrusted or mutated report bytes
     */
    Optional<Manifest> read(Session session, M3PluginEvidenceService.Identity identity) throws IOException;

    /**
     * Join exactly the expected original analyzer invocations, in deterministic
     * identity order. Missing receipts remain MISSING; failed and partial
     * producer statuses are not silently interpreted as successful analysis.
     *
     * @param session original Maven session
     * @param expected nonempty list of exact, distinct analyzer invocations
     * @return immutable aggregate, including explicit missing/failed coverage
     * @throws IOException on malformed or modified published evidence
     */
    Report aggregate(Session session, List<M3PluginEvidenceService.Identity> expected) throws IOException;

    private static String requireText(String value, String field, int limit) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > limit || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field);
        }
        checkUnicode(value);
        return value;
    }

    private static String requirePath(String value) {
        Objects.requireNonNull(value, "relativePath");
        if (value.isEmpty()) {
            return value;
        }
        if (value.length() > 2_048
                || value.startsWith("/")
                || value.indexOf(':') >= 0
                || value.indexOf('\\') >= 0
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("noncanonical analysis path");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("noncanonical analysis path segment");
            }
        }
        checkUnicode(value);
        return value;
    }

    private static void checkUnicode(String text) {
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.isHighSurrogate(value)) {
                if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) {
                    throw new IllegalArgumentException("unpaired UTF-16 surrogate");
                }
            } else if (Character.isLowSurrogate(value)) {
                throw new IllegalArgumentException("unpaired UTF-16 surrogate");
            }
        }
    }
}
