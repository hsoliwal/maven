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
package org.apache.maven.impl;

// SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.api.services.M3PluginEvidenceService;
import org.apache.maven.api.services.M3StaticAnalysisService;

/**
 * Native source-bound analyzer receipt reader. No analyzer executable,
 * parser for arbitrary source files, cache controller or recipe executor.
 */
@Named
@Singleton
public final class DefaultM3StaticAnalysisService implements M3StaticAnalysisService {
    private static final String MAGIC = "M3-STATIC-ANALYSIS-V1";
    private static final String COLUMNS = "rule\tseverity\tpath\tline\tcolumn\tmessage64";
    private static final int MAX_LINE = 16_384;
    private static final Severity[] LEVELS = Severity.values();
    private static final Comparator<M3PluginEvidenceService.Identity> IDENTITIES =
            Comparator.comparing(M3PluginEvidenceService.Identity::project)
                    .thenComparing(M3PluginEvidenceService.Identity::pluginGa)
                    .thenComparing(M3PluginEvidenceService.Identity::version)
                    .thenComparing(M3PluginEvidenceService.Identity::goal)
                    .thenComparing(M3PluginEvidenceService.Identity::execution)
                    .thenComparing(M3PluginEvidenceService.Identity::contextSha256)
                    .thenComparingLong(M3PluginEvidenceService.Identity::generation);

    private static final Comparator<Finding> FINDINGS = Comparator.comparing(Finding::relativePath)
            .thenComparingInt(Finding::line)
            .thenComparingInt(Finding::column)
            .thenComparing(Finding::ruleId)
            .thenComparing(Finding::severity)
            .thenComparing(Finding::message);

    private final M3PluginEvidenceService evidence;

    @Inject
    public DefaultM3StaticAnalysisService(M3PluginEvidenceService evidence) {
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    @Override
    public Optional<Manifest> read(Session session, M3PluginEvidenceService.Identity identity) throws IOException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(identity, "identity");
        Optional<M3PluginEvidenceService.Snapshot> selected = evidence.read(session, identity, SCHEMA, CAPABILITIES);
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(verifiedManifest(identity, selected.orElseThrow()));
    }

    /**
     * Atom: validate exact receipt ownership before parsing any untrusted bytes.
     * The native evidence service already authenticated the snapshot bytes.
     */
    private static Manifest verifiedManifest(
            M3PluginEvidenceService.Identity identity, M3PluginEvidenceService.Snapshot snapshot) throws IOException {
        M3PluginEvidenceService.Receipt receipt = snapshot.receipt();
        if (!identity.equals(receipt.identity())
                || !SCHEMA.equals(receipt.schema())
                || (receipt.capabilities() & CAPABILITIES) != CAPABILITIES) {
            throw new IOException("analysis receipt provenance mismatch");
        }
        return parse(receipt, snapshot.bytes());
    }

    @Override
    public Report aggregate(Session session, List<M3PluginEvidenceService.Identity> expected) throws IOException {
        Objects.requireNonNull(session, "session");
        M3PluginEvidenceService.Identity[] identities = sortedExpected(expected);
        List<Coverage> coverage = new ArrayList<>(identities.length);
        List<SourcedFinding> findings = new ArrayList<>();
        boolean allComplete = true;
        for (M3PluginEvidenceService.Identity identity : identities) {
            Optional<Manifest> selected = read(session, identity);
            Status status = selected.map(Manifest::status).orElse(Status.MISSING);
            coverage.add(new Coverage(identity, status));
            allComplete &= status == Status.COMPLETE;
            if (selected.isPresent()) {
                appendFindings(identity, selected.orElseThrow(), findings);
            }
        }
        return new Report(coverage, findings, allComplete);
    }

    /** Atom: validate, sort and deduplicate complete expected identities before any receipt I/O. */
    private static M3PluginEvidenceService.Identity[] sortedExpected(
            List<M3PluginEvidenceService.Identity> expected) {
        Objects.requireNonNull(expected, "expected");
        if (expected.isEmpty() || expected.size() > MAX_PRODUCERS) {
            throw new IllegalArgumentException("analysis producer budget or empty expectation");
        }
        M3PluginEvidenceService.Identity[] identities =
                expected.toArray(M3PluginEvidenceService.Identity[]::new);
        for (M3PluginEvidenceService.Identity identity : identities) {
            Objects.requireNonNull(identity, "expected identity");
        }
        Arrays.sort(identities, IDENTITIES);
        for (int index = 1; index < identities.length; index++) {
            if (identities[index - 1].equals(identities[index])) {
                throw new IllegalArgumentException("duplicate expected analysis invocation");
            }
        }
        return identities;
    }

    /** Atom: bound the aggregate before materializing a producer's finding objects. */
    private static void appendFindings(
            M3PluginEvidenceService.Identity identity, Manifest manifest, List<SourcedFinding> findings)
            throws IOException {
        if (manifest.size() > MAX_FINDINGS - findings.size()) {
            throw new IOException("analysis aggregate finding budget exceeded");
        }
        for (int index = 0; index < manifest.size(); index++) {
            findings.add(new SourcedFinding(identity, manifest.entry(index)));
        }
    }

    private static Manifest parse(M3PluginEvidenceService.Receipt receipt, byte[] bytes) throws IOException {
        if (bytes.length > M3PluginEvidenceService.MAX_RECEIPT_BYTES) {
            throw new IOException("analysis receipt byte budget exceeded");
        }
        String text = decode(bytes);
        Parsed rows = new Parsed();
        Status status = null;
        int row = 0;
        for (int start = 0; start < text.length(); ) {
            int end = text.indexOf('\n', start);
            String line = canonicalRow(text, start, end);
            if (row == 0) {
                if (!MAGIC.equals(line)) {
                    throw new IOException("unexpected analysis report schema");
                }
            } else if (row == 1) {
                status = declaredStatus(line);
            } else if (row == 2) {
                if (!COLUMNS.equals(line)) {
                    throw new IOException("invalid analysis report columns");
                }
            } else {
                rows.add(line);
            }
            row++;
            start = end + 1;
        }
        if (row < 3) {
            throw new IOException("incomplete analysis report header");
        }
        return new Packed(receipt, status, rows);
    }

