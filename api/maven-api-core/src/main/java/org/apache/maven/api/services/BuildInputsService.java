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
package org.apache.maven.api.services;

// SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

import org.apache.maven.api.Service;
import org.apache.maven.api.Session;
import org.apache.maven.api.annotations.Experimental;

/**
 * Typed, read-only resource input/output evidence from a producer-owned Maven goal.
 *
 * <p>The original goal owns the files and declares the inventory. This service
 * authenticates and parses its manifest through {@link M3PluginEvidenceService};
 * it does not scan files, execute goals, infer completeness or approve build-cache hits.
 * Existing resource and build-helper plugins continue to own their goal behavior.
 *
 * <p>The v1 manifest uses UTF-8 and LF-delimited canonical rows:
 * <pre>
 * M3-RESOURCE-IO-V1
 * role\tpath\tsha256\tbytes
 * I\tsrc/main/resources/a.txt\t...64 lowercase hexadecimal characters...\t12
 * O\ttarget/classes/a.txt\t...64 lowercase hexadecimal characters...\t12
 * </pre>
 * Each path is a normalized, project-relative logical name, not a file read
 * instruction; input rows precede output rows and are sorted by path per role.
 * A digest in a row is a producer claim, not independent verification of that file.
 */
@Experimental
public interface BuildInputsService extends Service {
    String SCHEMA = "m3.resource-io.manifest.v1";
    int CAPABILITIES =
            M3PluginEvidenceService.INPUTS | M3PluginEvidenceService.OUTPUTS | M3PluginEvidenceService.RESOURCES;

    enum Direction {
        INPUT,
        OUTPUT
    }

    /**
     * Immutable claim made by the producer; no file content is stored.
     *
     * @param direction whether the producer classified the file as input or output
     * @param relativePath normalized project-relative logical name
     * @param sha256 lowercase SHA-256 recorded by the producer
     * @param bytes nonnegative byte count recorded by the producer
     */
    record FileFact(Direction direction, String relativePath, String sha256, long bytes) {
        public FileFact {
            direction = Objects.requireNonNull(direction, "direction");
            relativePath = Objects.requireNonNull(relativePath, "relativePath");
            sha256 = Objects.requireNonNull(sha256, "sha256");
            if (bytes < 0) {
                throw new IllegalArgumentException("negative byte count");
            }
        }
    }

    /**
     * Immutable, array-backed indexed inventory. Entries are materialized on demand.
     * A manifest authenticates the producer receipt bytes but does not prove that
     * its file inventory covers every input, that file digests are true or that
     * the described outputs remain current.
     */
    interface Manifest {
        /** @return the original authenticated producer receipt */
        M3PluginEvidenceService.Receipt receipt();

        /** @return total number of producer-declared file rows */
        int size();

        /** @return number of input rows, always the first rows */
        int inputCount();

        /** @return number of output rows following the inputs */
        int outputCount();

        /** @return one file claim without retaining per-file objects */
        FileFact entry(int index);
    }

    /**
     * Finds and parses only a matching, already-published resource receipt.
     * Missing identity/schema/capability returns empty. Malformed or changed
     * bytes throw IOException, never silently pass. Does not publish receipts.
     *
     * @param session original Maven session owning the receipt
     * @param identity exact original producer invocation identity
     * @return an immutable inventory, or empty if no matching receipt exists
     * @throws IOException if the producer receipt is unreadable or invalid
     */
    Optional<Manifest> read(Session session, M3PluginEvidenceService.Identity identity) throws IOException;
}
