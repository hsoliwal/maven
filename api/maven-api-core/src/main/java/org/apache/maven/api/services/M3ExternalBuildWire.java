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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import org.apache.maven.api.annotations.Experimental;
import org.apache.maven.api.services.M3ExternalBuildService.Change;
import org.apache.maven.api.services.M3ExternalBuildService.ChangeKind;
import org.apache.maven.api.services.M3ExternalBuildService.Generation;
import org.apache.maven.api.services.M3ExternalBuildService.Request;

/**
 * Maven-owned, versioned, non-authoritative exchange for IDE owner deltas.
 * The document carries no compiler/toolchain hash and never permits a Mojo skip.
 * External providers such as m2e can emit this format without linking Maven 4.
 */
@Experimental
public final class M3ExternalBuildWire {
    public static final String SCHEMA = "M3-IDE-DELTA-V1";
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final String PREFIX_PROVIDER = "provider\t";
    private static final String PREFIX_PROJECT = "project64\t";
    private static final String PREFIX_KIND = "kind\t";
    private static final String PREFIX_COMPLETE = "complete\t";
    private static final String PREFIX_CHANGE = "change\t";

    private M3ExternalBuildWire() {}

    /**
     * Owner-supplied source paths, not an independently authenticated build.
     *
     * @param providerId unique source adapter identifier
     * @param projectId workspace-local owner project identifier
     * @param buildKind FULL, CLEAN, INCREMENTAL or AUTO
     * @param deltaComplete owner's completeness claim; not a Maven assertion
     * @param changes source-owner paths with unknown content digests
     */
    public record Envelope(
            String providerId, String projectId, String buildKind, boolean deltaComplete, List<Change> changes) {
        public Envelope {
            providerId = requireName(providerId, "providerId", 128);
            projectId = requireName(projectId, "projectId", 256);
            buildKind = Objects.requireNonNull(buildKind, "buildKind");
            if (!List.of("FULL", "CLEAN", "INCREMENTAL", "AUTO").contains(buildKind)) {
                throw new IllegalArgumentException("unknown host build kind");
            }
            if (deltaComplete && (buildKind.equals("FULL") || buildKind.equals("CLEAN"))) {
                throw new IllegalArgumentException("FULL/CLEAN cannot assert a complete incremental delta");
            }
            changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
            if (changes.size() > M3ExternalBuildService.MAX_CHANGES) {
                throw new IllegalArgumentException("external delta row budget exceeded");
            }
            String previous = null;
            for (Change change : changes) {
                if (change.contentSha256() != null) {
                    throw new IllegalArgumentException("wire v1 has no owner content hash field");
                }
                if (previous != null && previous.compareTo(change.relativePath()) >= 0) {
                    throw new IllegalArgumentException("external changes must be sorted and unique");
                }
                previous = change.relativePath();
            }
        }
    }

    /** Serializes the bounded original owner claims with strict UTF-8 and LF. */
    public static byte[] encode(Envelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        String project64 = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(envelope.projectId().getBytes(StandardCharsets.UTF_8));
        StringBuilder value = new StringBuilder(SCHEMA)
                .append('\n')
                .append(PREFIX_PROVIDER)
                .append(envelope.providerId())
                .append('\n')
                .append(PREFIX_PROJECT)
                .append(project64)
                .append('\n')
                .append(PREFIX_KIND)
                .append(envelope.buildKind())
                .append('\n')
                .append(PREFIX_COMPLETE)
                .append(envelope.deltaComplete())
                .append('\n');
        for (Change change : envelope.changes()) {
            value.append(PREFIX_CHANGE)
                    .append(change.kind())
                    .append('\t')
                    .append(change.relativePath())
                    .append('\n');
        }
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("external delta wire byte budget exceeded");
        }
        return bytes;
    }

    /** Refuses malformed, unsorted or noncanonical external source-owner packets. */
    public static Envelope decode(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_BYTES) {
            throw new IOException("external delta wire byte budget exceeded");
        }
        final String content;
        try {
            content = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("external delta wire is not UTF-8", invalid);
        }
        if (!content.endsWith("\n") || content.indexOf('\r') >= 0) {
            throw new IOException("external delta wire requires LF and final newline");
        }
        String[] lines = content.split("\n", -1);
        if (lines.length < 6 || !SCHEMA.equals(lines[0])) {
            throw new IOException("unsupported external delta wire schema");
        }
        String provider = field(lines[1], PREFIX_PROVIDER);
        String encodedProject = field(lines[2], PREFIX_PROJECT);
        String kind = field(lines[3], PREFIX_KIND);
        String complete = field(lines[4], PREFIX_COMPLETE);
        if (!(complete.equals("true") || complete.equals("false"))) {
            throw new IOException("noncanonical delta completeness flag");
        }
        String project;
        try {
            byte[] raw = Base64.getUrlDecoder().decode(encodedProject);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(encodedProject)) {
                throw new IllegalArgumentException("noncanonical project identifier encoding");
            }
            project = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            throw new IOException("invalid external project encoding", invalid);
        }
        List<Change> changes = new ArrayList<>(lines.length - 6);
        for (int index = 5; index < lines.length - 1; index++) {
            String line = lines[index];
            if (!line.startsWith(PREFIX_CHANGE)) {
                throw new IOException("noncanonical external delta row");
            }
            String[] row = line.split("\t", -1);
            if (row.length != 3) {
                throw new IOException("external delta row requires kind and path");
            }
            try {
                changes.add(new Change(row[2], ChangeKind.valueOf(row[1]), null));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("invalid external delta change", invalid);
            }
        }
        try {
            return new Envelope(provider, project, kind, Boolean.parseBoolean(complete), changes);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid external delta envelope", invalid);
        }
    }

    /**
     * Attaches separately verified build-context facts; unknown/incomplete owner
     * change coverage always forces reconciliation in M3ExternalBuildService.
     */
    public static Request bind(Envelope envelope, Generation previous, Generation current) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(current, "current");
        if (!envelope.providerId().equals(current.providerId())
                || !envelope.projectId().equals(current.projectId())) {
            throw new IllegalArgumentException("foreign external build generation");
        }
        Generation narrowed = new Generation(
                current.providerId(),
                current.projectId(),
                current.generation(),
                current.modelSha256(),
                current.toolchainSha256(),
                current.dependenciesSha256(),
                current.sourceRootsSha256(),
                current.deltaComplete() && envelope.deltaComplete());
        return new Request(previous, narrowed, envelope.changes());
    }

    private static String field(String line, String prefix) throws IOException {
        if (!line.startsWith(prefix)) {
            throw new IOException("unexpected external delta field");
        }
        return line.substring(prefix.length());
    }

    private static String requireName(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()
                || value.length() > maxLength
                || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + name);
        }
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (Character.isHighSurrogate(ch)) {
                if (++index == value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new IllegalArgumentException("malformed " + name);
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("malformed " + name);
            }
        }
        return value;
    }
}
