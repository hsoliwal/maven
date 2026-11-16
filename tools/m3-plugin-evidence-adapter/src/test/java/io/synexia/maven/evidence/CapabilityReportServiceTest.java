/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.synexia.maven.evidence;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.apache.maven.api.Session;
import org.apache.maven.api.SessionData;
import org.apache.maven.api.services.M3PluginEvidenceService.Identity;
import org.apache.maven.api.services.M3PluginEvidenceService.Receipt;
import org.apache.maven.impl.DefaultM3PluginEvidenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Actual native evidence service with an explicit session-data test fixture. */
class CapabilityReportServiceTest {
    private static final Identity IDENTITY = new Identity(
            "example:resources:jar:1", "io.synexia:m3-plugin-evidence-maven-plugin",
            "0.1.0-SNAPSHOT", "resources", "default-cli", "a".repeat(64), 1);

    @TempDir
    Path directory;

    @Test
    void realServicePublishesReadsAndKeepsSessionAbsenceDistinct() throws Exception {
        var service = new DefaultM3PluginEvidenceService();
        Session session = session();
        String absent = new String(CapabilityReport.read(session, service, IDENTITY), StandardCharsets.UTF_8);
        assertTrue(absent.contains("\"status\":\"UNAVAILABLE\""));
        assertTrue(absent.contains("\"operationCount\":null"));

        ResourceEvidence evidence = new ResourceEvidence(IDENTITY, List.of(new ResourceEvidence.Operation(
                "src/message.txt", "target/classes/message.txt", "a".repeat(64), "b".repeat(64),
                4, 5, false)));
        Receipt receipt = receipt(IDENTITY, ResourceEvidenceCodec.encode(evidence), "valid.bin");
        service.publish(session, receipt);
        service.publish(session, receipt);
        assertArrayEquals(CapabilityReport.render(evidence), CapabilityReport.read(session, service, IDENTITY));
        assertArrayEquals(CapabilityReport.unavailable(IDENTITY), CapabilityReport.read(session(), service, IDENTITY));
    }

    @Test
    void realServiceRejectsTamperedBytesBeforeReportRendering() throws Exception {
        var service = new DefaultM3PluginEvidenceService();
        Session session = session();
        byte[] bytes = ResourceEvidenceCodec.encode(new ResourceEvidence(IDENTITY, List.of()));
        Receipt receipt = receipt(IDENTITY, bytes, "tamper.bin");
        service.publish(session, receipt);
        bytes[bytes.length - 1] = 1;
        Files.write(receipt.file(), bytes);
        assertThrows(IOException.class, () -> CapabilityReport.read(session, service, IDENTITY));
        assertArrayEquals(bytes, Files.readAllBytes(receipt.file()));
    }

    @Test
    void authenticatedBytesStillRequireValidFramingAndMatchingInnerIdentity() throws Exception {
        var service = new DefaultM3PluginEvidenceService();
        Session malformedSession = session();
        Receipt malformed = receipt(IDENTITY, new byte[] {0, 1, 2}, "malformed.bin");
        service.publish(malformedSession, malformed);
        assertThrows(IOException.class, () -> CapabilityReport.read(malformedSession, service, IDENTITY));

        Identity inner = new Identity(IDENTITY.project(), IDENTITY.pluginGa(), IDENTITY.version(),
                IDENTITY.goal(), "another-execution", IDENTITY.contextSha256(), IDENTITY.generation());
        Receipt mismatch = receipt(IDENTITY,
                ResourceEvidenceCodec.encode(new ResourceEvidence(inner, List.of())), "mismatch.bin");
        Session mismatchSession = session();
        service.publish(mismatchSession, mismatch);
        assertThrows(IOException.class, () -> CapabilityReport.read(mismatchSession, service, IDENTITY));
    }

    private Receipt receipt(Identity identity, byte[] bytes, String filename) throws IOException {
        Path file = directory.resolve(filename);
        Files.write(file, bytes);
        return new Receipt(identity, file, ResourceEvidenceCodec.SCHEMA,
                EvidenceFiles.sha256(bytes), ResourceEvidenceCodec.CAPABILITIES);
    }

    private static Session session() {
        Memory memory = new Memory();
        return (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] {Session.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getData") && method.getParameterCount() == 0) {
                        return memory;
                    }
                    throw new AssertionError("unexpected session fixture access: " + method.getName());
                });
    }

    private static final class Memory implements SessionData {
        private final Map<Key<?>, Object> values = new HashMap<>();

        @Override
        public synchronized <T> void set(Key<T> key, T value) {
            if (value == null) {
                values.remove(key);
            } else {
                values.put(key, value);
            }
        }

        @Override
        public synchronized <T> boolean replace(Key<T> key, T previous, T next) {
            if (!Objects.equals(values.get(key), previous)) {
                return false;
            }
            set(key, next);
            return true;
        }

        @Override
        public synchronized <T> T get(Key<T> key) {
            return key.type().cast(values.get(key));
        }

        @Override
        public synchronized <T> T computeIfAbsent(Key<T> key, Supplier<T> supplier) {
            return key.type().cast(values.computeIfAbsent(key, ignored -> supplier.get()));
        }
    }
}
