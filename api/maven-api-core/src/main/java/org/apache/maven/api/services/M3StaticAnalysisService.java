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

        /**
         * Count original findings of a given severity. Default delegation
         * preserves pre-existing Manifest implementations without changing
         * any plugin, schema, report or binary contract.
         *
         * @param severity original analyzer severity to count
         * @return number of findings with the requested severity
         */
        default int count(Severity severity) {
            Objects.requireNonNull(severity, "severity");
            int total = 0;
            for (int index = 0; index < size(); index++) {
                if (entry(index).severity() == severity) {
                    total++;
                }
            }
            return total;
        }
    }

    /**
     * Read-only change counts between two complete reports from the same analyzer.
     * A non-comparable result deliberately carries zero counts, not a clean-scan verdict.
     *
     * @param comparable whether both producer reports claim COMPLETE
     * @param unchanged identical normalized findings present in both reports
     * @param introduced findings present only in the later report
     * @param resolved findings present only in the earlier report
     * @param introducedErrors newly reported ERROR-severity findings
     */
    record Comparison(boolean comparable, int unchanged, int introduced, int resolved, int introducedErrors) {
        public Comparison {
            if (unchanged < 0
                    || introduced < 0
                    || resolved < 0
                    || introducedErrors < 0
                    || introducedErrors > introduced
                    || (!comparable && (unchanged != 0 || introduced != 0 || resolved != 0))) {
                throw new IllegalArgumentException("invalid analysis comparison counts");
            }
        }
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

    /**
     * Compare existing reports in canonical v1 finding order without re-running
     * an analyzer, copying whole reports or retaining per-finding objects.
     * Matching project/plugin/version/goal/execution is mandatory; a change
     * in invocation context is allowed but does not prove comparable rules or
     * source coverage. A report status other than COMPLETE is *not* zero findings.
     *
     * <p>This is observational change detection only, not an assertion of
     * causality, a build-cache hit, a release gate or permission to skip a goal.
     *
     * @param earlier original baseline manifest
     * @param later original updated manifest
     * @return bounded change counts, or an explicit non-comparable result
     * @throws IllegalArgumentException for different producers or unordered findings
     */
    default Comparison compare(Manifest earlier, Manifest later) {
        Objects.requireNonNull(earlier, "earlier");
        Objects.requireNonNull(later, "later");
        M3PluginEvidenceService.Identity first =
                Objects.requireNonNull(earlier.receipt(), "earlier receipt").identity();
        M3PluginEvidenceService.Identity second =
                Objects.requireNonNull(later.receipt(), "later receipt").identity();
        if (!sameProducer(first, second)) {
            throw new IllegalArgumentException("analysis reports originate from different producers");
        }
        if (earlier.status() != Status.COMPLETE || later.status() != Status.COMPLETE) {
            return new Comparison(false, 0, 0, 0, 0);
        }
        int firstSize = earlier.size();
        int secondSize = later.size();
        if (firstSize < 0 || secondSize < 0 || firstSize > MAX_FINDINGS || secondSize > MAX_FINDINGS) {
            throw new IllegalArgumentException("analysis comparison finding budget exceeded");
        }
        int i = 0;
        int j = 0;
        int retained = 0;
        int added = 0;
        int removed = 0;
        int newErrors = 0;
        Finding priorFirst = null;
        Finding priorSecond = null;
        while (i < firstSize || j < secondSize) {
            Finding a = i < firstSize ? Objects.requireNonNull(earlier.entry(i), "earlier finding") : null;
            Finding b = j < secondSize ? Objects.requireNonNull(later.entry(j), "later finding") : null;
            if ((a != null && priorFirst != null && compareFinding(priorFirst, a) >= 0)
                    || (b != null && priorSecond != null && compareFinding(priorSecond, b) >= 0)) {
                throw new IllegalArgumentException("analysis comparison requires canonical sorted findings");
            }
            if (a == null || b != null && compareFinding(a, b) > 0) {
                added++;
                if (b.severity() == Severity.ERROR) {
                    newErrors++;
                }
                priorSecond = b;
                j++;
            } else if (b == null || compareFinding(a, b) < 0) {
                removed++;
                priorFirst = a;
                i++;
            } else {
                retained++;
                priorFirst = a;
                priorSecond = b;
                i++;
                j++;
            }
        }
        return new Comparison(true, retained, added, removed, newErrors);
    }

    private static boolean sameProducer(
            M3PluginEvidenceService.Identity first, M3PluginEvidenceService.Identity second) {
        Objects.requireNonNull(first, "earlier identity");
        Objects.requireNonNull(second, "later identity");
        return first.project().equals(second.project())
                && first.pluginGa().equals(second.pluginGa())
                && first.version().equals(second.version())
                && first.goal().equals(second.goal())
                && first.execution().equals(second.execution());
    }

    /** The same value order used by the native v1 report parser. */
    private static int compareFinding(Finding a, Finding b) {
        int value = a.relativePath().compareTo(b.relativePath());
        if (value == 0) {
            value = Integer.compare(a.line(), b.line());
        }
        if (value == 0) {
            value = Integer.compare(a.column(), b.column());
        }
        if (value == 0) {
            value = a.ruleId().compareTo(b.ruleId());
        }
        if (value == 0) {
            value = a.severity().compareTo(b.severity());
        }
        return value == 0 ? a.message().compareTo(b.message()) : value;
    }

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
