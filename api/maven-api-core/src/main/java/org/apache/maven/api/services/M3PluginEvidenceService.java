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

// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.maven.api.Service;
import org.apache.maven.api.Session;
import org.apache.maven.api.annotations.Experimental;

/**
 * Shared, read-only evidence from original Maven plugin goals. A producer owns
 * its work and output; this service checks receipt-byte custody and makes no
 * cache, build, deployment, semantic-parity or goal-skipping decision.
 * Original compiler and dependency facts retain their specialized owners.
 */
@Experimental
public interface M3PluginEvidenceService extends Service {
    int INPUTS = 1, OUTPUTS = 2, RESOURCES = 4, ARCHIVE = 8;
    int TESTS = 16, DEPENDENCIES = 32, PUBLICATION = 64, ANALYSIS = 128;
    int KNOWN_CAPABILITIES = INPUTS | OUTPUTS | RESOURCES | ARCHIVE | TESTS | DEPENDENCIES | PUBLICATION | ANALYSIS;

    /** Maximum receipt size admitted by verification and snapshot reads. */
    int MAX_RECEIPT_BYTES = 16 * 1024 * 1024;

    /**
     * Exact original Mojo identity and source-bound invocation generation.
     *
     * @param project project identity for the producer invocation
     * @param pluginGa plugin group and artifact, separated by a colon
     * @param version plugin version
     * @param goal executed Mojo goal
     * @param execution execution identifier
     * @param contextSha256 lowercase SHA-256 digest of the invocation context
     * @param generation positive generation of this invocation
     */
    record Identity(
            String project,
            String pluginGa,
            String version,
            String goal,
            String execution,
            String contextSha256,
            long generation) {
        public Identity {
            project = name(project, "project");
            pluginGa = name(pluginGa, "pluginGa");
            int separator = pluginGa.indexOf(':');
            if (separator <= 0 || separator == pluginGa.length() - 1 || pluginGa.indexOf(':', separator + 1) >= 0) {
                throw new IllegalArgumentException("pluginGa");
            }
            version = name(version, "version");
            goal = name(goal, "goal");
            execution = name(execution, "execution");
            contextSha256 = sha(contextSha256);
            if (generation < 1) {
                throw new IllegalArgumentException("generation");
            }
        }
    }

    /**
     * A producer-owned immutable receipt, never an assertion of goal parity.
     *
     * @param identity invocation that owns the evidence
     * @param file producer-owned file containing the evidence
     * @param schema schema identifier for the file contents
     * @param sha256 expected lowercase SHA-256 digest of the file
     * @param capabilities nonempty mask of supported evidence capabilities
     */
    record Receipt(Identity identity, Path file, String schema, String sha256, int capabilities) {
        public Receipt {
            identity = Objects.requireNonNull(identity, "identity");
            file = Objects.requireNonNull(file, "file");
            schema = name(schema, "schema");
            sha256 = sha(sha256);
            if (capabilities <= 0 || (capabilities & ~KNOWN_CAPABILITIES) != 0) {
                throw new IllegalArgumentException("capabilities");
            }
        }

        /**
         * Verifies the current file bytes without retaining their contents.
         * This checks byte custody only and does not publish the receipt to a session.
         *
         * @throws IOException if the file is not a bounded regular file or its bytes differ
         */
        public void verify() throws IOException {
            readReceipt(this, false);
        }

        /**
         * Reads and verifies the exact bytes returned in an immutable snapshot.
         * Prefer the service's {@link M3PluginEvidenceService#read} operation when
         * session membership and invocation matching must also be checked.
         *
         * @return a snapshot independent of subsequent changes to the file
         * @throws IOException if the file is not a bounded regular file or its bytes differ
         */
        public Snapshot snapshot() throws IOException {
            return new Snapshot(this, readReceipt(this, true));
        }
    }

    /**
     * An authenticated receipt and its exact bytes. The service does not retain
     * these bytes in its session store. A snapshot establishes byte custody,
     * not semantic parity, cache eligibility, or permission to skip a goal.
     */
    final class Snapshot {
        private final Receipt receipt;
        private final byte[] bytes;

        private Snapshot(Receipt receipt, byte[] bytes) {
            this.receipt = receipt;
            this.bytes = bytes;
        }

        /** @return the immutable receipt whose digest authenticated the snapshot */
        public Receipt receipt() {
            return receipt;
        }

        /** @return an independent copy of the authenticated bytes */
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /** Binds one immutable provider receipt to this session after byte verification. */
    void publish(Session session, Receipt receipt) throws IOException;

