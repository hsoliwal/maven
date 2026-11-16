/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.apache.maven.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import org.apache.maven.api.Session;
import org.apache.maven.api.services.BuildInputsService;
import org.apache.maven.api.services.M3PluginEvidenceService;
import org.apache.maven.di.Injector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tests original evidence authentication and strict, read-only resource inventory parsing. */
final class DefaultBuildInputsServiceTest {
    private static final String SHA = "a".repeat(64);
    private static final String HEADER = "M3-RESOURCE-IO-V1\nrole\tpath\tsha256\tbytes\n";

    @TempDir
    Path directory;

    @Test
    void injectsNativeServiceWithoutAnotherGoalOrFileScanner() {
        Injector injector = Injector.create();
        try {
            injector.bindInstance(M3PluginEvidenceService.class, mock(M3PluginEvidenceService.class));
            injector.bindImplicit(DefaultBuildInputsService.class);
            BuildInputsService service = injector.getInstance(BuildInputsService.class);
            assertInstanceOf(DefaultBuildInputsService.class, service);
            assertSame(service, injector.getInstance(BuildInputsService.class));
        } finally {
            injector.dispose();
        }
    }

    @Test
    void immutableInputOutputIndexPreservesOriginalAuthenticatedReceipt() throws Exception {
        String content = HEADER + "I\tsrc/main/resources/a.txt\t" + SHA + "\t0\n"
                + "I\tsrc/main/resources/é b.txt\t" + SHA + "\t11\n"
                + "O\ttarget/classes/a.txt\t" + SHA + "\t4\n";
        Fixture f = fixture(content);
        BuildInputsService.Manifest manifest = f.read().orElseThrow();
        assertEquals(3, manifest.size());
        assertEquals(2, manifest.inputCount());
        assertEquals(1, manifest.outputCount());
        assertEquals(f.receipt(), manifest.receipt());
        assertEquals(BuildInputsService.Direction.INPUT, manifest.entry(0).direction());
        assertEquals("src/main/resources/é b.txt", manifest.entry(1).relativePath());
        assertEquals(11, manifest.entry(1).bytes());
        assertEquals(BuildInputsService.Direction.OUTPUT, manifest.entry(2).direction());
        assertThrows(IndexOutOfBoundsException.class, () -> manifest.entry(3));
        Files.writeString(f.file(), "modified after snapshot");
        assertEquals(3, manifest.size());
        assertThrows(IOException.class, () -> f.read());
    }

    @Test
    void missingReceiptNeverInfersAnInventoryOrCachePermission() throws Exception {
        M3PluginEvidenceService evidence = mock(M3PluginEvidenceService.class);
        Session session = mock(Session.class);
        M3PluginEvidenceService.Identity id = identity();
        var service = new DefaultBuildInputsService(evidence);
        assertFalse(service.read(session, id).isPresent());
        assertThrows(NullPointerException.class, () -> service.read(session, null));
    }

    @Test
    void rejectsUnsafeUnsortedDuplicateOrUnverifiableData() throws Exception {
        String[] invalid = {
            "\ufeff" + HEADER,
            "M3-RESOURCE-IO-V2\nrole\tpath\tsha256\tbytes\n",
            HEADER + "I\t../escape.txt\t" + SHA + "\t1\n",
            HEADER + "I\tC:/escape.txt\t" + SHA + "\t1\n",
            HEADER + "I\ta\\b.txt\t" + SHA + "\t1\n",
            HEADER + "I\tsrc/./a.txt\t" + SHA + "\t1\n",
            HEADER + "I\ta.txt\t" + SHA.toUpperCase() + "\t1\n",
            HEADER + "I\ta.txt\t" + SHA + "\t01\n",
            HEADER + "I\ta.txt\t" + SHA + "\t9223372036854775808\n",
            HEADER + "I\tb.txt\t" + SHA + "\t1\nI\ta.txt\t" + SHA + "\t1\n",
            HEADER + "O\ta.txt\t" + SHA + "\t1\nI\tb.txt\t" + SHA + "\t1\n",
            HEADER + "I\ta.txt\t" + SHA + "\t1\nI\ta.txt\t" + SHA + "\t1\n",
            HEADER + "I\ta.txt\t" + SHA + "\t1", // no final LF
            HEADER + "I\ta.txt\t" + SHA + "\t1\r\n"
        };
        for (int i = 0; i < invalid.length; i++) {
            Fixture f = fixture(invalid[i], "invalid-" + i);
            assertThrows(IOException.class, f::read, "case " + i);
        }
    }

    @Test
    void emptyManifestIsParseableButDoesNotClaimCoverage() throws Exception {
        var f = fixture(HEADER);
        var manifest = f.read().orElseThrow();
        assertEquals(0, manifest.size());
        assertEquals(0, manifest.inputCount());
        assertEquals(0, manifest.outputCount());
    }

    private Fixture fixture(String text) throws Exception {
        return fixture(text, "resource");
    }

    private Fixture fixture(String text, String fileName) throws Exception {
        Path file = directory.resolve(fileName + ".tsv");
        Files.writeString(file, text);
        var receipt = new M3PluginEvidenceService.Receipt(identity(), file, BuildInputsService.SCHEMA,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))),
                BuildInputsService.CAPABILITIES);
        M3PluginEvidenceService evidence = mock(M3PluginEvidenceService.class);
        Session session = mock(Session.class);
        when(evidence.read(eq(session), eq(identity()), eq(BuildInputsService.SCHEMA),
                eq(BuildInputsService.CAPABILITIES)))
                .thenAnswer(ignored -> Optional.of(receipt.snapshot()));
        return new Fixture(file, receipt, new DefaultBuildInputsService(evidence), session);
    }

    private static M3PluginEvidenceService.Identity identity() {
        return new M3PluginEvidenceService.Identity("project", "org.apache.maven.plugins:maven-resources-plugin",
                "3.4.0", "resources", "default-resources", "b".repeat(64), 1);
    }

    private record Fixture(Path file, M3PluginEvidenceService.Receipt receipt, BuildInputsService service,
            Session session) {
        Optional<BuildInputsService.Manifest> read() throws IOException {
            return service.read(session, identity());
        }
    }
}
