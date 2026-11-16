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
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.maven.api.services.M3PluginEvidenceService.Identity;
import org.junit.jupiter.api.Test;

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

    private static ResourceEvidence.Operation operation(String source, String output, String inHash, String outHash,
            long inBytes, long outBytes, boolean delegateResult) {
        return new ResourceEvidence.Operation(source, output, inHash, outHash, inBytes, outBytes, delegateResult);
    }
}
