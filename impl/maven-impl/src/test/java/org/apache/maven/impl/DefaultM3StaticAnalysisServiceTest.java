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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.apache.maven.api.Session;
import org.apache.maven.api.SessionData;
import org.apache.maven.api.services.M3PluginEvidenceService;
import org.apache.maven.api.services.M3StaticAnalysisService;
import org.apache.maven.di.Injector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Tests the original native evidence membership, strict parser, and bounded report indexing. */
final class DefaultM3StaticAnalysisServiceTest {
    private static final String HEADER =
            "M3-STATIC-ANALYSIS-V1\nstatus\tCOMPLETE\nrule\tseverity\tpath\tline\tcolumn\tmessage64\n";
    private static final String MESSAGE = encoded("A reported problem");

    @TempDir
    Path directory;

    @Test
    void originalMavenInjectorSelectsOneNativeAnalysisService() {
        Injector injector = Injector.create();
        try {
            injector.bindInstance(M3PluginEvidenceService.class, mock(M3PluginEvidenceService.class));
            injector.bindImplicit(DefaultM3StaticAnalysisService.class);
            M3StaticAnalysisService selected = injector.getInstance(M3StaticAnalysisService.class);
            assertInstanceOf(DefaultM3StaticAnalysisService.class, selected);
            assertSame(selected, injector.getInstance(M3StaticAnalysisService.class));
        } finally {
            injector.dispose();
        }
    }

    @Test
    void authenticOriginalEvidenceProvidesImmutableSortedFindings() throws Exception {
        var original = new DefaultM3PluginEvidenceService();
        Session session = session();
        var identity = identity("maven-checkstyle-plugin", 1);
        Path file = publish(
                original,
                session,
                identity,
                HEADER
                        + row("A.Rule", "ERROR", "src/main/java/A.java", "4", "2", MESSAGE)
                        + row("B.Rule", "WARNING", "src/main/java/B.java", "12", "0", MESSAGE));
        var service = new DefaultM3StaticAnalysisService(original);
        var read = service.read(session, identity).orElseThrow();
        assertEquals(2, read.size());
        assertEquals(M3StaticAnalysisService.Status.COMPLETE, read.status());
        assertEquals(identity, read.receipt().identity());
        assertEquals("A.Rule", read.entry(0).ruleId());
        assertEquals("src/main/java/B.java", read.entry(1).relativePath());
        assertEquals(12, read.entry(1).line());
        assertEquals(1, read.count(M3StaticAnalysisService.Severity.ERROR));
        assertEquals(1, read.count(M3StaticAnalysisService.Severity.WARNING));
        assertEquals(0, read.count(M3StaticAnalysisService.Severity.INFO));
        assertThrows(NullPointerException.class, () -> read.count(null));
        assertThrows(IndexOutOfBoundsException.class, () -> read.entry(2));

        Files.writeString(file, "modified after authenticated snapshot");
        assertEquals(2, read.size(), "published read remains a stable parsed snapshot");
        assertThrows(IOException.class, () -> service.read(session, identity));
        original.revoke(session, identity);
        assertTrue(service.read(session, identity).isEmpty(), "revocation never deletes producer bytes");
    }

    @Test
    void comparesOnlyCompletedAnalyzerReportsWithLinearSortedMerge() throws Exception {
        var provider = new DefaultM3PluginEvidenceService();
        var service = new DefaultM3StaticAnalysisService(provider);
        Session session = session();
        var baseline = identity("maven-checkstyle-plugin", 71);
        var current = identity("maven-checkstyle-plugin", 72);
        publish(
                provider,
                session,
                baseline,
                HEADER
                        + row("A.Rule", "ERROR", "src/A.java", "1", "1", encoded("same"))
                        + row("C.Rule", "WARNING", "src/C.java", "2", "1", encoded("resolved"))
                        + row("D.Rule", "INFO", "src/D.java", "2", "1", encoded("same")));
        publish(
                provider,
                session,
                current,
                HEADER
                        + row("A.Rule", "ERROR", "src/A.java", "1", "1", encoded("same"))
                        + row("B.Rule", "ERROR", "src/B.java", "2", "1", encoded("introduced"))
                        + row("D.Rule", "INFO", "src/D.java", "2", "1", encoded("same"))
                        + row("E.Rule", "WARNING", "src/E.java", "4", "1", encoded("introduced")));
        var before = service.read(session, baseline).orElseThrow();
        var after = service.read(session, current).orElseThrow();
        var comparison = service.compare(before, after);
        assertTrue(comparison.comparable());
        assertEquals(2, comparison.unchanged());
        assertEquals(2, comparison.introduced());
        assertEquals(1, comparison.resolved());
        assertEquals(1, comparison.introducedErrors());

        var reversed = service.compare(after, before);
        assertEquals(2, reversed.unchanged());
        assertEquals(1, reversed.introduced());
        assertEquals(2, reversed.resolved());
        assertEquals(0, reversed.introducedErrors());
    }

