/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Portable Java 17 verifier for the original Corrosion/Cargo offline,
 * locked source-owner evidence. Neither Maven nor Gradle invokes Cargo,
 * re-resolves Rust features or interprets foreign rust-analyzer state.
 *
 * <p>These hashes cover reported native Cargo metadata bytes and selected
 * project-local manifests. A matching source-owner invocation is necessary
 * for accepting diagnostics but is NOT sufficient for remote/local build
 * cache reuse, compilation skipping or output provenance.</p>
 */
public final class M3CargoBuildEvidenceWire {
    public static final String MAGIC = "M3-CARGO-BUILD-FACTS-V1";
    public static final int MAX_BYTES = 64 * 1024;
    public static final long MAX_METADATA_BYTES = 16L * 1024 * 1024;

    private M3CargoBuildEvidenceWire() { }

    public record Snapshot(String provider, String project, String invocation,
            String eclipseSelected, String manifestSha256, String lockSha256,
            String cargoConfigSha256, String rustToolchainSha256,
            String rustToolchainTomlSha256, String cargoMetadataSha256,
            long metadataBytes, String cargoCommandSha256) {
        public Snapshot {
            if (!"eclipse.corrosion".equals(provider)) {
                throw new IllegalArgumentException("foreign Cargo facts provider");
            }
            project = valid(project, false);
            eclipseSelected = valid(eclipseSelected, true);
            try {
                if (!UUID.fromString(invocation).toString().equals(invocation)) {
                    throw new IllegalArgumentException("noncanonical Cargo invocation ID");
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("invalid Eclipse Cargo invocation", invalid);
            }
            manifestSha256 = sha(manifestSha256, false);
            lockSha256 = sha(lockSha256, false);
            cargoConfigSha256 = sha(cargoConfigSha256, true);
            rustToolchainSha256 = sha(rustToolchainSha256, true);
            rustToolchainTomlSha256 = sha(rustToolchainTomlSha256, true);
            cargoMetadataSha256 = sha(cargoMetadataSha256, false);
            cargoCommandSha256 = sha(cargoCommandSha256, false);
            if (metadataBytes < 1 || metadataBytes > MAX_METADATA_BYTES) {
                throw new IllegalArgumentException("invalid Cargo metadata length");
            }
        }

        public boolean authorizesBuildSkipping() { return false; }
    }

    public record Assessment(Snapshot facts, String eclipseBuildKind,
            boolean originalDeltaComplete) {
        public Assessment {
            Objects.requireNonNull(facts, "facts");
            eclipseBuildKind = valid(eclipseBuildKind, false);
        }

        /** Stable diagnostic JSON, not a permission to execute/skip Cargo. */
        public String toJson() {
            var f = facts;
            StringBuilder out = new StringBuilder("{\"schema\":\"m3-cargo-build-facts-v1\"");
            field(out, "provider", f.provider());
            field(out, "project", f.project());
            field(out, "invocation", f.invocation());
            field(out, "eclipseSelected", f.eclipseSelected());
            field(out, "manifestSha256", f.manifestSha256());
            field(out, "lockSha256", f.lockSha256());
            field(out, "cargoConfigSha256", f.cargoConfigSha256());
            field(out, "rustToolchainSha256", f.rustToolchainSha256());
            field(out, "rustToolchainTomlSha256", f.rustToolchainTomlSha256());
            field(out, "cargoMetadataSha256", f.cargoMetadataSha256());
            out.append(",\"metadataBytes\":").append(f.metadataBytes());
            field(out, "cargoCommandSha256", f.cargoCommandSha256());
            field(out, "eclipseBuildKind", eclipseBuildKind);
            out.append(",\"originalDeltaComplete\":").append(originalDeltaComplete);
            return out.append(",\"authorizesBuildSkipping\":false}").toString();
        }

        public boolean authorizesBuildSkipping() { return false; }
    }

    public static Snapshot read(Path original) throws IOException {
        Objects.requireNonNull(original, "original");
        if (!Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)
                || Files.size(original) > MAX_BYTES) {
            throw new IOException("Cargo facts must be a source-owner regular file under 64 KiB");
        }
        try (InputStream input = Channels.newInputStream(Files.newByteChannel(original,
                Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            return decode(input.readNBytes(MAX_BYTES + 1));
        }
    }

    /** Strict version, row count, LF, UTF-8, Base64URL and source-owner bounds. */
    public static Snapshot decode(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IOException("Cargo source-owner packet byte budget exceeded");
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("non-UTF-8 Cargo source-owner packet", invalid);
        }
        if (!text.endsWith("\n") || text.charAt(0) == '\uFEFF' || text.indexOf('\r') >= 0) {
            throw new IOException("Cargo packet requires LF-only and no BOM");
        }
        String[] rows = text.split("\n", -1);
        if (rows.length != 14 || !MAGIC.equals(rows[0]) || !rows[13].isEmpty()) {
            throw new IOException("unsupported Cargo owner packet version or row count");
        }
        try {
            return new Snapshot(
                    value(rows[1], "provider"),
                    decode64(value(rows[2], "project64"), false),
                    value(rows[3], "invocation"),
                    decode64(value(rows[4], "eclipseSelected64"), true),
                    value(rows[5], "manifestSha256"),
                    value(rows[6], "lockSha256"),
                    value(rows[7], "cargoConfigSha256"),
                    value(rows[8], "rustToolchainSha256"),
                    value(rows[9], "rustToolchainTomlSha256"),
                    value(rows[10], "cargoMetadataSha256"),
                    count(value(rows[11], "metadataBytes")),
                    value(rows[12], "cargoCommandSha256"));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("inconsistent original Cargo source-owner metadata", invalid);
        }
    }

    /** Reject project, selected Eclipse configuration or invocation mismatch. */
    public static Assessment bind(Snapshot rust, M3EclipseBuildHandoffWire.Snapshot eclipse)
            throws IOException {
        Objects.requireNonNull(rust, "rust");
        Objects.requireNonNull(eclipse, "eclipse");
        if (!rust.project().equals(eclipse.project())
                || !rust.invocation().equals(eclipse.invocation())
                || !rust.eclipseSelected().equals(eclipse.selectedConfiguration())) {
            throw new IOException("foreign Cargo facts or original Eclipse build generation");
        }
        return new Assessment(rust, eclipse.buildKind(), eclipse.deltaComplete());
    }

    public static Assessment inspect(Path cargoFacts, Path eclipseHandoff) throws IOException {
        return bind(read(cargoFacts), M3EclipseBuildHandoffWire.read(eclipseHandoff));
    }

    private static String value(String row, String label) throws IOException {
        String prefix = label + '\t';
        if (!row.startsWith(prefix) || row.indexOf('\t', prefix.length()) >= 0) {
            throw new IOException("missing/invalid original Cargo owner field: " + label);
        }
        return row.substring(prefix.length());
    }

    private static String decode64(String text, boolean empty) throws IOException {
        if (text.length() > 5500 || !text.matches("[A-Za-z0-9_-]*")) {
            throw new IOException("invalid original Cargo Base64URL");
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(text);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(text)) {
                throw new IOException("noncanonical original Cargo Base64URL");
            }
            return valid(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString(), empty);
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            throw new IOException("invalid source-owner Cargo UTF-8 field", invalid);
        }
    }

    private static long count(String value) {
        if (!value.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("noncanonical Cargo metadata byte count");
        }
        long parsed = Long.parseLong(value);
        if (parsed > MAX_METADATA_BYTES) {
            throw new IllegalArgumentException("Cargo metadata exceeds byte budget");
        }
        return parsed;
    }

    private static String sha(String value, boolean optional) {
        if (optional && "absent".equals(value)) return value;
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("noncanonical original Cargo SHA-256");
        }
        return value;
    }

    private static String valid(String value, boolean empty) {
        Objects.requireNonNull(value, "original Cargo identity");
        if ((!empty && value.isBlank()) || value.length() > 4096
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid Cargo identity");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("invalid Cargo Unicode");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("invalid Cargo Unicode");
            }
        }
        return value;
    }

    private static void field(StringBuilder out, String name, String value) {
        out.append(',');
        quote(out, name);
        out.append(':');
        quote(out, value);
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) {
                            out.append(Character.forDigit((c >>> shift) & 15, 16));
                        }
                    } else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
