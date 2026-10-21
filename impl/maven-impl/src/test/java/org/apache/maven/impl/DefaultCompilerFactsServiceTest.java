/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this work.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.maven.impl;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.apache.maven.api.Session;
import org.apache.maven.api.SessionData;
import org.apache.maven.api.services.CompilerFactsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** No source parser or compilation task is created by this Maven API test. */
class DefaultCompilerFactsServiceTest {
    @TempDir Path temporary;

    @Test void exactCompilerGenerationAndSchemaAreRequired() throws Exception {
        var service = new DefaultCompilerFactsService();
        Session session = session();
        Path image = temporary.resolve("facts.m3af");
        Files.write(image, new byte[] {1, 2, 3, 4});
        var source = key("b".repeat(64), "jdk21");
        var facts = new CompilerFactsService.Image(source, image, "m3jdk21.javac.geometry.v1",
                sha(Files.readAllBytes(image)), CompilerFactsService.SYNTAX | CompilerFactsService.PARENTS);
        assertTrue(service.find(session, source, facts.schema(), CompilerFactsService.SYNTAX).isEmpty());
        service.publish(session, facts);
        service.publish(session, facts);
        assertTrue(service.find(session, source, facts.schema(), CompilerFactsService.PARENTS).isPresent());
        assertTrue(service.find(session, source, "other.schema", CompilerFactsService.SYNTAX).isEmpty());
        assertTrue(service.find(session, source, facts.schema(), CompilerFactsService.ATTRIBUTION).isEmpty());
        assertTrue(service.find(session, key("c".repeat(64), "jdk21"),
                facts.schema(), CompilerFactsService.SYNTAX).isEmpty());
        assertTrue(service.find(session, key("b".repeat(64), "other-toolchain"),
                facts.schema(), CompilerFactsService.SYNTAX).isEmpty());
        assertTrue(service.find(session(), source, facts.schema(), CompilerFactsService.SYNTAX).isEmpty());
        service.revoke(session, source);
        assertFalse(service.find(session, source, facts.schema(), CompilerFactsService.SYNTAX).isPresent());
    }

    @Test void changedAndSymlinkedImagesFailClosed() throws Exception {
        var service = new DefaultCompilerFactsService();
        Session session = session();
        Path image = temporary.resolve("facts.m3af");
        Files.write(image, new byte[] {1, 2, 3, 4});
        var source = key("b".repeat(64), "jdk21");
        var facts = new CompilerFactsService.Image(source, image, "m3jdk21.javac.geometry.v1",
                sha(Files.readAllBytes(image)), CompilerFactsService.SYNTAX);
        service.publish(session, facts);
        Files.write(image, new byte[] {1, 2, 3, 9});
        assertThrows(IOException.class,
                () -> service.find(session, source, facts.schema(), CompilerFactsService.SYNTAX));
        assertThrows(IOException.class, () -> service.publish(session, facts));
        Path alias = temporary.resolve("alias.m3af");
        Files.createSymbolicLink(alias, image);
        assertThrows(IOException.class, () -> service.publish(session,
                new CompilerFactsService.Image(key("c".repeat(64), "jdk21"), alias,
                        facts.schema(), facts.imageSha256(), CompilerFactsService.SYNTAX)));
    }

    @Test void sourceContentIdentityIsNotProofOfAttribution() {
        var source = key("b".repeat(64), "jdk21");
        assertThrows(IllegalArgumentException.class, () -> new CompilerFactsService.Image(
                source, temporary.resolve("any"), "unclaimed", "f".repeat(64),
                CompilerFactsService.KNOWN_CAPABILITIES << 1));
        assertThrows(IllegalArgumentException.class, () -> key("invalid", "jdk21"));
        assertEquals(64, source.sourceSha256().length());
    }

    private static CompilerFactsService.Identity key(String context, String compiler) {
        return new CompilerFactsService.Identity("g:a", URI.create("file:///project/src/Demo.java"),
                "a".repeat(64), context, compiler);
    }
    private static String sha(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Session session() {
        Session result = mock(Session.class);
        when(result.getData()).thenReturn(new Memory());
        return result;
    }

    private static final class Memory implements SessionData {
        private final Map<Key<?>, Object> entries = new ConcurrentHashMap<>();
        @Override public synchronized <T> void set(Key<T> key, T value) {
            if (value == null) entries.remove(key); else entries.put(key, value);
        }
        @Override public synchronized <T> boolean replace(Key<T> key, T before, T after) {
            if (entries.get(key) != before) return false;
            set(key, after);
            return true;
        }
        @Override @SuppressWarnings("unchecked")
        public <T> T get(Key<T> key) { return (T) entries.get(key); }
        @Override @SuppressWarnings("unchecked")
        public <T> T computeIfAbsent(Key<T> key, Supplier<T> supplier) {
            return (T) entries.computeIfAbsent(key, unused -> supplier.get());
        }
    }
}
