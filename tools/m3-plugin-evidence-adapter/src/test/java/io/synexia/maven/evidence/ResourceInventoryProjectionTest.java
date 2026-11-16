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
import org.apache.maven.api.services.BuildInputsService;
import org.apache.maven.api.services.M3PluginEvidenceService.Identity;
import org.apache.maven.api.services.M3PluginEvidenceService.Receipt;
import org.apache.maven.impl.DefaultBuildInputsService;
import org.apache.maven.impl.DefaultM3PluginEvidenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceInventoryProjectionTest {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final Identity ID = new Identity(
            "example:resources", "io.synexia:m3-plugin-evidence-maven-plugin",
            "0.1.0-SNAPSHOT", "resources", "default-resources", A, 1);

    @TempDir
    Path directory;

    @Test
    void sortedProjectionDeduplicatesSourcesAndOmitsAbsentOutputs() throws IOException {
        var first = operation("src/shared.txt", "target/z.txt", A, A, 2, 2, true);
        var second = operation("src/aa.txt", "target/a.txt", B, B, 4, 4, true);
        var absent = operation("src/shared.txt", "target/absent.txt", A, ResourceEvidence.ABSENT, 2, -1, false);
        byte[] actual = ResourceInventoryProjection.encode(new ResourceEvidence(ID, List.of(first, absent, second)));
        String header = "M3-RESOURCE-IO-V1\nrole\tpath\tsha256\tbytes\n";
        String expected = header + "I\tsrc/aa.txt\t" + B + "\t4\n"
                + "I\tsrc/shared.txt\t" + A + "\t2\n"
                + "O\ttarget/a.txt\t" + B + "\t4\n"
                + "O\ttarget/z.txt\t" + A + "\t2\n";
        assertEquals(expected, new String(actual, StandardCharsets.UTF_8));
        assertArrayEquals(
                ResourceInventoryProjection.encode(new ResourceEvidence(ID, List.of(second, first, absent))), actual);
        assertFalse(expected.contains("target/absent.txt"));
        assertTrue(expected.endsWith("\n"));
    }

    @Test
    void emptyOperationInventoryRemainsNonAuthoritative() throws IOException {
        byte[] empty = ResourceInventoryProjection.encode(new ResourceEvidence(ID, List.of()));
        assertEquals("M3-RESOURCE-IO-V1\nrole\tpath\tsha256\tbytes\n",
                new String(empty, StandardCharsets.UTF_8));
        assertEquals("m3.resource-io.manifest.v1", ResourceInventoryProjection.SCHEMA);
    }

    @Test
    void conflictingRepeatedInputObservationIsNeverCanonicalized() {
        var first = operation("src/shared.txt", "target/a.txt", A, A, 2, 2, true);
        var conflicting = operation("src/shared.txt", "target/b.txt", B, B, 3, 3, true);
        assertThrows(IOException.class, () -> ResourceInventoryProjection.encode(
                new ResourceEvidence(ID, List.of(first, conflicting))));
    }

    @Test
    void originalMavenRegistryMakesTextProjectionReadableByBuildInputsService() throws Exception {
        var actualOperation = operation("src/a.txt", "target/classes/a.txt", A, B, 3, 5, false);
        var observed = new ResourceEvidence(ID, List.of(actualOperation));
        byte[] payload = ResourceInventoryProjection.encode(observed);
        Path inventoryFile = directory.resolve("inventory.tsv");
        Files.write(inventoryFile, payload);
        var receipt = new Receipt(
                ID,
                inventoryFile,
                ResourceInventoryProjection.SCHEMA,
                EvidenceFiles.sha256(payload),
                ResourceEvidenceCodec.CAPABILITIES);
        Session session = session();
        var registry = new DefaultM3PluginEvidenceService();
        var consumer = new DefaultBuildInputsService(registry);
        assertTrue(consumer.read(session, ID).isEmpty());

        registry.publish(session, receipt);
        BuildInputsService.Manifest inventory = consumer.read(session, ID).orElseThrow();
        assertEquals(2, inventory.size());
        assertEquals(1, inventory.inputCount());
        assertEquals(1, inventory.outputCount());
        assertEquals("src/a.txt", inventory.entry(0).relativePath());
        assertEquals("target/classes/a.txt", inventory.entry(1).relativePath());
        assertEquals(5, inventory.entry(1).bytes());
        assertArrayEquals(payload, registry.read(
                        session, ID, ResourceInventoryProjection.SCHEMA, ResourceEvidenceCodec.CAPABILITIES)
                .orElseThrow().bytes());

        byte[] binary = ResourceEvidenceCodec.encode(observed);
        Path binaryFile = directory.resolve("original-operations.bin");
        Files.write(binaryFile, binary);
        var competingReceipt = new Receipt(
                ID, binaryFile, ResourceEvidenceCodec.SCHEMA,
                EvidenceFiles.sha256(binary), ResourceEvidenceCodec.CAPABILITIES);
        assertThrows(IOException.class, () -> registry.publish(session, competingReceipt));
        registry.revoke(session, ID);
        assertTrue(consumer.read(session, ID).isEmpty());
    }

    @Test
    void sourcePathsOutsideTheTextConsumerContractFailClosed() {
        for (String path : List.of("src/a:b.txt", "src/new\nline.txt", "src/name\tpart.txt",
                "src/" + "a".repeat(1025), "src/" + (char) 0x7f + ".txt")) {
            var operation = operation(path, "target/result.txt", A, A, 1, 1, true);
            assertThrows(IOException.class, () -> ResourceInventoryProjection.encode(
                    new ResourceEvidence(ID, List.of(operation))), path);
        }
    }

    @Test
    void falseDelegateResultDoesNotSuppressPhysicallyObservedOutput() throws IOException {
        var changed = operation("src/data.txt", "target/data.txt", A, B, 3, 9, false);
        String inventory = new String(ResourceInventoryProjection.encode(
                new ResourceEvidence(ID, List.of(changed))), StandardCharsets.UTF_8);
        assertTrue(inventory.contains("O\ttarget/data.txt\t" + B + "\t9\n"));
    }

    private static Session session() {
        Memory memory = new Memory();
        return (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] {Session.class},
                (proxy, method, args) -> {
                    if ("getData".equals(method.getName()) && method.getParameterCount() == 0) {
                        return memory;
                    }
                    throw new AssertionError("unexpected session fixture method: " + method.getName());
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
        public synchronized <T> T computeIfAbsent(Key<T> key, Supplier<T> supply) {
            return key.type().cast(values.computeIfAbsent(key, ignored -> supply.get()));
        }
    }

    private static ResourceEvidence.Operation operation(String source, String output, String inHash, String outHash,
            long inBytes, long outBytes, boolean delegateResult) {
        return new ResourceEvidence.Operation(source, output, inHash, outHash, inBytes, outBytes, delegateResult);
    }
}