    /**
     * Discovers registered invocation identities for an exact schema and capability mask.
     * This immutable session snapshot performs no file reads and may become stale after
     * return. Consumers must use {@link #find} or a typed reader to verify the evidence.
     * Providers which do not expose discovery retain the default empty result.
     *
     * @param session session that owns the registrations
     * @param schema exact evidence schema
     * @param requiredCapabilities nonempty mask which each matching receipt must contain
     * @return immutable registered identities, or empty when none are exposed
     */
    default List<Identity> identities(Session session, String schema, int requiredCapabilities) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(schema, "schema");
        if (schema.isBlank() || requiredCapabilities <= 0 || (requiredCapabilities & ~KNOWN_CAPABILITIES) != 0) {
            throw new IllegalArgumentException("requiredCapabilities/schema");
        }
        return List.of();
    }

    /** Exact schema/mask/identity match, verified against original file bytes again. */
    Optional<Receipt> find(Session session, Identity identity, String schema, int requiredCapabilities)
            throws IOException;

    /**
     * Returns a snapshot of the exact authenticated bytes for a matching receipt.
     * Later file changes do not change a returned snapshot. No payload is retained
     * in the session store, and reading does not execute a plugin or change build decisions.
     *
     * <p>The default implementation preserves existing service implementations by
     * using {@link #find} and then authenticating the bytes it actually reads.
     * Native implementations may combine lookup and snapshot reading.
     *
     * @return an empty result for an absent identity, schema, or required capability
     * @throws IOException if a matching receipt cannot be read or authenticated
     */
    default Optional<Snapshot> read(Session session, Identity identity, String schema, int requiredCapabilities)
            throws IOException {
        Optional<Receipt> receipt = find(session, identity, schema, requiredCapabilities);
        return receipt.isPresent() ? Optional.of(receipt.get().snapshot()) : Optional.empty();
    }

    /**
     * Reads a typed, partial Resources observation from an exactly matched receipt.
     * The existing authenticated snapshot reader owns byte custody; the decoder checks
     * the bounded schema and the embedded context against the invocation identity.
     * This method grants no reuse or output ownership.
     *
     * @param session session that owns the receipt
     * @param identity exact original goal invocation identity
     * @return the immutable observation, or empty when no matching receipt exists
     * @throws IOException if the observation, digest or context does not match
     */
    default Optional<ResourceObservation> findResourceObservation(Session session, Identity identity)
            throws IOException {
        Optional<Snapshot> candidate = read(session, identity, ResourceObservation.SCHEMA, RESOURCES);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        byte[] bytes = candidate.orElseThrow().bytes();
        if (bytes.length > ResourceObservation.MAX_BYTES) {
            throw new IOException("resource observation exceeds byte budget");
        }
        ResourceObservation observation = ResourceObservation.readFrom(new ByteArrayInputStream(bytes));
        if (!identity.contextSha256().equals(observation.configurationSha256())) {
            throw new IOException("resource observation context mismatch");
        }
        return Optional.of(observation);
    }

    /** Revokes a reference without modifying the provider-owned file. */
    void revoke(Session session, Identity identity);

    /** Shared bounded reader; only snapshot requests allocate a payload array. */
    private static byte[] readReceipt(Receipt receipt, boolean capture) throws IOException {
        Path file = receipt.file();
        BasicFileAttributes attr = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attr.isRegularFile() || attr.size() > MAX_RECEIPT_BYTES) {
            throw new IOException("plugin evidence requires a bounded regular source-owned file");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        byte[] contents = capture ? new byte[(int) attr.size()] : null;
        try (InputStream input = Channels.newInputStream(
                Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            byte[] buffer = new byte[16_384];
            long total = 0;
            for (int read; (read = input.read(buffer)) != -1; ) {
                if (read == 0) {
                    throw new IOException("stalled plugin evidence read");
                }
                total += read;
                if (total > MAX_RECEIPT_BYTES) {
                    throw new IOException("plugin evidence byte budget exceeded");
                }
                digest.update(buffer, 0, read);
                if (capture) {
                    if (total > contents.length) {
                        throw new IOException("plugin evidence changed during read");
                    }
                    System.arraycopy(buffer, 0, contents, (int) total - read, read);
                }
            }
            if (total != attr.size()) {
                throw new IOException("plugin evidence changed during read");
            }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(receipt.sha256())) {
            throw new IOException("plugin evidence content digest mismatch");
        }
        return contents;
    }

    private static String name(String text, String field) {
        Objects.requireNonNull(text, field);
        if (text.isBlank() || text.length() > 256 || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field);
        }
        return text;
    }

    private static String sha(String text) {
        Objects.requireNonNull(text, "sha256");
        if (!text.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256");
        }
        return text;
    }
}
