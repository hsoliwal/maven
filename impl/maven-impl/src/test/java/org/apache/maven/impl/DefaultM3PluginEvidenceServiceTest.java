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
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.apache.maven.api.Session;
import org.apache.maven.api.SessionData;
import org.apache.maven.api.services.M3PluginEvidenceService;
import org.apache.maven.di.Injector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Original Maven 4 session/DI test, without inventing a new Mojo or source parser. */
final class DefaultM3PluginEvidenceServiceTest {
    @TempDir
    Path temporary;

    @Test
    void mavenInjectorExposesOneNativeSessionService() {
        Injector injector = Injector.create();
        try {
            injector.bindImplicit(DefaultM3PluginEvidenceService.class);
            M3PluginEvidenceService service = injector.getInstance(M3PluginEvidenceService.class);
            assertInstanceOf(DefaultM3PluginEvidenceService.class, service);
            assertSame(service, injector.getInstance(M3PluginEvidenceService.class));
        } finally {
            injector.dispose();
        }
    }

    @Test
    void sourceBoundReadOnlyFacetsNeverImplyGoalReuseOrCrossSessionState() throws Exception {
        Path image = temporary.resolve("resources.receipt");
        Files.write(image, new byte[] {1, 2, 3});
        var service = new DefaultM3PluginEvidenceService();
        Session original = session(), differentSession = session();
        var source = identity("maven-resources-plugin", "resources", 1);
        var receipt = new M3PluginEvidenceService.Receipt(
                source,
                image,
                "m3.resources.manifest.v1",
                sha(image),
                M3PluginEvidenceService.INPUTS | M3PluginEvidenceService.RESOURCES | M3PluginEvidenceService.OUTPUTS);
        assertTrue(service.find(original, source, receipt.schema(), M3PluginEvidenceService.RESOURCES)
                .isEmpty());
        service.publish(original, receipt);
        service.publish(original, receipt);
        assertEquals(
                receipt,
                service.find(original, source, receipt.schema(), M3PluginEvidenceService.RESOURCES)
                        .orElseThrow());
        assertTrue(service.find(original, source, receipt.schema(), M3PluginEvidenceService.ARCHIVE)
                .isEmpty());
        assertTrue(service.find(original, source, "wrong.schema", M3PluginEvidenceService.INPUTS)
                .isEmpty());
        assertTrue(service.find(
                        original,
                        identity("maven-resources-plugin", "resources", 2),
                        receipt.schema(),
                        M3PluginEvidenceService.INPUTS)
                .isEmpty());
        assertTrue(service.find(differentSession, source, receipt.schema(), M3PluginEvidenceService.INPUTS)
                .isEmpty());
        service.revoke(original, source);
        assertFalse(service.find(original, source, receipt.schema(), M3PluginEvidenceService.INPUTS)
                .isPresent());
    }

    @Test
    void changedContentSymlinkAndConflictingGenerationRefuse() throws Exception {
        Path image = temporary.resolve("jar.receipt");
        Files.write(image, new byte[] {1, 2, 3});
        var service = new DefaultM3PluginEvidenceService();
        Session session = session();
        var id = identity("maven-jar-plugin", "jar", 1);
        var receipt = new M3PluginEvidenceService.Receipt(
                id, image, "m3.archive.manifest.v1", sha(image), M3PluginEvidenceService.ARCHIVE);
        service.publish(session, receipt);
        assertThrows(
                IOException.class,
                () -> service.publish(
                        session,
                        new M3PluginEvidenceService.Receipt(
                                id, image, "other.schema", receipt.sha256(), M3PluginEvidenceService.ARCHIVE)));
        Files.write(image, new byte[] {1, 2, 9});
        assertThrows(
                IOException.class, () -> service.find(session, id, receipt.schema(), M3PluginEvidenceService.ARCHIVE));
        Path link = temporary.resolve("alias.receipt");
        Files.createSymbolicLink(link, image);
        assertThrows(
                IOException.class,
                () -> service.publish(
                        session,
                        new M3PluginEvidenceService.Receipt(
                                identity("maven-jar-plugin", "jar", 2),
                                link,
                                receipt.schema(),
                                receipt.sha256(),
                                M3PluginEvidenceService.ARCHIVE)));
    }

    @Test
    void boundedSortedSlotsRefuseBeforeReadingA513thSourceAndRecoverOnRevoke() throws Exception {
        Path image = temporary.resolve("test.receipt");
        Files.write(image, new byte[] {8, 9});
        String hash = sha(image);
        var service = new DefaultM3PluginEvidenceService();
        Session session = session();
        for (int index = 1; index <= 512; index++) {
            service.publish(
                    session,
                    new M3PluginEvidenceService.Receipt(
                            identity("maven-surefire-plugin", "test", index),
                            image,
                            "m3.tests.results.v1",
                            hash,
                            M3PluginEvidenceService.TESTS));
        }
        var next = identity("maven-surefire-plugin", "test", 513);
        assertThrows(
                IOException.class,
                () -> service.publish(
                        session,
                        new M3PluginEvidenceService.Receipt(
                                next,
                                temporary.resolve("missing.receipt"),
                                "m3.tests.results.v1",
                                hash,
                                M3PluginEvidenceService.TESTS)));
        assertTrue(service.find(
                        session,
                        identity("maven-surefire-plugin", "test", 1),
                        "m3.tests.results.v1",
                        M3PluginEvidenceService.TESTS)
                .isPresent());
        service.revoke(session, identity("maven-surefire-plugin", "test", 1));
        service.publish(
                session,
                new M3PluginEvidenceService.Receipt(
                        next, image, "m3.tests.results.v1", hash, M3PluginEvidenceService.TESTS));
        assertTrue(service.find(session, next, "m3.tests.results.v1", M3PluginEvidenceService.TESTS)
                .isPresent());
    }

