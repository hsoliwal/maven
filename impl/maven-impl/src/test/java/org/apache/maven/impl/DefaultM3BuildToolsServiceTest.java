/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this work.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.maven.impl;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.spi.ToolProvider;
import org.apache.maven.api.Session;
import org.apache.maven.api.SessionData;
import org.apache.maven.api.services.M3BuildToolsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises the real Maven API service contract, not a second classfile parser. */
class DefaultM3BuildToolsServiceTest {
    @TempDir Path temp;

    @Test void sessionCacheReusesFactsAndVerifiesActualChangedBytes() throws Exception {
        Path clazz = temp.resolve("Fixture.class");
        Files.write(clazz, new byte[]{1, 2, 3, 4});
        Path runtime = Path.of(System.getProperty("java.home"));
        NativeProvider provider = new NativeProvider();
        var service = new DefaultM3BuildToolsService(() -> Optional.of(provider), () -> runtime);
        Session first = session(), another = session();
        var request = new M3BuildToolsService.Request(runtime, clazz, false);
        var api = new M3BuildToolsService.Request(runtime, clazz, true);
        var facts = service.dependencies(first, request).orElseThrow();
        assertEquals("CLASS", facts.scope());
        assertEquals(1, provider.invocations);
        assertEquals(facts, service.dependencies(first, request).orElseThrow());
        assertEquals(1, provider.invocations, "second plugin reuses the same generation");
        assertEquals("PUBLIC_API", service.dependencies(first, api).orElseThrow().scope());
        assertEquals(2, provider.invocations);
        assertTrue(service.dependencies(another, request).isPresent());
        assertEquals(3, provider.invocations, "different session must not share unverified facts");
        var stamp = Files.getLastModifiedTime(clazz);
        Files.write(clazz, new byte[]{4, 3, 2, 1});
        Files.setLastModifiedTime(clazz, stamp);
        assertTrue(service.dependencies(first, request).isPresent());
        assertEquals(4, provider.invocations, "restored timestamp cannot authorize unchanged class");
    }

    @Test void wrongToolchainSymlinkAndMissingProviderFailClosed() throws Exception {
        Path clazz = temp.resolve("Source.class"); Files.write(clazz, new byte[]{9, 9});
        Path runtime = Path.of(System.getProperty("java.home"));
        NativeProvider provider = new NativeProvider();
        var service = new DefaultM3BuildToolsService(() -> Optional.of(provider), () -> runtime);
        Session session = session();
        Path otherJdk = temp.resolve("other-jdk"); Files.createDirectory(otherJdk);
        assertTrue(service.dependencies(session, new M3BuildToolsService.Request(otherJdk, clazz, false)).isEmpty());
        assertEquals(0, provider.invocations);
        assertTrue(new DefaultM3BuildToolsService(Optional::<ToolProvider>empty, () -> runtime)
                .dependencies(session, new M3BuildToolsService.Request(runtime, clazz, false)).isEmpty());
        Path link = temp.resolve("Alias.class"); Files.createSymbolicLink(link, clazz);
        assertThrows(IOException.class, () -> service.dependencies(session,
                new M3BuildToolsService.Request(runtime, link, false)));
        assertEquals(0, provider.invocations, "input admission must precede native scan");
        var badProvider = new DefaultM3BuildToolsService(() -> Optional.of(new ToolProvider() {
            @Override public String name() { return "not-the-sdk"; }
            @Override public int run(PrintWriter out, PrintWriter err, String... args) { return 0; }
        }), () -> runtime);
        assertThrows(IOException.class, () -> badProvider.dependencies(session,
                new M3BuildToolsService.Request(runtime, clazz, false)));
    }

    private static Session session() {
        Session session = mock(Session.class);
        when(session.getData()).thenReturn(new Memory());
        return session;
    }

    private static final class Memory implements SessionData {
        private final Map<Key<?>, Object> values = new ConcurrentHashMap<>();
        @Override public synchronized <T> void set(Key<T> key, T value) {
            if (value == null) values.remove(key); else values.put(key, value);
        }
        @Override public synchronized <T> boolean replace(Key<T> key, T before, T after) {
            if (values.get(key) != before) return false;
            set(key, after);
            return true;
        }
        @Override @SuppressWarnings("unchecked")
        public <T> T get(Key<T> key) { return (T) values.get(key); }
        @Override @SuppressWarnings("unchecked")
        public <T> T computeIfAbsent(Key<T> key, Supplier<T> create) {
            return (T) values.computeIfAbsent(key, ignored -> create.get());
        }
    }

    private static final class NativeProvider implements ToolProvider {
        int invocations;
        @Override public String name() { return "m3-class-deps"; }
        @Override public int run(PrintWriter out, PrintWriter err, String... args) {
            invocations++;
            try {
                Path source = Path.of(args[args.length - 1]);
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source));
                String hash = java.util.HexFormat.of().formatHex(digest);
                out.println("M3JDK21_CLASS_DEPS_V1\t" + (args.length == 2 ? "PUBLIC_API" : "CLASS")
                        + "\t" + hash + "\tcC5Qcm9iZQ\t1");
                out.println("D\tamF2YS51dGlsLkxpc3Q");
                return 0;
            } catch (Exception error) { err.println("failed"); return 1; }
        }
    }
}
