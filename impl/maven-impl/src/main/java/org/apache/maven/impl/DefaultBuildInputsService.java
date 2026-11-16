/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.apache.maven.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.api.services.BuildInputsService;
import org.apache.maven.api.services.M3PluginEvidenceService;

/** Strict parser for existing, source-bound Maven resource goal evidence. */
@Named
@Singleton
public final class DefaultBuildInputsService implements BuildInputsService {
    private static final int MAX_ROWS = 65_536;
    private static final int MAX_LINE_LENGTH = 2_048;
    private static final String MAGIC = "M3-RESOURCE-IO-V1";
    private static final String HEADER = "role\tpath\tsha256\tbytes";

    private final M3PluginEvidenceService evidence;

    @Inject
    public DefaultBuildInputsService(M3PluginEvidenceService evidence) {
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    @Override
    public Optional<Manifest> read(Session session, M3PluginEvidenceService.Identity identity) throws IOException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(identity, "identity");
        Optional<M3PluginEvidenceService.Snapshot> found = evidence.read(session, identity, SCHEMA, CAPABILITIES);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        M3PluginEvidenceService.Snapshot snapshot = found.orElseThrow();
        M3PluginEvidenceService.Receipt receipt = snapshot.receipt();
        if (!receipt.identity().equals(identity)
                || !SCHEMA.equals(receipt.schema())
                || (receipt.capabilities() & CAPABILITIES) != CAPABILITIES) {
            throw new IOException("resource inventory receipt does not match the requested source");
        }
        return Optional.of(parse(receipt, snapshot.bytes()));
    }

    private static Manifest parse(M3PluginEvidenceService.Receipt receipt, byte[] contents) throws IOException {
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(contents))
                    .toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("resource inventory is not valid UTF-8", malformed);
        }
        Parsed rows = new Parsed();
        int line = 0;
        for (int start = 0; start < text.length(); ) {
            int end = text.indexOf('\n', start);
            if (end < 0 || end - start > MAX_LINE_LENGTH) {
                throw new IOException("unterminated or oversized resource manifest row");
            }
            String value = text.substring(start, end);
            if (value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
                throw new IOException("noncanonical resource manifest line ending");
            }
            if (line == 0) {
                if (!MAGIC.equals(value)) {
                    throw new IOException("unexpected resource manifest format");
                }
            } else if (line == 1) {
                if (!HEADER.equals(value)) {
                    throw new IOException("unexpected resource manifest columns");
                }
            } else {
                rows.add(value);
            }
            line++;
            start = end + 1;
        }
        if (line < 2) {
            throw new IOException("missing resource manifest header");
        }
        return new Packed(receipt, rows);
    }

    private static final class Parsed {
        private byte[] direction = new byte[8];
        private String[] paths = new String[8];
        private String[] hashes = new String[8];
        private long[] bytes = new long[8];
        private int count;
        private int inputs;
        private int lastRole = -1;
        private String lastPath;

        private void add(String value) throws IOException {
            if (count == MAX_ROWS) {
                throw new IOException("resource manifest row budget exceeded");
            }
            String[] fields = value.split("\t", -1);
            if (fields.length != 4) {
                throw new IOException("resource manifest requires four columns");
            }
            int role = switch (fields[0]) {
                case "I" -> 0;
                case "O" -> 1;
                default -> throw new IOException("unknown resource manifest role");
            };
            String path = fields[1];
            validatePath(path);
            String hash = fields[2];
            if (hash.length() != 64) {
                throw new IOException("resource manifest digest length");
            }
            for (int i = 0; i < hash.length(); i++) {
                char c = hash.charAt(i);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                    throw new IOException("resource manifest requires lowercase SHA-256");
                }
            }
            String countText = fields[3];
            if (countText.isEmpty() || (countText.length() > 1 && countText.charAt(0) == '0')) {
                throw new IOException("noncanonical resource byte count");
            }
            for (int i = 0; i < countText.length(); i++) {
                if (countText.charAt(i) < '0' || countText.charAt(i) > '9') {
                    throw new IOException("noncanonical resource byte count");
                }
            }
            final long size;
            try {
                size = Long.parseLong(countText);
            } catch (NumberFormatException overflow) {
                throw new IOException("resource byte count overflow", overflow);
            }
            if (role < lastRole || (role == lastRole && path.compareTo(lastPath) <= 0)) {
                throw new IOException("duplicate or unordered resource manifest rows");
            }
            if (count == paths.length) {
                int capacity = Math.min(MAX_ROWS, paths.length * 2);
                direction = Arrays.copyOf(direction, capacity);
                paths = Arrays.copyOf(paths, capacity);
                hashes = Arrays.copyOf(hashes, capacity);
                bytes = Arrays.copyOf(bytes, capacity);
            }
            direction[count] = (byte) role;
            paths[count] = path;
            hashes[count] = hash;
            bytes[count] = size;
            count++;
            if (role == 0) {
                inputs++;
            }
            lastRole = role;
            lastPath = path;
        }

        private static void validatePath(String path) throws IOException {
            if (path.isEmpty() || path.length() > 1_024 || path.charAt(0) == '/' || path.indexOf('\\') >= 0
                    || path.indexOf(':') >= 0) {
                throw new IOException("unsafe resource manifest path");
            }
            String[] segments = path.split("/", -1);
            for (String segment : segments) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                    throw new IOException("noncanonical resource manifest path");
                }
            }
            for (int i = 0; i < path.length(); i++) {
                if (Character.isISOControl(path.charAt(i))) {
                    throw new IOException("resource manifest path contains control character");
                }
            }
        }
    }

    private static final class Packed implements Manifest {
        private final M3PluginEvidenceService.Receipt receipt;
        private final int count;
        private final int inputs;
        private final byte[] direction;
        private final String[] paths;
        private final String[] hashes;
        private final long[] bytes;

        private Packed(M3PluginEvidenceService.Receipt receipt, Parsed rows) {
            this.receipt = receipt;
            this.count = rows.count;
            this.inputs = rows.inputs;
            this.direction = Arrays.copyOf(rows.direction, count);
            this.paths = Arrays.copyOf(rows.paths, count);
            this.hashes = Arrays.copyOf(rows.hashes, count);
            this.bytes = Arrays.copyOf(rows.bytes, count);
        }

        @Override
        public M3PluginEvidenceService.Receipt receipt() {
            return receipt;
        }

        @Override
        public int size() {
            return count;
        }

        @Override
        public int inputCount() {
            return inputs;
        }

        @Override
        public int outputCount() {
            return count - inputs;
        }

        @Override
        public FileFact entry(int index) {
            Objects.checkIndex(index, count);
            return new FileFact(direction[index] == 0 ? Direction.INPUT : Direction.OUTPUT,
                    paths[index], hashes[index], bytes[index]);
        }
    }
}
