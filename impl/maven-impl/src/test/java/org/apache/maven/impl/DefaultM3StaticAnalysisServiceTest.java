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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import static org.mockito.ArgumentMatchers.eq;
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
        Path file = publish(original, session, identity,
                HEADER + row("A.Rule", "ERROR", "src/main/java/A.java", "4", "2", MESSAGE)
                        + row("B.Rule", "WARNING", "src/main/java/B.java", "12", "0", MESSAGE));
        var service = new DefaultM3StaticAnalysisService(original);
        var read = service.read(session, identity).orElseThrow();
        assertEquals(2, read.size());
        assertEquals(M3StaticAnalysisService.Status.COMPLETE, read.status());
        assertEquals(identity, read.receipt().identity());
        assertEquals("A.Rule", read.entry(0).ruleId());
        assertEquals("src/main/java/B.java", read.entry(1).relativePath());
        assertEquals(12, read.entry(1).line());
        assertThrows(IndexOutOfBoundsException.class, () -> read.entry(2));

        Files.writeString(file, "modified after authenticated snapshot");
        assertEquals(2, read.size(), "published read remains a stable parsed snapshot");
        assertThrows(IOException.class, () -> service.read(session, identity));
        original.revoke(session, identity);
        assertTrue(service.read(session, identity).isEmpty(), "revocation never deletes producer bytes");
    }

    @Test
    void missingPartialAndFailedRunsNeverBecomeSuccessfulCoverage() throws Exception {
        var provider = new DefaultM3PluginEvidenceService();
        var service = new DefaultM3StaticAnalysisService(provider);
        Session session = session();
        var checkstyle = identity("maven-checkstyle-plugin", 1);
        var pmd = identity("maven-pmd-plugin", 2);
        var spotbugs = identity("spotbugs-maven-plugin", 3);
        publish(provider, session, checkstyle,
                HEADER + row("A", "INFO", "src/A.java", "1", "1", MESSAGE));
        publish(provider, session, pmd,
                HEADER.replace("status\tCOMPLETE", "status\tPARTIAL")
                        + row("B", "WARNING", "src/B.java", "2", "0", MESSAGE));

        var report = service.aggregate(session, List.of(spotbugs, pmd, checkstyle));
        assertFalse(report.complete());
        assertEquals(3, report.coverage().size());
        assertEquals(2, report.findings().size());
        assertEquals(M3StaticAnalysisService.Status.MISSING, report.coverage().get(0).status());
        assertEquals(M3StaticAnalysisService.Status.COMPLETE, report.coverage().get(1).status());
        assertEquals(M3StaticAnalysisService.Status.PARTIAL, report.coverage().get(2).status());
        assertEquals(checkstyle, report.findings().get(0).identity());
        assertThrows(UnsupportedOperationException.class, () -> report.findings().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.coverage().clear());

        publish(provider, session, spotbugs, HEADER.replace("status\tCOMPLETE", "status\tFAILED"));
        assertEquals(M3StaticAnalysisService.Status.FAILED,
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
    void duplicateOrExcessiveExpectedAnalyzersAreRejectedBeforeLookups() {
        M3PluginEvidenceService evidence = mock(M3PluginEvidenceService.class);
        var service = new DefaultM3StaticAnalysisService(evidence);
        Session session = mock(Session.class);
        var pmd = identity("maven-pmd-plugin", 1);
        assertThrows(IllegalArgumentException.class, () -> service.aggregate(session, List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.aggregate(session, List.of(pmd, pmd)));
        assertThrows(IllegalArgumentException.class, () -> service.aggregate(
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
        assertThrows(IllegalArgumentException.class,
                () -> new M3StaticAnalysisService.Finding("Rule", level, "../outside", 1, 1, "bad"));
        assertThrows(IllegalArgumentException.class,
                () -> new M3StaticAnalysisService.Finding("Rule", level, "C:/absolute", 1, 1, "bad"));
        assertThrows(IllegalArgumentException.class,
                () -> new M3StaticAnalysisService.Finding("Rule", level, "a\\b", 1, 1, "bad"));
        assertThrows(IllegalArgumentException.class,
                () -> new M3StaticAnalysisService.Finding("Rule", level, "", 1, 1, "bad"));
        assertThrows(IllegalArgumentException.class,
                () -> new M3StaticAnalysisService.Finding("Rule", level, "A.java", 0, 1, "bad"));
        assertThrows(IllegalArgumentException.class,
                () -> new M3StaticAnalysisService.Finding("Rule", level,
                        String.valueOf((char) 0xD800), 0, 0, "bad"));
        assertEquals("", new M3StaticAnalysisService.Finding("Rule", level, "", 0, 0, "project")
                .relativePath());
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
        provider.publish(session, new M3PluginEvidenceService.Receipt(identity, file,
                M3StaticAnalysisService.SCHEMA, sha(bytes), M3StaticAnalysisService.CAPABILITIES));
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
                identity, path, M3StaticAnalysisService.SCHEMA, sha(Files.readAllBytes(path)),
                M3PluginEvidenceService.INPUTS);
        provider.publish(session, receipt);
        var consumer = new DefaultM3StaticAnalysisService(provider);
        assertFalse(consumer.read(session, identity).isPresent());
        assertFalse(consumer.aggregate(session, List.of(identity)).complete());
    }

    private Path publish(M3PluginEvidenceService evidence, Session session,
            M3PluginEvidenceService.Identity identity, String content) throws Exception {
        Path path = Files.createTempFile(directory, "original-analysis-", ".tsv");
        Files.writeString(path, content, StandardCharsets.UTF_8);
        var receipt = new M3PluginEvidenceService.Receipt(
                identity, path, M3StaticAnalysisService.SCHEMA, sha(Files.readAllBytes(path)),
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

    private static String row(
            String rule, String severity, String path, String line, String column, String message64) {
        return rule + "\t" + severity + "\t" + path + "\t" + line + "\t" + column + "\t" + message64 + "\n";
    }

    private static M3PluginEvidenceService.Identity identity(String plugin, long generation) {
        String ga = "spotbugs-maven-plugin".equals(plugin)
                ? "com.github.spotbugs:" + plugin : "org.apache.maven.plugins:" + plugin;
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
