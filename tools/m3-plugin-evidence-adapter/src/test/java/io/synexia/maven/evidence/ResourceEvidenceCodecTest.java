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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.apache.maven.api.Session;
import org.apache.maven.api.services.M3PluginEvidenceService;
import org.apache.maven.api.services.M3PluginEvidenceService.Identity;
import org.apache.maven.api.services.M3PluginEvidenceService.Receipt;
import org.apache.maven.api.services.M3PluginEvidenceService.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceEvidenceCodecTest {
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final Identity IDENTITY = new Identity(
            "example:resources",
            "io.synexia:m3-plugin-evidence-maven-plugin",
            "0.1.0-SNAPSHOT",
            "resources",
            "default-resources",
            HASH_A,
            1);

    @TempDir
    Path directory;

    private int receiptSequence;

    @Test
    void canonicalOrderingIsIndependentOfCollectionOrderAndMutation() throws IOException {
        ResourceEvidence.Operation first = delegateTrueObservation("a");
        ResourceEvidence.Operation second = delegateTrueObservation("b");
        List<ResourceEvidence.Operation> mutable = new ArrayList<>(List.of(second, first));
        ResourceEvidence evidence = new ResourceEvidence(IDENTITY, mutable);
        mutable.clear();
        ResourceEvidence sorted = new ResourceEvidence(IDENTITY, List.of(first, second));
        byte[] encoded = ResourceEvidenceCodec.encode(evidence);
        assertEquals(List.of(first, second), evidence.operations());
        assertThrows(UnsupportedOperationException.class, () -> evidence.operations().clear());
        assertArrayEquals(ResourceEvidenceCodec.encode(sorted), encoded);
        assertArrayEquals(CapabilityReport.render(sorted), CapabilityReport.render(evidence));
        assertEquals(evidence, ResourceEvidenceCodec.decode(encoded, IDENTITY));
        assertArrayEquals(encoded, ResourceEvidenceCodec.encode(ResourceEvidenceCodec.decode(encoded, IDENTITY)));
    }

    @Test
    void delegateResultsRemainDifferentEvenWhenBytesMatch() throws IOException {
        ResourceEvidence.Operation matching = new ResourceEvidence.Operation(
                "src/main/resources/matching", "target/classes/matching", HASH_A, HASH_A, 3, 3, false);
        ResourceEvidence.Operation different = new ResourceEvidence.Operation(
                "src/main/resources/different", "target/classes/different", HASH_A, HASH_B, 3, 7, false);
        ResourceEvidence.Operation absent = new ResourceEvidence.Operation(
                "src/main/resources/absent", "target/classes/absent", HASH_A, "ABSENT", 3, -1, false);
        ResourceEvidence evidence = new ResourceEvidence(IDENTITY, List.of(matching, different, absent, delegateTrueObservation("new")));
        assertEquals(evidence, ResourceEvidenceCodec.decode(ResourceEvidenceCodec.encode(evidence), IDENTITY));
        String report = utf8(CapabilityReport.render(evidence));
        assertTrue(report.contains("\"delegateTrueCount\":1,\"delegateFalseCount\":3"));
        assertEquals(3, occurrences(report, "\"delegateResult\":false,\"result\":\"DELEGATE_FALSE\""));
        assertTrue(report.contains("\"outputSha256\":\"ABSENT\",\"inputBytes\":3,\"outputBytes\":-1"));
        assertTrue(report.contains("\"complete\":false,\"cacheEligible\":false"));
        assertTrue(report.contains("\"delegateResultMeaning\":\"original-copyFileWithResult-return-value\""));
    }

    @Test
    void impossibleOperationClaimsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> operation(HASH_A, HASH_B, 3, 3, true));
        assertThrows(IllegalArgumentException.class, () -> operation(HASH_A, HASH_A, 3, 4, true));
        assertThrows(IllegalArgumentException.class, () -> operation(HASH_A, "ABSENT", 3, -1, true));
        assertThrows(IllegalArgumentException.class, () -> operation(HASH_A, "ABSENT", 3, 0, false));
        assertThrows(IllegalArgumentException.class, () -> operation(HASH_A, HASH_A, 3, -1, false));
        assertThrows(IllegalArgumentException.class, () -> operation(HASH_A, HASH_A, -1, 3, false));
        assertThrows(IllegalArgumentException.class, () -> operation("A".repeat(64), HASH_A, 3, 3, false));
        assertThrows(IllegalArgumentException.class, () -> operation(HASH_A, "absent", 3, -1, false));
        assertThrows(IllegalArgumentException.class, () -> new ResourceEvidence(IDENTITY, List.of(delegateTrueObservation("a"), delegateTrueObservation("a"))));
    }

    @Test
    void pathsAreRelativeAndUtf8ByteBoundsAreExact() throws IOException {
        for (String path : List.of("", "/outside", "../outside", "a/../b", "a/./b", "a//b", "a/", "a\\b", "C:/outside", "C:relative", "a\0b")) {
            assertThrows(IllegalArgumentException.class, () -> new ResourceEvidence.Operation(path, "target/output", HASH_A, HASH_A, 3, 3, true), path);
        }
        String maximumPath = "é".repeat(1024);
        ResourceEvidence maximum = new ResourceEvidence(IDENTITY, List.of(new ResourceEvidence.Operation(
                maximumPath, "target/output", HASH_A, HASH_A, 3, 3, true)));
        assertEquals(maximum, ResourceEvidenceCodec.decode(ResourceEvidenceCodec.encode(maximum), IDENTITY));
        assertThrows(IllegalArgumentException.class, () -> new ResourceEvidence.Operation(
                maximumPath + "é", "target/output", HASH_A, HASH_A, 3, 3, true));
        assertThrows(IllegalArgumentException.class, () -> new ResourceEvidence.Operation(
                "a/" + (char) 0xd800, "target/output", HASH_A, HASH_A, 3, 3, true));
        assertThrows(IllegalArgumentException.class, () -> new ResourceEvidence(
                IDENTITY, Collections.nCopies(ResourceEvidence.MAX_OPERATIONS + 1, delegateTrueObservation("a"))));
    }

    @Test
    void unicodeAndJsonEscapesPreserveThePathIdentity() throws IOException {
        String name = "é雪😀\"\n\t" + (char) 0x7f + ".txt";
        ResourceEvidence evidence = new ResourceEvidence(IDENTITY, List.of(new ResourceEvidence.Operation(
                "src/main/resources/" + name, "target/classes/" + name, HASH_A, HASH_A, 3, 3, true)));
        assertEquals(evidence, ResourceEvidenceCodec.decode(ResourceEvidenceCodec.encode(evidence), IDENTITY));
        String report = utf8(CapabilityReport.render(evidence));
        assertTrue(report.contains("é雪😀\\\"\\n\\t\\u007f.txt"));
        assertTrue(report.endsWith("}\n"));
        assertEquals(1, report.chars().filter(character -> character == '\n').count());
        assertFalse(report.contains("\t"));
        Identity malformed = new Identity("bad-" + (char) 0xd800, IDENTITY.pluginGa(), IDENTITY.version(), IDENTITY.goal(), IDENTITY.execution(), HASH_A, 1);
        assertThrows(IllegalArgumentException.class, () -> new ResourceEvidence(malformed, List.of()));
        assertThrows(IllegalArgumentException.class, () -> CapabilityReport.unavailable(malformed));
    }

    @Test
    void allSevenInvocationFieldsMustMatch() {
        List<Identity> different = List.of(
                new Identity("other:project", IDENTITY.pluginGa(), IDENTITY.version(), IDENTITY.goal(), IDENTITY.execution(), HASH_A, 1),
                new Identity(IDENTITY.project(), "other:plugin", IDENTITY.version(), IDENTITY.goal(), IDENTITY.execution(), HASH_A, 1),
                new Identity(IDENTITY.project(), IDENTITY.pluginGa(), "other-version", IDENTITY.goal(), IDENTITY.execution(), HASH_A, 1),
                new Identity(IDENTITY.project(), IDENTITY.pluginGa(), IDENTITY.version(), "other-goal", IDENTITY.execution(), HASH_A, 1),
                new Identity(IDENTITY.project(), IDENTITY.pluginGa(), IDENTITY.version(), IDENTITY.goal(), "other-execution", HASH_A, 1),
                new Identity(IDENTITY.project(), IDENTITY.pluginGa(), IDENTITY.version(), IDENTITY.goal(), IDENTITY.execution(), HASH_B, 1),
                new Identity(IDENTITY.project(), IDENTITY.pluginGa(), IDENTITY.version(), IDENTITY.goal(), IDENTITY.execution(), HASH_A, 2));
        byte[] encoded = ResourceEvidenceCodec.encode(new ResourceEvidence(IDENTITY, List.of(delegateTrueObservation("a"))));
        for (Identity expected : different) {
            assertThrows(IOException.class, () -> ResourceEvidenceCodec.decode(encoded, expected));
        }
    }

    @Test
    void hostileFramingAndValuesCannotBeSilentlyCanonicalized() {
        byte[] valid = ResourceEvidenceCodec.encode(new ResourceEvidence(IDENTITY, List.of(delegateTrueObservation("a"))));
        List<byte[]> invalid = new ArrayList<>();
        invalid.add(new byte[0]);
        invalid.add(Arrays.copyOf(valid, valid.length - 1));
        invalid.add(Arrays.copyOf(valid, valid.length + 1));
        invalid.add(withInt(valid, 0, 0));
        invalid.add(withInt(valid, 4, 2));
        invalid.add(withInt(valid, 8, -1));
        invalid.add(withInt(valid, 8, ResourceEvidence.MAX_IDENTITY_UTF8_BYTES + 1));
        invalid.add(withInt(valid, countOffset(valid), -1));
        invalid.add(withInt(valid, countOffset(valid), ResourceEvidence.MAX_OPERATIONS + 1));
        invalid.add(withInt(valid, textOffset(valid, 0, 0), ResourceEvidence.MAX_PATH_UTF8_BYTES + 1));
        byte[] badBoolean = valid.clone();
        badBoolean[badBoolean.length - 1] = 2;
        invalid.add(badBoolean);
        byte[] badHash = valid.clone();
        badHash[textOffset(valid, 0, 2) + Integer.BYTES] = 'G';
        invalid.add(badHash);
        byte[] absolutePath = valid.clone();
        absolutePath[textOffset(valid, 0, 0) + Integer.BYTES] = '/';
        invalid.add(absolutePath);
        byte[] badSize = valid.clone();
        ByteBuffer.wrap(badSize).putLong(valid.length - 2 * Long.BYTES - 1, -1);
        invalid.add(badSize);
        byte[] malformedUtf8 = valid.clone();
        int pathStart = textOffset(valid, 0, 0) + Integer.BYTES;
        malformedUtf8[pathStart] = (byte) 0xc3;
        malformedUtf8[pathStart + 1] = 0x28;
        invalid.add(malformedUtf8);
        for (byte[] bytes : invalid) {
            assertThrows(IOException.class, () -> ResourceEvidenceCodec.decode(bytes, IDENTITY));
        }
        assertThrows(IOException.class, () -> ResourceEvidenceCodec.decode(
                new byte[M3PluginEvidenceService.MAX_RECEIPT_BYTES + 1], IDENTITY));
    }

    @Test
    void duplicateAndUnsortedWireDestinationsAreRejected() {
        byte[] valid = ResourceEvidenceCodec.encode(new ResourceEvidence(IDENTITY, List.of(delegateTrueObservation("a"), delegateTrueObservation("b"))));
        int firstOutput = textOffset(valid, 0, 1);
        int secondOutput = textOffset(valid, 1, 1);
        int outputLength = ByteBuffer.wrap(valid).getInt(firstOutput);
        assertEquals(outputLength, ByteBuffer.wrap(valid).getInt(secondOutput));
        byte[] duplicate = valid.clone();
        System.arraycopy(valid, firstOutput + Integer.BYTES, duplicate, secondOutput + Integer.BYTES, outputLength);
        assertThrows(IOException.class, () -> ResourceEvidenceCodec.decode(duplicate, IDENTITY));
        int first = textOffset(valid, 0, 0);
        int second = textOffset(valid, 1, 0);
        byte[] unsorted = valid.clone();
        System.arraycopy(valid, second, unsorted, first, valid.length - second);
        System.arraycopy(valid, first, unsorted, first + valid.length - second, second - first);
        assertThrows(IOException.class, () -> ResourceEvidenceCodec.decode(unsorted, IDENTITY));
    }

    @Test
    void operationCountBoundaryAndKnownEmptyRemainRepresentable() throws IOException {
        List<ResourceEvidence.Operation> maximum = new ArrayList<>();
        for (int index = 0; index < ResourceEvidence.MAX_OPERATIONS; index++) {
            maximum.add(delegateTrueObservation(Integer.toString(index)));
        }
        ResourceEvidence evidence = new ResourceEvidence(IDENTITY, maximum);
        assertEquals(evidence, ResourceEvidenceCodec.decode(ResourceEvidenceCodec.encode(evidence), IDENTITY));
        ResourceEvidence empty = new ResourceEvidence(IDENTITY, List.of());
        assertEquals(empty, ResourceEvidenceCodec.decode(ResourceEvidenceCodec.encode(empty), IDENTITY));
        assertTrue(utf8(CapabilityReport.render(empty)).contains("\"operationCount\":0"));
        assertTrue(utf8(CapabilityReport.render(empty)).contains("\"operations\":[]"));
    }

    @Test
    void reportConsumesVerifiedSnapshotWithoutReopeningItsFile() throws Exception {
        ResourceEvidence evidence = new ResourceEvidence(IDENTITY, List.of(delegateTrueObservation("a")));
        Snapshot snapshot = snapshot(ResourceEvidenceCodec.encode(evidence));
        Files.writeString(snapshot.receipt().file(), "changed after snapshot");
        Session session = session();
        SnapshotService service = new SnapshotService(session, Optional.of(snapshot), null);
        assertArrayEquals(CapabilityReport.render(evidence), CapabilityReport.read(session, service, IDENTITY));
        assertEquals(1, service.reads);
    }

    @Test
    void absentAndInvalidEvidenceRemainDistinct() throws Exception {
        Session session = session();
        SnapshotService absent = new SnapshotService(session, Optional.empty(), null);
        String unavailable = utf8(CapabilityReport.read(session, absent, IDENTITY));
        assertTrue(unavailable.contains("\"status\":\"UNAVAILABLE\""));
        assertTrue(unavailable.contains("\"operationCount\":null"));
        assertTrue(unavailable.contains("\"operations\":null"));
        assertFalse(unavailable.contains("\"status\":\"KNOWN\""));
        byte[] invalid = ResourceEvidenceCodec.encode(new ResourceEvidence(IDENTITY, List.of(delegateTrueObservation("a"))));
        invalid[invalid.length - 1] = 2;
        SnapshotService malformed = new SnapshotService(session, Optional.of(snapshot(invalid)), null);
        assertThrows(IOException.class, () -> CapabilityReport.read(session, malformed, IDENTITY));
        IOException tamper = new IOException("receipt digest mismatch");
        SnapshotService corrupted = new SnapshotService(session, Optional.empty(), tamper);
        assertSame(tamper, assertThrows(IOException.class, () -> CapabilityReport.read(session, corrupted, IDENTITY)));
    }

    private static ResourceEvidence.Operation delegateTrueObservation(String name) {
        return new ResourceEvidence.Operation(
                "src/main/resources/" + name, "target/classes/" + name, HASH_A, HASH_A, 3, 3, true);
    }

    private static ResourceEvidence.Operation operation(String inputHash, String outputHash, long inputBytes, long outputBytes, boolean delegateResult) {
        return new ResourceEvidence.Operation("src/input", "target/output", inputHash, outputHash, inputBytes, outputBytes, delegateResult);
    }

    private static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static byte[] withInt(byte[] bytes, int offset, int value) {
        byte[] modified = bytes.clone();
        ByteBuffer.wrap(modified).putInt(offset, value);
        return modified;
    }

    private static int countOffset(byte[] bytes) {
        ByteBuffer input = ByteBuffer.wrap(bytes);
        input.position(2 * Integer.BYTES);
        for (int field = 0; field < 6; field++) {
            skipText(input);
        }
        input.getLong();
        return input.position();
    }

    private static int textOffset(byte[] bytes, int operation, int field) {
        ByteBuffer input = ByteBuffer.wrap(bytes);
        input.position(countOffset(bytes) + Integer.BYTES);
        for (int index = 0; index < operation; index++) {
            for (int text = 0; text < 4; text++) {
                skipText(input);
            }
            input.position(input.position() + 2 * Long.BYTES + 1);
        }
        for (int text = 0; text < field; text++) {
            skipText(input);
        }
        return input.position();
    }

    private static void skipText(ByteBuffer input) {
        int length = input.getInt();
        input.position(input.position() + length);
    }

    private Snapshot snapshot(byte[] bytes) throws Exception {
        Path file = directory.resolve("receipt-" + receiptSequence++ + ".bin");
        Files.write(file, bytes);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new Receipt(IDENTITY, file, ResourceEvidenceCodec.SCHEMA, hash, ResourceEvidenceCodec.CAPABILITIES).snapshot();
    }

    private static Session session() {
        return (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] {Session.class}, (proxy, method, arguments) -> {
            throw new AssertionError("report must not access session state directly: " + method.getName());
        });
    }

    private static final class SnapshotService implements M3PluginEvidenceService {
        private final Session session;
        private final Optional<Snapshot> snapshot;
        private final IOException failure;
        private int reads;

        private SnapshotService(Session session, Optional<Snapshot> snapshot, IOException failure) {
            this.session = session;
            this.snapshot = snapshot;
            this.failure = failure;
        }

        @Override
        public Optional<Snapshot> read(Session actual, Identity identity, String schema, int mask) throws IOException {
            reads++;
            assertSame(session, actual);
            assertEquals(IDENTITY, identity);
            assertEquals(ResourceEvidenceCodec.SCHEMA, schema);
            assertEquals(7, mask);
            if (failure != null) {
                throw failure;
            }
            return snapshot;
        }

        @Override
        public void publish(Session ignored, Receipt receipt) {
            throw new AssertionError("report must not publish");
        }

        @Override
        public Optional<Receipt> find(Session ignored, Identity identity, String schema, int mask) {
            throw new AssertionError("report must use verified snapshot read");
        }

        @Override
        public void revoke(Session ignored, Identity identity) {
            throw new AssertionError("report must not revoke");
        }
    }
}
