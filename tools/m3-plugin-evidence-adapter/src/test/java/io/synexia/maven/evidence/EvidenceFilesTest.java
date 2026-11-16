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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvidenceFilesTest {
    @TempDir
    Path root;

    @Test
    void contentChangeWithPreservedTimestampChangesIdentity() throws Exception {
        Path file = root.resolve("resource.txt");
        Files.writeString(file, "before");
        FileTime time = Files.getLastModifiedTime(file);
        EvidenceFiles.FileDigest before = EvidenceFiles.digest(root, file, false, 100);
        Files.writeString(file, "after!");
        Files.setLastModifiedTime(file, time);
        EvidenceFiles.FileDigest after = EvidenceFiles.digest(root, file, false, 100);
        assertEquals(before.size(), after.size());
        assertNotEquals(before.sha256(), after.sha256());
    }

    @Test
    void immutablePublicationIsIdempotentAndConflictsPreserveBytes() throws Exception {
        Path directory = root.resolve("target/m3-evidence");
        byte[] bytes = "immutable".getBytes(StandardCharsets.UTF_8);
        Path first = EvidenceFiles.publish(root, directory, "one.receipt", bytes);
        assertEquals(first, EvidenceFiles.publish(root, directory, "one.receipt", bytes));
        assertThrows(IOException.class, () -> EvidenceFiles.publish(root, directory, "one.receipt", new byte[] {1}));
        assertArrayEquals(bytes, Files.readAllBytes(first));
    }

    @Test
    void absentOutputsAreExplicitAndBudgetsFailBeforeRead() throws Exception {
        Path absent = root.resolve("missing");
        assertEquals(new EvidenceFiles.FileDigest("ABSENT", -1), EvidenceFiles.digest(root, absent, true, 0));
        assertThrows(IOException.class, () -> EvidenceFiles.digest(root, absent, false, 100));
        Path input = root.resolve("input");
        Files.write(input, new byte[] {1, 2});
        assertThrows(IOException.class, () -> EvidenceFiles.digest(root, input, false, 1));
        assertThrows(IOException.class, () -> EvidenceFiles.digest(root, root, false, 100));
    }

    @Test
    void escapesAndSymlinkAncestorsCannotPublish() throws Exception {
        Path outside = Files.createTempDirectory(root.getParent(), "outside-evidence-");
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, outside);
        assertThrows(IOException.class, () -> EvidenceFiles.publish(root, link.resolve("child"), "data.json", new byte[0]));
        assertFalse(Files.exists(outside.resolve("child")));
        assertThrows(IOException.class, () -> EvidenceFiles.checked(root, root.resolve("../escape"), true));
        assertThrows(IOException.class, () -> EvidenceFiles.publish(root, root, "data.json", new byte[0]));
    }
}