    /** Atom: enforce LF framing and the bounded canonical row representation. */
    private static String canonicalRow(String text, int start, int end) throws IOException {
        if (end < 0 || end - start > MAX_LINE) {
            throw new IOException("unterminated or oversized analysis report row");
        }
        String row = text.substring(start, end);
        if (row.indexOf('\r') >= 0 || row.indexOf('\0') >= 0) {
            throw new IOException("analysis report requires LF-only rows");
        }
        return row;
    }

    /** Atom: map only producer-declared execution states, never fabricate MISSING as source success. */
    private static Status declaredStatus(String row) throws IOException {
        return switch (row) {
            case "status\tCOMPLETE" -> Status.COMPLETE;
            case "status\tPARTIAL" -> Status.PARTIAL;
            case "status\tFAILED" -> Status.FAILED;
            default -> throw new IOException("invalid analysis completion state");
        };
    }

    private static String decode(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("analysis report is not UTF-8", malformed);
        }
    }

    private static String message(String encoded) throws IOException {
        if (encoded.length() > 12_288 || encoded.isEmpty()) {
            throw new IOException("analysis message is empty or oversized");
        }
        final byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("invalid analysis message encoding", malformed);
        }
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) {
            throw new IOException("noncanonical analysis message encoding");
        }
        if (bytes.length > 8_192) {
            throw new IOException("analysis message byte budget exceeded");
        }
        return decode(bytes);
    }

    private static int position(String text) throws IOException {
        if (text.isEmpty() || text.length() > 10 || (text.length() > 1 && text.charAt(0) == '0')) {
            throw new IOException("noncanonical analysis position");
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                throw new IOException("noncanonical analysis position");
            }
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException overflow) {
            throw new IOException("analysis position overflow", overflow);
        }
    }

    /** Atom: decode and validate an original analyzer row before inserting it into packed lanes. */
    private static Finding parseFinding(String row) throws IOException {
        String[] fields = row.split("\t", -1);
        if (fields.length != 6) {
            throw new IOException("expected six static analysis fields");
        }
        try {
            return new Finding(
                    fields[0], Severity.valueOf(fields[1]), fields[2], position(fields[3]),
                    position(fields[4]), message(fields[5]));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid normalized static analysis finding", invalid);
        }
    }

    /** Immutable backing arrays; report row objects are created only on access. */
    private static final class Parsed {
        private String[] rules = new String[8];
        private String[] paths = new String[8];
        private String[] messages = new String[8];
        private byte[] severities = new byte[8];
        private int[] lines = new int[8];
        private int[] columns = new int[8];
        private int count;
        private final int[] severityCounts = new int[LEVELS.length];
        private Finding previous;

        void add(String row) throws IOException {
            if (count == MAX_FINDINGS) {
                throw new IOException("analysis finding budget exceeded");
            }
            Finding fact = parseFinding(row);
            if (previous != null && FINDINGS.compare(previous, fact) >= 0) {
                throw new IOException("unsorted or duplicate analysis findings");
            }
            previous = fact;
            if (count == rules.length) {
                int length = Math.min(MAX_FINDINGS, rules.length * 2);
                rules = Arrays.copyOf(rules, length);
                paths = Arrays.copyOf(paths, length);
                messages = Arrays.copyOf(messages, length);
                severities = Arrays.copyOf(severities, length);
                lines = Arrays.copyOf(lines, length);
                columns = Arrays.copyOf(columns, length);
            }
            rules[count] = fact.ruleId();
            paths[count] = fact.relativePath();
            messages[count] = fact.message();
            severities[count] = (byte) fact.severity().ordinal();
            lines[count] = fact.line();
            columns[count] = fact.column();
            severityCounts[fact.severity().ordinal()]++;
            count++;
        }
    }

    private static final class Packed implements Manifest {
        private final M3PluginEvidenceService.Receipt receipt;
        private final Status status;
        private final String[] rules;
        private final String[] paths;
        private final String[] messages;
        private final byte[] severities;
        private final int[] lines;
        private final int[] columns;
        private final int[] severityCounts;

        Packed(M3PluginEvidenceService.Receipt receipt, Status status, Parsed parsed) {
            this.receipt = receipt;
            this.status = status;
            rules = Arrays.copyOf(parsed.rules, parsed.count);
            paths = Arrays.copyOf(parsed.paths, parsed.count);
            messages = Arrays.copyOf(parsed.messages, parsed.count);
            severities = Arrays.copyOf(parsed.severities, parsed.count);
            lines = Arrays.copyOf(parsed.lines, parsed.count);
            columns = Arrays.copyOf(parsed.columns, parsed.count);
            severityCounts = parsed.severityCounts.clone();
        }

        @Override
        public M3PluginEvidenceService.Receipt receipt() {
            return receipt;
        }

        @Override
        public Status status() {
            return status;
        }

        @Override
        public int size() {
            return rules.length;
        }

        @Override
        public int count(Severity severity) {
            return severityCounts[Objects.requireNonNull(severity, "severity").ordinal()];
        }

        @Override
        public Finding entry(int index) {
            Objects.checkIndex(index, rules.length);
            return new Finding(
                    rules[index],
                    LEVELS[severities[index]],
                    paths[index],
                    lines[index],
                    columns[index],
                    messages[index]);
        }
    }
}