    @Test
    void changesInMessageAreNewAndResolvedRatherThanAssumedIdentical() throws Exception {
        var provider = new DefaultM3PluginEvidenceService();
        var service = new DefaultM3StaticAnalysisService(provider);
        Session session = session();
        var baseline = identity("maven-pmd-plugin", 73);
        var current = identity("maven-pmd-plugin", 74);
        publish(provider, session, baseline, HEADER + row("A.Rule", "WARNING", "src/A.java", "1", "1", encoded("old")));
        publish(provider, session, current, HEADER + row("A.Rule", "WARNING", "src/A.java", "1", "1", encoded("new")));
        var comparison = service.compare(
                service.read(session, baseline).orElseThrow(),
                service.read(session, current).orElseThrow());
        assertEquals(0, comparison.unchanged());
        assertEquals(1, comparison.introduced());
        assertEquals(1, comparison.resolved());
        assertEquals(0, comparison.introducedErrors());
    }

    @Test
    void unrelatedAndIncompleteAnalyzersCannotBeCalledCleanComparisons() throws Exception {
        var provider = new DefaultM3PluginEvidenceService();
        var service = new DefaultM3StaticAnalysisService(provider);
        Session session = session();
        var baseline = identity("maven-pmd-plugin", 75);
        var partial = identity("maven-pmd-plugin", 76);
        var other = identity("maven-checkstyle-plugin", 77);
        publish(provider, session, baseline, HEADER);
        publish(provider, session, partial, HEADER.replace("status\tCOMPLETE", "status\tPARTIAL"));
        publish(provider, session, other, HEADER);
        var old = service.read(session, baseline).orElseThrow();
        var incomplete = service.compare(old, service.read(session, partial).orElseThrow());
        assertFalse(incomplete.comparable());
        assertEquals(0, incomplete.introduced());
        assertEquals(0, incomplete.resolved());
        assertThrows(
                IllegalArgumentException.class,
                () -> service.compare(old, service.read(session, other).orElseThrow()));
        assertThrows(NullPointerException.class, () -> service.compare(null, old));
        assertThrows(IllegalArgumentException.class, () -> new M3StaticAnalysisService.Comparison(false, 0, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new M3StaticAnalysisService.Comparison(true, 0, 1, 0, 2));
    }

    @Test
    void rejectsUnsortedThirdPartyManifestsInsteadOfFabricatingDeltaCounts() throws Exception {
        var provider = new DefaultM3PluginEvidenceService();
        var service = new DefaultM3StaticAnalysisService(provider);
        Session session = session();
        var identity = identity("maven-pmd-plugin", 78);
        publish(provider, session, identity, HEADER);
        var original = service.read(session, identity).orElseThrow();
        M3StaticAnalysisService.Manifest forged = mock(M3StaticAnalysisService.Manifest.class);
        when(forged.receipt()).thenReturn(original.receipt());
        when(forged.status()).thenReturn(M3StaticAnalysisService.Status.COMPLETE);
        when(forged.size()).thenReturn(2);
        when(forged.entry(0))
                .thenReturn(new M3StaticAnalysisService.Finding(
                        "Z.Rule", M3StaticAnalysisService.Severity.ERROR, "src/Z.java", 1, 1, "z"));
        when(forged.entry(1))
                .thenReturn(new M3StaticAnalysisService.Finding(
                        "A.Rule", M3StaticAnalysisService.Severity.ERROR, "src/A.java", 1, 1, "a"));
        assertThrows(IllegalArgumentException.class, () -> service.compare(forged, original));
    }

    @Test
    void additiveDefaultSeverityCountWorksForExistingManifestImplementations() throws Exception {
        var evidence = new DefaultM3PluginEvidenceService();
        Session session = session();
        var identity = identity("maven-pmd-plugin", 444);
        publish(
                evidence,
                session,
                identity,
                HEADER
                        + row("RuleA", "ERROR", "src/A.java", "1", "1", MESSAGE)
                        + row("RuleB", "WARNING", "src/B.java", "1", "1", MESSAGE));
        var original = new DefaultM3StaticAnalysisService(evidence)
                .read(session, identity)
                .orElseThrow();
        M3StaticAnalysisService.Manifest legacy = new M3StaticAnalysisService.Manifest() {
            @Override
            public M3PluginEvidenceService.Receipt receipt() {
                return original.receipt();
            }

            @Override
            public M3StaticAnalysisService.Status status() {
                return original.status();
            }

            @Override
            public int size() {
                return original.size();
            }

            @Override
            public M3StaticAnalysisService.Finding entry(int index) {
                return original.entry(index);
            }
        };
        assertEquals(1, legacy.count(M3StaticAnalysisService.Severity.ERROR));
        assertEquals(1, legacy.count(M3StaticAnalysisService.Severity.WARNING));
        assertEquals(0, legacy.count(M3StaticAnalysisService.Severity.INFO));
        assertEquals(
                original.count(M3StaticAnalysisService.Severity.ERROR),
                legacy.count(M3StaticAnalysisService.Severity.ERROR));
    }

    @Test
    void missingPartialAndFailedRunsNeverBecomeSuccessfulCoverage() throws Exception {
        var provider = new DefaultM3PluginEvidenceService();
        var service = new DefaultM3StaticAnalysisService(provider);
        Session session = session();
        var checkstyle = identity("maven-checkstyle-plugin", 1);
        var pmd = identity("maven-pmd-plugin", 2);
        var spotbugs = identity("spotbugs-maven-plugin", 3);
        publish(provider, session, checkstyle, HEADER + row("A", "INFO", "src/A.java", "1", "1", MESSAGE));
        publish(
                provider,
                session,
                pmd,
                HEADER.replace("status\tCOMPLETE", "status\tPARTIAL")
                        + row("B", "WARNING", "src/B.java", "2", "0", MESSAGE));

        var report = service.aggregate(session, List.of(spotbugs, pmd, checkstyle));
        assertFalse(report.complete());
        assertEquals(3, report.coverage().size());
        assertEquals(2, report.findings().size());
        assertEquals(
                M3StaticAnalysisService.Status.MISSING, report.coverage().get(0).status());
        assertEquals(
                M3StaticAnalysisService.Status.COMPLETE,
                report.coverage().get(1).status());
        assertEquals(
                M3StaticAnalysisService.Status.PARTIAL, report.coverage().get(2).status());
        assertEquals(checkstyle, report.findings().get(0).identity());
        assertThrows(
                UnsupportedOperationException.class, () -> report.findings().clear());
        assertThrows(
                UnsupportedOperationException.class, () -> report.coverage().clear());

        publish(provider, session, spotbugs, HEADER.replace("status\tCOMPLETE", "status\tFAILED"));
        assertEquals(
                M3StaticAnalysisService.Status.FAILED,
                service.aggregate(session, List.of(spotbugs)).coverage().get(0).status());
        assertTrue(service.read(session(), checkstyle).isEmpty(), "no cross-session evidence");
    }

    @Test
    void matchingAllProducerClaimsIsReportedAsCompleteNotAsSemanticProof() throws Exception {
        var provider = new DefaultM3PluginEvidenceService();
        var service = new DefaultM3StaticAnalysisService(provider);
        Session session = session();
        var pmd = identity("maven-pmd-plugin", 1);
        var checks = identity("maven-checkstyle-plugin", 2);
        publish(provider, session, pmd, HEADER);
        publish(provider, session, checks, HEADER);
        var report = service.aggregate(session, List.of(pmd, checks));
        assertTrue(report.complete());
        assertEquals(0, report.findings().size());
        assertEquals(2, report.coverage().size());
    }

    @Test
    void atomizedAggregationRetainsOriginalSourceOrderingAcrossInputPermutations() throws Exception {
        var provider = new DefaultM3PluginEvidenceService();
        Session session = session();
        var checkstyle = identity("maven-checkstyle-plugin", 5);
        var pmd = identity("maven-pmd-plugin", 4);
        publish(provider, session, pmd, HEADER + row("PMD.Rule", "ERROR", "src/B.java", "5", "1", MESSAGE));
        publish(
                provider,
                session,
                checkstyle,
                HEADER + row("Checkstyle.Rule", "WARNING", "src/A.java", "2", "3", MESSAGE));

        var service = new DefaultM3StaticAnalysisService(provider);
        var expected = service.aggregate(session, List.of(checkstyle, pmd));
        var reversed = service.aggregate(session, List.of(pmd, checkstyle));
        assertEquals(expected, reversed);
        assertTrue(expected.complete());
        assertEquals(checkstyle, expected.coverage().get(0).identity());
        assertEquals(checkstyle, expected.findings().get(0).identity());
        assertEquals(pmd, expected.findings().get(1).identity());
    }

    @Test
    void canonicalRowFramingAtomRefusesOverlongUnterminatedAndEmptyHeader() throws Exception {
        String[] invalid = {
            "M3-STATIC-ANALYSIS-V1",
            HEADER + "x".repeat(16_385) + "\n",
            HEADER + row("Rule", "ERROR", "src/A.java", "1", "1", MESSAGE) + "extra-without-final-LF"
        };
        for (int index = 0; index < invalid.length; index++) {
            var provider = new DefaultM3PluginEvidenceService();
            Session session = session();
            var identity = identity("maven-pmd-plugin", index + 100);
            publish(provider, session, identity, invalid[index]);
            assertThrows(IOException.class, () -> new DefaultM3StaticAnalysisService(provider).read(session, identity));
        }
    }

    @Test
    void duplicateOrExcessiveExpectedAnalyzersAreRejectedBeforeLookups() {
        M3PluginEvidenceService evidence = mock(M3PluginEvidenceService.class);
        var service = new DefaultM3StaticAnalysisService(evidence);
        Session session = mock(Session.class);
        var pmd = identity("maven-pmd-plugin", 1);
        assertThrows(IllegalArgumentException.class, () -> service.aggregate(session, List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.aggregate(session, List.of(pmd, pmd)));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.aggregate(
                        session, java.util.Collections.nCopies(M3StaticAnalysisService.MAX_PRODUCERS + 1, pmd)));
        assertThrows(NullPointerException.class, () -> service.aggregate(null, List.of(pmd)));
        verifyNoInteractions(evidence);
    }

    @Test
    void malformedStrictReportsFailClosed() throws Exception {
        String one = row("A.Rule", "ERROR", "src/A.java", "1", "1", MESSAGE);
        String[] invalid = {
            "\ufeff" + HEADER,
            HEADER.replace("M3-STATIC-ANALYSIS-V1", "M3-STATIC-ANALYSIS-V2"),
            HEADER.replace("status\tCOMPLETE", "status\tMISSING"),
            HEADER.replace("status\tCOMPLETE", "status\tBOGUS"),
            HEADER.replace("message64", "message"),
            HEADER.replace("\n", "\r\n"),
            HEADER + one.substring(0, one.length() - 1),
            HEADER + row("A.Rule", "ERROR", "../escape", "1", "1", MESSAGE),
            HEADER + row("A.Rule", "ERROR", "C:/escape", "1", "1", MESSAGE),
            HEADER + row("A.Rule", "ERROR", "src\\A.java", "1", "1", MESSAGE),
            HEADER + row("A.Rule", "FATAL", "src/A.java", "1", "1", MESSAGE),
            HEADER + row("A.Rule", "ERROR", "src/A.java", "01", "1", MESSAGE),
            HEADER + row("A.Rule", "ERROR", "src/A.java", "0", "1", MESSAGE),
            HEADER + row("A.Rule", "ERROR", "src/A.java", "1", "-1", MESSAGE),
            HEADER + row("A.Rule", "ERROR", "src/A.java", "1", "1", "QQ=="),
            HEADER + row("A.Rule", "ERROR", "src/A.java", "1", "1", "***"),
            HEADER + one + one,
            HEADER + row("Z.Rule", "ERROR", "src/Z.java", "1", "1", MESSAGE) + one
        };
        for (int index = 0; index < invalid.length; index++) {
            var provider = new DefaultM3PluginEvidenceService();
            var session = session();
            var id = identity("maven-pmd-plugin", index + 1);
            publish(provider, session, id, invalid[index]);
            var service = new DefaultM3StaticAnalysisService(provider);
            assertThrows(IOException.class, () -> service.read(session, id), "case " + index);
        }
    }

    @Test
    void inputLocationValidationPreservesOnlyCanonicalPathsAndPositions() {
        var level = M3StaticAnalysisService.Severity.ERROR;
        assertThrows(
                IllegalArgumentException.class,
                () -> new M3StaticAnalysisService.Finding("Rule", level, "../outside", 1, 1, "bad"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new M3StaticAnalysisService.Finding("Rule", level, "C:/absolute", 1, 1, "bad"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new M3StaticAnalysisService.Finding("Rule", level, "a\\b", 1, 1, "bad"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new M3StaticAnalysisService.Finding("Rule", level, "", 1, 1, "bad"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new M3StaticAnalysisService.Finding("Rule", level, "A.java", 0, 1, "bad"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new M3StaticAnalysisService.Finding("Rule", level, String.valueOf((char) 0xD800), 0, 0, "bad"));
        assertEquals("", new M3StaticAnalysisService.Finding("Rule", level, "", 0, 0, "project").relativePath());
    }

    @Test
    void malformedUtf8FromOriginalAuthenticatedReportIsRefused() throws Exception {
        var provider = new DefaultM3PluginEvidenceService();
        Session session = session();
        var identity = identity("maven-pmd-plugin", 1);
        byte[] prefix = HEADER.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = java.util.Arrays.copyOf(prefix, prefix.length + 2);
        bytes[prefix.length] = (byte) 0xc3;
        bytes[prefix.length + 1] = (byte) 0x28;
        Path file = directory.resolve("invalid-utf8.tsv");
        Files.write(file, bytes);
        provider.publish(
                session,
                new M3PluginEvidenceService.Receipt(
                        identity,
                        file,
                        M3StaticAnalysisService.SCHEMA,
                        sha(bytes),
                        M3StaticAnalysisService.CAPABILITIES));
        assertThrows(IOException.class, () -> new DefaultM3StaticAnalysisService(provider).read(session, identity));
    }

    @Test
    void byteCustodyAndCapabilityMustMatchOriginalPluginProducer() throws Exception {
        var provider = new DefaultM3PluginEvidenceService();
        Session session = session();
        var identity = identity("maven-pmd-plugin", 1);
        Path path = directory.resolve("without-capability.txt");
        Files.writeString(path, HEADER);
        var receipt = new M3PluginEvidenceService.Receipt(
                identity,
                path,
                M3StaticAnalysisService.SCHEMA,
                sha(Files.readAllBytes(path)),
                M3PluginEvidenceService.INPUTS);
        provider.publish(session, receipt);
        var consumer = new DefaultM3StaticAnalysisService(provider);
        assertFalse(consumer.read(session, identity).isPresent());
        assertFalse(consumer.aggregate(session, List.of(identity)).complete());
    }

    private Path publish(
            M3PluginEvidenceService evidence,
            Session session,
            M3PluginEvidenceService.Identity identity,
            String content)
            throws Exception {
        Path path = Files.createTempFile(directory, "original-analysis-", ".tsv");
        Files.writeString(path, content, StandardCharsets.UTF_8);
        var receipt = new M3PluginEvidenceService.Receipt(
                identity,
                path,
                M3StaticAnalysisService.SCHEMA,
                sha(Files.readAllBytes(path)),
                M3StaticAnalysisService.CAPABILITIES);
        evidence.publish(session, receipt);
        return path;
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String encoded(String message) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(message.getBytes(StandardCharsets.UTF_8));
    }

    private static String row(String rule, String severity, String path, String line, String column, String message64) {
        return rule + "\t" + severity + "\t" + path + "\t" + line + "\t" + column + "\t" + message64 + "\n";
    }

    private static M3PluginEvidenceService.Identity identity(String plugin, long generation) {
        String ga = "spotbugs-maven-plugin".equals(plugin)
                ? "com.github.spotbugs:" + plugin
                : "org.apache.maven.plugins:" + plugin;
        return new M3PluginEvidenceService.Identity(
                "sample:demo:1.0", ga, "1.0", "check", "default-check", "a".repeat(64), generation);
    }

    private static Session session() {
        Session value = mock(Session.class);
        when(value.getData()).thenReturn(new Memory());
        return value;
    }

    private static final class Memory implements SessionData {
        private final Map<Key<?>, Object> values = new ConcurrentHashMap<>();

        @Override
        public synchronized <T> void set(Key<T> key, T value) {
            if (value == null) {
                values.remove(key);
            } else {
                values.put(key, value);
            }
        }

        @Override
        public synchronized <T> boolean replace(Key<T> key, T before, T after) {
            if (values.get(key) != before) {
                return false;
            }
            set(key, after);
            return true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Key<T> key) {
            return (T) values.get(key);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T computeIfAbsent(Key<T> key, Supplier<T> supply) {
            return (T) values.computeIfAbsent(key, ignored -> supply.get());
        }
    }
}
