/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.maven.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.apache.maven.api.Session;
import org.apache.maven.api.SessionData;
import org.apache.maven.api.services.M3PluginEvidenceService;
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
import static org.mockito.Mockito.when;

/** Original Maven 4 session/DI test, without inventing a new Mojo or source parser. */
final class DefaultM3PluginEvidenceServiceTest {
    @TempDir Path temporary;

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
        var receipt = new M3PluginEvidenceService.Receipt(source, image, "m3.resources.manifest.v1",
                sha(image), M3PluginEvidenceService.INPUTS | M3PluginEvidenceService.RESOURCES
                        | M3PluginEvidenceService.OUTPUTS);
        assertTrue(service.find(original, source, receipt.schema(), M3PluginEvidenceService.RESOURCES).isEmpty());
        service.publish(original, receipt);
        service.publish(original, receipt);
        assertEquals(receipt,
                service.find(original, source, receipt.schema(), M3PluginEvidenceService.RESOURCES).orElseThrow());
        assertTrue(service.find(original, source, receipt.schema(), M3PluginEvidenceService.ARCHIVE).isEmpty());
        assertTrue(service.find(original, source, "wrong.schema", M3PluginEvidenceService.INPUTS).isEmpty());
        assertTrue(service.find(original, identity("maven-resources-plugin", "resources", 2), receipt.schema(),
                M3PluginEvidenceService.INPUTS).isEmpty());
        assertTrue(service.find(differentSession, source, receipt.schema(), M3PluginEvidenceService.INPUTS).isEmpty());
        service.revoke(original, source);
        assertFalse(service.find(original, source, receipt.schema(), M3PluginEvidenceService.INPUTS).isPresent());
    }

    @Test
    void changedContentSymlinkAndConflictingGenerationRefuse() throws Exception {
        Path image = temporary.resolve("jar.receipt");
        Files.write(image, new byte[] {1, 2, 3});
        var service = new DefaultM3PluginEvidenceService();
        Session session = session();
        var id = identity("maven-jar-plugin", "jar", 1);
        var receipt = new M3PluginEvidenceService.Receipt(id, image, "m3.archive.manifest.v1", sha(image),
                M3PluginEvidenceService.ARCHIVE);
        service.publish(session, receipt);
        assertThrows(IOException.class, () -> service.publish(session,
                new M3PluginEvidenceService.Receipt(id, image, "other.schema", receipt.sha256(),
                        M3PluginEvidenceService.ARCHIVE)));
        Files.write(image, new byte[] {1, 2, 9});
        assertThrows(IOException.class, () -> service.find(session, id, receipt.schema(), M3PluginEvidenceService.ARCHIVE));
        Path link = temporary.resolve("alias.receipt");
        Files.createSymbolicLink(link, image);
        assertThrows(IOException.class, () -> service.publish(session,
                new M3PluginEvidenceService.Receipt(identity("maven-jar-plugin", "jar", 2), link,
                        receipt.schema(), receipt.sha256(), M3PluginEvidenceService.ARCHIVE)));
    }

    @Test
    void boundedSortedSlotsRefuseBeforeReadingA513thSourceAndRecoverOnRevoke() throws Exception {
        Path image = temporary.resolve("test.receipt");
        Files.write(image, new byte[] {8, 9});
        String hash = sha(image);
        var service = new DefaultM3PluginEvidenceService();
        Session session = session();
        for (int index = 1; index <= 512; index++) {
            service.publish(session, new M3PluginEvidenceService.Receipt(
                    identity("maven-surefire-plugin", "test", index), image, "m3.tests.results.v1", hash,
                    M3PluginEvidenceService.TESTS));
        }
        var next = identity("maven-surefire-plugin", "test", 513);
        assertThrows(IOException.class, () -> service.publish(session,
                new M3PluginEvidenceService.Receipt(next, temporary.resolve("missing.receipt"),
                        "m3.tests.results.v1", hash, M3PluginEvidenceService.TESTS)));
        assertTrue(service.find(session, identity("maven-surefire-plugin", "test", 1),
                "m3.tests.results.v1", M3PluginEvidenceService.TESTS).isPresent());
        service.revoke(session, identity("maven-surefire-plugin", "test", 1));
        service.publish(session, new M3PluginEvidenceService.Receipt(next, image,
                "m3.tests.results.v1", hash, M3PluginEvidenceService.TESTS));
        assertTrue(service.find(session, next, "m3.tests.results.v1", M3PluginEvidenceService.TESTS)
                .isPresent());
    }

    private static M3PluginEvidenceService.Identity identity(String plugin, String goal, long generation) {
        return new M3PluginEvidenceService.Identity("project", "org.apache.maven.plugins:" + plugin,
                "4.0.0", goal, "default", "a".repeat(64), generation);
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
        @Override public synchronized <T> void set(Key<T> key, T value) {
            if (value == null) state.remove(key);
            else state.put(key, value);
        }
        @Override public synchronized <T> boolean replace(Key<T> key, T previous, T next) {
            if (state.get(key) != previous) return false;
            set(key, next);
            return true;
        }
        @Override @SuppressWarnings("unchecked") public <T> T get(Key<T> key) {
            return (T) state.get(key);
        }
        @Override @SuppressWarnings("unchecked") public <T> T computeIfAbsent(Key<T> key, Supplier<T> supply) {
            return (T) state.computeIfAbsent(key, ignored -> supply.get());
        }
    }
}