    @Test
    void snapshotsOwnTheAuthenticatedBytesAcrossFileAndCallerChanges() throws Exception {
        Path image = temporary.resolve("snapshot.receipt");
        byte[] expected = new byte[16_387];
        Arrays.fill(expected, (byte) 7);
        expected[expected.length - 1] = 9;
        Files.write(image, expected);
        var receipt = receipt(image);
        var service = new DefaultM3PluginEvidenceService();
        Session session = session();
        service.publish(session, receipt);

        var snapshot = service.read(session, receipt.identity(), receipt.schema(), receipt.capabilities())
                .orElseThrow();
        assertSame(receipt, snapshot.receipt());
        assertArrayEquals(expected, snapshot.bytes());
        byte[] exposed = snapshot.bytes();
        exposed[0] = 0;
        assertArrayEquals(expected, snapshot.bytes());

        Files.write(image, new byte[] {1, 2, 3});
        assertArrayEquals(expected, snapshot.bytes());
        assertThrows(
                IOException.class,
                () -> service.read(session, receipt.identity(), receipt.schema(), receipt.capabilities()));
        service.revoke(session, receipt.identity());
        assertTrue(service.read(session, receipt.identity(), receipt.schema(), receipt.capabilities())
                .isEmpty());
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(image));
        assertArrayEquals(expected, snapshot.bytes());
    }

    @Test
    void snapshotLookupDoesNotReadUnmatchedOrCrossSessionFiles() throws Exception {
        Path image = temporary.resolve("matches.receipt");
        Files.write(image, new byte[] {1, 2, 3});
        var receipt = receipt(image);
        var service = new DefaultM3PluginEvidenceService();
        Session session = session();
        service.publish(session, receipt);
        Files.delete(image);

        assertTrue(service.read(session(), receipt.identity(), receipt.schema(), receipt.capabilities())
                .isEmpty());
        assertTrue(service.read(session, receipt.identity(), "different.schema", receipt.capabilities())
                .isEmpty());
        assertTrue(service.read(session, receipt.identity(), receipt.schema(), M3PluginEvidenceService.ARCHIVE)
                .isEmpty());
        assertTrue(service.read(
                        session, identity("maven-surefire-plugin", "test", 2), receipt.schema(), receipt.capabilities())
                .isEmpty());
        assertThrows(
                IOException.class,
                () -> service.read(session, receipt.identity(), receipt.schema(), receipt.capabilities()));
    }

    @Test
    void snapshotReadRejectsSameLengthChangesAndSymlinkSubstitution() throws Exception {
        Path image = temporary.resolve("changed.receipt");
        Files.write(image, new byte[] {1, 2, 3});
        var receipt = receipt(image);
        var service = new DefaultM3PluginEvidenceService();
        Session session = session();
        service.publish(session, receipt);
        Files.write(image, new byte[] {1, 2, 9});
        assertThrows(
                IOException.class,
                () -> service.read(session, receipt.identity(), receipt.schema(), receipt.capabilities()));

        Path original = temporary.resolve("original.receipt");
        Files.write(original, new byte[] {1, 2, 3});
        Files.delete(image);
        Files.createSymbolicLink(image, original);
        assertThrows(
                IOException.class,
                () -> service.read(session, receipt.identity(), receipt.schema(), receipt.capabilities()));
        assertThrows(IOException.class, receipt::verify);
        assertThrows(IOException.class, receipt::snapshot);
    }

    @Test
    void verificationAndSnapshotsShareFileTypeAndSizeLimits() throws Exception {
        Path image = temporary.resolve("bounded.receipt");
        Files.write(image, new byte[] {1});
        var receipt = receipt(image);
        var service = new DefaultM3PluginEvidenceService();
        Session session = session();
        service.publish(session, receipt);
        try (SeekableByteChannel output = Files.newByteChannel(image, StandardOpenOption.WRITE)) {
            output.position(M3PluginEvidenceService.MAX_RECEIPT_BYTES);
            output.write(ByteBuffer.wrap(new byte[] {0}));
        }
        assertThrows(IOException.class, receipt::verify);
        assertThrows(IOException.class, receipt::snapshot);
        assertThrows(
                IOException.class,
                () -> service.read(session, receipt.identity(), receipt.schema(), receipt.capabilities()));

        Files.delete(image);
        Files.createDirectory(image);
        assertThrows(IOException.class, receipt::verify);
        assertThrows(IOException.class, receipt::snapshot);
        assertThrows(
                IOException.class,
                () -> service.read(session, receipt.identity(), receipt.schema(), receipt.capabilities()));
    }

    @Test
    void legacyImplementationUsesTheDefaultReadWithoutImplementingANewMethod() throws Exception {
        Path image = temporary.resolve("legacy.receipt");
        Files.write(image, new byte[] {4, 5, 6});
        var receipt = receipt(image);
        M3PluginEvidenceService legacy = legacyService(() -> Optional.of(receipt));
        var snapshot = legacy.read(session(), receipt.identity(), receipt.schema(), receipt.capabilities())
                .orElseThrow();
        assertSame(receipt, snapshot.receipt());
        assertArrayEquals(new byte[] {4, 5, 6}, snapshot.bytes());
        assertTrue(legacyService(Optional::empty)
                .read(session(), receipt.identity(), receipt.schema(), receipt.capabilities())
                .isEmpty());
    }

    @Test
    void legacyFallbackAuthenticatesBytesAgainAfterFindReturns() throws Exception {
        Path image = temporary.resolve("legacy-changed.receipt");
        Files.write(image, new byte[] {4, 5, 6});
        var receipt = receipt(image);
        M3PluginEvidenceService legacy = legacyService(() -> {
            receipt.verify();
            Files.write(image, new byte[] {4, 5, 9});
            return Optional.of(receipt);
        });
        assertThrows(
                IOException.class,
                () -> legacy.read(session(), receipt.identity(), receipt.schema(), receipt.capabilities()));
    }

    @Test
    void emptyReceiptSnapshotsAreValidAndReadArgumentsAreCheckedBeforeLookup() throws Exception {
        Path image = temporary.resolve("empty.receipt");
        Files.write(image, new byte[0]);
        var receipt = receipt(image);
        receipt.verify();
        assertArrayEquals(new byte[0], receipt.snapshot().bytes());
        var service = new DefaultM3PluginEvidenceService();
        Session session = session();
        service.publish(session, receipt);
        assertArrayEquals(
                new byte[0],
                service.read(session, receipt.identity(), receipt.schema(), receipt.capabilities())
                        .orElseThrow()
                        .bytes());
        assertThrows(
                NullPointerException.class,
                () -> service.read(null, receipt.identity(), receipt.schema(), receipt.capabilities()));
        assertThrows(
                NullPointerException.class,
                () -> service.read(session, null, receipt.schema(), receipt.capabilities()));
        assertThrows(
                NullPointerException.class,
                () -> service.read(session, receipt.identity(), null, receipt.capabilities()));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.read(session, receipt.identity(), " ", receipt.capabilities()));
        assertThrows(
                IllegalArgumentException.class, () -> service.read(session, receipt.identity(), receipt.schema(), 0));
        assertThrows(
                IllegalArgumentException.class, () -> service.read(session, receipt.identity(), receipt.schema(), 128));
    }

    private static M3PluginEvidenceService.Receipt receipt(Path image) throws Exception {
        return new M3PluginEvidenceService.Receipt(
                identity("maven-surefire-plugin", "test", 1),
                image,
                "m3.tests.results.v1",
                sha(image),
                M3PluginEvidenceService.TESTS);
    }

    private static M3PluginEvidenceService legacyService(ReceiptLookup lookup) {
        return new M3PluginEvidenceService() {
            @Override
            public void publish(Session session, Receipt receipt) {
                throw new UnsupportedOperationException("read must not publish");
            }

            @Override
            public Optional<Receipt> find(Session session, Identity identity, String schema, int capabilities)
                    throws IOException {
                return lookup.find();
            }

            @Override
            public void revoke(Session session, Identity identity) {
                throw new UnsupportedOperationException("read must not revoke");
            }
        };
    }

    @FunctionalInterface
    private interface ReceiptLookup {
        Optional<M3PluginEvidenceService.Receipt> find() throws IOException;
    }

    private static M3PluginEvidenceService.Identity identity(String plugin, String goal, long generation) {
        return new M3PluginEvidenceService.Identity(
                "project", "org.apache.maven.plugins:" + plugin, "4.0.0", goal, "default", "a".repeat(64), generation);
    }

    private static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static Session session() {
        Session session = mock(Session.class);
        when(session.getData()).thenReturn(new Memory());
        return session;
    }

    private static final class Memory implements SessionData {
        private final Map<Key<?>, Object> state = new ConcurrentHashMap<>();

        @Override
        public synchronized <T> void set(Key<T> key, T value) {
            if (value == null) {
                state.remove(key);
            } else {
                state.put(key, value);
            }
        }

        @Override
        public synchronized <T> boolean replace(Key<T> key, T previous, T next) {
            if (state.get(key) != previous) {
                return false;
            }
            set(key, next);
            return true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Key<T> key) {
            return (T) state.get(key);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T computeIfAbsent(Key<T> key, Supplier<T> supply) {
            return (T) state.computeIfAbsent(key, ignored -> supply.get());
        }
    }
}
