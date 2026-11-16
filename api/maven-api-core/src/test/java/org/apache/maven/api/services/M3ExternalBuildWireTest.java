/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.maven.api.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.maven.api.services.M3ExternalBuildService.Change;
import org.apache.maven.api.services.M3ExternalBuildService.ChangeKind;
import org.apache.maven.api.services.M3ExternalBuildService.Generation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source-owner wire contract; no IDE process or Maven task is executed. */
final class M3ExternalBuildWireTest {
    private static final String A = "a".repeat(64);

    @Test
    void canonicalRoundTripPreservesOriginalChangesAndUtf8() throws Exception {
        var captured = new M3ExternalBuildWire.Envelope(
                "eclipse.m2e", "résumé", "INCREMENTAL", true,
                List.of(new Change("src/A.java", ChangeKind.ADDED, null),
                        new Change("src/é.java", ChangeKind.MODIFIED, null)));
        byte[] original = M3ExternalBuildWire.encode(captured);
        assertTrue(new String(original, StandardCharsets.UTF_8).startsWith("M3-IDE-DELTA-V1\n"));
        assertEquals(captured, M3ExternalBuildWire.decode(original));
        assertArrayEquals(original, M3ExternalBuildWire.encode(M3ExternalBuildWire.decode(original)));
    }

    @Test
    void missingAndUntrustedContextCanNeverBecomeACompleteDelta() throws Exception {
        var host = new M3ExternalBuildWire.Envelope("eclipse.m2e", "example", "AUTO", false,
                List.of(new Change("src/A.java", ChangeKind.MODIFIED, null)));
        Generation previous = generation(1, true);
        var request = M3ExternalBuildWire.bind(host, previous, generation(2, true));
        assertFalse(request.current().deltaComplete());
        assertEquals(1, request.changes().size());
        assertEquals(previous, request.previous());
        assertFalse(M3ExternalBuildWire.bind(host, previous, generation(2, false)).current().deltaComplete());
    }

    @Test
    void foreignOwnerOrInvalidOrderingCannotRebindIdentity() {
        var host = new M3ExternalBuildWire.Envelope("eclipse.m2e", "example", "AUTO", true, List.of());
        assertThrows(IllegalArgumentException.class,
                () -> M3ExternalBuildWire.bind(host, null, new Generation(
                        "visualstudio.msbuild", "example", 1, A, A, A, A, true)));
        assertThrows(IllegalArgumentException.class,
                () -> new M3ExternalBuildWire.Envelope("eclipse.m2e", "example", "INCREMENTAL", true,
                        List.of(new Change("z.java", ChangeKind.ADDED, null),
                                new Change("a.java", ChangeKind.MODIFIED, null))));
        assertThrows(IllegalArgumentException.class,
                () -> new M3ExternalBuildWire.Envelope("eclipse.m2e", "example", "FULL", true, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new M3ExternalBuildWire.Envelope("eclipse.m2e", "example", "INCREMENTAL", true,
                        List.of(new Change("a.java", ChangeKind.MODIFIED, A))));
    }

    @Test
    void malformedSchemaRowsEncodingAndLineEndingsRefuse() {
        var envelope = new M3ExternalBuildWire.Envelope("eclipse.m2e", "example", "AUTO", true,
                List.of(new Change("src/a.java", ChangeKind.ADDED, null)));
        byte[] valid = M3ExternalBuildWire.encode(envelope);
        for (String invalid : List.of(
                "\ufeff" + new String(valid, StandardCharsets.UTF_8),
                new String(valid, StandardCharsets.UTF_8).replace("M3-IDE-DELTA-V1", "M3-IDE-DELTA-V2"),
                new String(valid, StandardCharsets.UTF_8).replace("complete\ttrue", "complete\tTRUE"),
                new String(valid, StandardCharsets.UTF_8).replace("src/a.java", "../escape.java"),
                new String(valid, StandardCharsets.UTF_8).replace("change\tADDED", "change\tUNKNOWN"),
                new String(valid, StandardCharsets.UTF_8).replace("\n", "\r\n"))) {
            assertThrows(IOException.class, () -> M3ExternalBuildWire.decode(invalid.getBytes(StandardCharsets.UTF_8)));
        }
        assertThrows(IOException.class, () -> M3ExternalBuildWire.decode(new byte[] {(byte) 0xc3, 0x28}));
        assertThrows(IOException.class, () -> M3ExternalBuildWire.decode(new byte[0]));
    }

    private static Generation generation(long number, boolean complete) {
        return new Generation("eclipse.m2e", "example", number, A, A, A, A, complete);
    }
}
