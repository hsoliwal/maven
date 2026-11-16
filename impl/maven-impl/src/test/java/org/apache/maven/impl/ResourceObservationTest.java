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

// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.maven.api.services.ResourceObservation;
import org.apache.maven.api.services.ResourceObservation.Area;
import org.apache.maven.api.services.ResourceObservation.FileState;
import org.apache.maven.api.services.ResourceObservation.Kind;
import org.apache.maven.api.services.ResourceObservation.ResourceRoot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Public observation format tests using real encoded receipts, including hostile input. */
final class ResourceObservationTest {
    @Test
    void deterministicRoundTripRetainsActualStatesAndOrderedDeclarations() throws Exception {
        var observation = sample();
        byte[] encoded = encode(observation);
        assertArrayEquals(encoded, encode(observation));
        assertEquals(observation, ResourceObservation.readFrom(new ByteArrayInputStream(encoded)));
        assertEquals("PARTIAL", observation.completeness());
        assertEquals("old bytes", observation.limitations().get(1));
        assertEquals(1, observation.before().get(0).size());
        assertEquals(2, observation.after().get(0).size());
    }

    @Test
    void everyTruncatedPrefixAndTrailingDataAreRejected() throws Exception {
        byte[] encoded = encode(sample());
        for (int length = 0; length < encoded.length; length++) {
            byte[] truncated = Arrays.copyOf(encoded, length);
            assertThrows(IOException.class, () -> ResourceObservation.readFrom(new ByteArrayInputStream(truncated)));
        }
        byte[] extra = Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IOException.class, () -> ResourceObservation.readFrom(new ByteArrayInputStream(extra)));
        encoded[0] ^= 1;
        assertThrows(IOException.class, () -> ResourceObservation.readFrom(new ByteArrayInputStream(encoded)));
    }

    @Test
    void noncanonicalBooleanEncodingIsRejected() throws Exception {
        byte[] encoded = encode(sample());
        encoded[8 + 2 + 64] = 2;
        assertThrows(IOException.class, () -> ResourceObservation.readFrom(new ByteArrayInputStream(encoded)));
    }

    @Test
    void collectionCountsAreCheckedBeforeAllocatingEntries() throws Exception {
        for (int invalid : new int[] {-1, ResourceObservation.MAX_ROOTS + 1, Integer.MAX_VALUE}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeLong(0x4d33524553303031L);
            out.writeUTF("a".repeat(64));
            out.writeBoolean(false);
            out.writeUTF("file:///project/target/classes/");
            out.writeInt(invalid);
            assertThrows(
                    IOException.class,
                    () -> ResourceObservation.readFrom(new ByteArrayInputStream(bytes.toByteArray())));
        }
    }

    @Test
    void callerListsAreDefensivelyCopiedAtEveryLevel() {
        List<String> includes = new ArrayList<>(List.of("**/*.txt"));
        ResourceRoot root = new ResourceRoot("file:///project/src/", "", false, includes, List.of());
        List<ResourceRoot> roots = new ArrayList<>(List.of(root));
        List<String> limitations = new ArrayList<>(List.of("PARTIAL"));
        ResourceObservation observation = new ResourceObservation(
                "a".repeat(64), false, roots, "file:///project/target/", List.of(), List.of(), limitations);
        includes.clear();
        roots.clear();
        limitations.clear();
        assertEquals(List.of("**/*.txt"), observation.resources().get(0).includes());
        assertThrows(
                UnsupportedOperationException.class,
                () -> observation.resources().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> observation.resources().get(0).includes().clear());
    }

    @Test
    void rejectsInvalidIdentityMetadataAndUnboundedPayloads() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceRoot("relative/path", "", false, List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceRoot("https://example.com/", "", false, List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceRoot("file:///a?secret", "", false, List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceRoot("file:///a", "x".repeat(8193), false, List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FileState(Area.INPUT, "file:///a", Kind.FILE, -1, 0, "UNKNOWN", "a".repeat(64)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FileState(Area.INPUT, "file:///a", Kind.FILE, 1, 0, "rwxrwxrwt", "a".repeat(64)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FileState(Area.INPUT, "file:///a", Kind.MISSING, 1, 0, "UNKNOWN", ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FileState(Area.INPUT, "file:///a", Kind.DIRECTORY, 0, 0, "UNKNOWN", "a".repeat(64)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceObservation(
                        "a".repeat(64), false, List.of(), "file:///a", List.of(), List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceObservation(
                        "A".repeat(64), false, List.of(), "file:///a", List.of(), List.of(), List.of("PARTIAL")));
        var tooMany = java.util.Collections.nCopies(
                ResourceObservation.MAX_FILES + 1, sample().before().get(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceObservation(
                        "a".repeat(64), false, List.of(), "file:///a", tooMany, List.of(), List.of("PARTIAL")));
    }

    @Test
    void byteBudgetBoundsLargeValidCollectionsWithoutClosingCallerStreams() throws Exception {
        ResourceRoot large = new ResourceRoot(
                "file:///source/", "", false, java.util.Collections.nCopies(256, "x".repeat(8192)), List.of());
        ResourceObservation oversized = new ResourceObservation(
                "a".repeat(64),
                false,
                java.util.Collections.nCopies(5, large),
                "file:///target/",
                List.of(),
                List.of(),
                List.of("PARTIAL"));
        assertThrows(IOException.class, () -> oversized.writeTo(OutputStreamSink.INSTANCE));
        TrackingOutput bytes = new TrackingOutput();
        sample().writeTo(bytes);
        assertFalse(bytes.closed);
        TrackingInput input = new TrackingInput(bytes.toByteArray());
        assertEquals(sample(), ResourceObservation.readFrom(input));
        assertFalse(input.closed);
    }

    @Test
    void decoderEnforcesByteBudgetOnOtherwiseValidBoundedCollections() throws Exception {
        byte[] within = rawLargeObservation(3);
        assertEquals(
                3,
                ResourceObservation.readFrom(new ByteArrayInputStream(within))
                        .resources()
                        .size());
        byte[] above = rawLargeObservation(5);
        IOException failure =
                assertThrows(IOException.class, () -> ResourceObservation.readFrom(new ByteArrayInputStream(above)));
        assertEquals("resource observation exceeds byte budget", failure.getMessage());
    }

    @Test
    void malformedEncodedFileKindsAndMetadataBecomeCheckedReadFailures() throws Exception {
        for (int variant = 0; variant < 4; variant++) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeLong(0x4d33524553303031L);
            out.writeUTF("a".repeat(64));
            out.writeBoolean(false);
            out.writeUTF("file:///target/");
            out.writeInt(0);
            out.writeInt(1);
            out.writeByte(variant == 0 ? 255 : 0);
            out.writeUTF("file:///source/data");
            out.writeByte(variant == 1 ? 255 : 0);
            out.writeLong(variant == 2 ? -1 : 1);
            out.writeLong(0);
            out.writeUTF("UNKNOWN");
            out.writeUTF(variant == 3 ? "B".repeat(64) : "b".repeat(64));
            out.writeInt(0);
            out.writeInt(1);
            out.writeUTF("PARTIAL");
            assertThrows(
                    IOException.class,
                    () -> ResourceObservation.readFrom(new ByteArrayInputStream(bytes.toByteArray())));
        }
    }

    private static byte[] rawLargeObservation(int roots) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeLong(0x4d33524553303031L);
        out.writeUTF("a".repeat(64));
        out.writeBoolean(false);
        out.writeUTF("file:///target/");
        out.writeInt(roots);
        for (int root = 0; root < roots; root++) {
            out.writeUTF("file:///source/" + root);
            out.writeUTF("");
            out.writeBoolean(false);
            out.writeInt(256);
            for (int rule = 0; rule < 256; rule++) {
                out.writeUTF("x".repeat(8192));
            }
            out.writeInt(0);
        }
        out.writeInt(0);
        out.writeInt(0);
        out.writeInt(1);
        out.writeUTF("PARTIAL");
        return bytes.toByteArray();
    }

    private static ResourceObservation sample() {
        return new ResourceObservation(
                "a".repeat(64),
                false,
                List.of(new ResourceRoot(
                        "file:///project/src/",
                        "nested",
                        true,
                        List.of("**/*.txt", "name-\u03b2"),
                        List.of("**/secret"))),
                "file:///project/target/classes/",
                List.of(
                        new FileState(
                                Area.INPUT,
                                "file:///project/src/data.txt",
                                Kind.FILE,
                                1,
                                123,
                                "rw-r-----",
                                "b".repeat(64)),
                        new FileState(
                                Area.OUTPUT, "file:///project/target/classes/", Kind.MISSING, 0, 0, "UNKNOWN", "")),
                List.of(new FileState(
                        Area.OUTPUT,
                        "file:///project/target/classes/data.txt",
                        Kind.FILE,
                        2,
                        456,
                        "rw-r-----",
                        "c".repeat(64))),
                List.of("OUTPUT_OWNERSHIP_UNPROVEN", "old bytes"));
    }

    private static byte[] encode(ResourceObservation observation) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        observation.writeTo(out);
        return out.toByteArray();
    }

    private static final class TrackingOutput extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class TrackingInput extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInput(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class OutputStreamSink extends java.io.OutputStream {
        private static final OutputStreamSink INSTANCE = new OutputStreamSink();

        @Override
        public void write(int value) {}

        @Override
        public void write(byte[] bytes, int offset, int length) {}
    }
}
