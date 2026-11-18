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
 * Pure Java 17 verifier of original Tycho FINAL p2 platform identity.
 * Maven/Gradle never resolve p2, download artifacts or interpret a .target
 * declaration in place of Tycho's actual final target platform.
 *
 * <p>The IU identity digest is only observed metadata. It omits actual
 * artifact byte hashes, filter/requirement and bundle wiring closure, EE
 * packages, generated outputs and build successes. It is never a cache key
 * or authorization to skip native Maven/Tycho, PDE or Gradle work.</p>
 */
public final class M3TychoFinalP2FactsWire {
    public static final String MAGIC = "M3-TYCHO-FINAL-P2-FACTS-V1";
    public static final int MAX_BYTES = 16 * 1024;
    public static final int MAX_UNITS = 262_144;
    private M3TychoFinalP2FactsWire() { }

    public record Snapshot(String provider, String groupId, String artifactId,
            String version, String packaging, String eclipseProject,
            String invocation, String eclipseSelected, String os, String ws,
            String arch, String stage, int unitCount, String unitsSha256) {
        public Snapshot {
            if (!"tycho.p2".equals(provider) || !"final".equals(stage)) {
                throw new IllegalArgumentException("p2 source must be the original Tycho FINAL platform");
            }
            groupId = valid(groupId, false);
            artifactId = valid(artifactId, false);
            version = valid(version, false);
            packaging = valid(packaging, false);
            eclipseProject = valid(eclipseProject, false);
            eclipseSelected = valid(eclipseSelected, true);
            os = valid(os, false);
            ws = valid(ws, false);
            arch = valid(arch, false);
            try {
                if (!UUID.fromString(invocation).toString().equals(invocation)) {
                    throw new IllegalArgumentException("noncanonical Tycho invocation");
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("invalid original Eclipse invocation", invalid);
            }
            if (unitCount < 0 || unitCount > MAX_UNITS
                    || unitsSha256 == null || !unitsSha256.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("invalid final p2 unit identity");
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

        public String toJson() {
            var f = facts;
            StringBuilder out = new StringBuilder("{\"schema\":\"m3-tycho-final-p2-facts-v1\"");
            field(out, "provider", f.provider());
            field(out, "groupId", f.groupId());
            field(out, "artifactId", f.artifactId());
            field(out, "version", f.version());
            field(out, "packaging", f.packaging());
            field(out, "eclipseProject", f.eclipseProject());
            field(out, "invocation", f.invocation());
            field(out, "eclipseSelected", f.eclipseSelected());
            field(out, "os", f.os());
            field(out, "ws", f.ws());
            field(out, "arch", f.arch());
            field(out, "stage", f.stage());
            out.append(",\"unitCount\":").append(f.unitCount());
            field(out, "unitsSha256", f.unitsSha256());
            field(out, "eclipseBuildKind", eclipseBuildKind);
            out.append(",\"originalDeltaComplete\":").append(originalDeltaComplete);
            return out.append(",\"authorizesBuildSkipping\":false}").toString();
        }

        public boolean authorizesBuildSkipping() { return false; }
    }

    public static Snapshot read(Path packet) throws IOException {
        Objects.requireNonNull(packet, "packet");
        if (!Files.isRegularFile(packet, LinkOption.NOFOLLOW_LINKS)
                || Files.size(packet) > MAX_BYTES) {
            throw new IOException("Tycho final p2 source-owner packet must be a regular file under 16 KiB");
        }
        try (InputStream in = Channels.newInputStream(Files.newByteChannel(packet,
                Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            return decode(in.readNBytes(MAX_BYTES + 1));
        }
    }

    public static Snapshot decode(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IOException("Tycho final p2 packet byte budget exceeded");
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("Tycho final p2 source packet is not UTF-8", invalid);
        }
        if (!text.endsWith("\n") || text.charAt(0) == '\uFEFF' || text.indexOf('\r') >= 0) {
            throw new IOException("Tycho final p2 packet requires LF and no BOM");
        }
        String[] rows = text.split("\n", -1);
        if (rows.length != 16 || !MAGIC.equals(rows[0]) || !rows[15].isEmpty()) {
            throw new IOException("unsupported Tycho final p2 packet version or record count");
        }
        try {
            return new Snapshot(
                    fieldValue(rows[1], "provider"),
                    decode64(fieldValue(rows[2], "group64"), false),
                    decode64(fieldValue(rows[3], "artifact64"), false),
                    decode64(fieldValue(rows[4], "version64"), false),
                    decode64(fieldValue(rows[5], "packaging64"), false),
                    decode64(fieldValue(rows[6], "eclipseProject64"), false),
                    fieldValue(rows[7], "invocation"),
                    decode64(fieldValue(rows[8], "eclipseSelected64"), true),
                    decode64(fieldValue(rows[9], "os64"), false),
                    decode64(fieldValue(rows[10], "ws64"), false),
                    decode64(fieldValue(rows[11], "arch64"), false),
                    fieldValue(rows[12], "stage"),
                    count(fieldValue(rows[13], "unitCount")),
                    fieldValue(rows[14], "unitsSha256"));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("inconsistent original Tycho final target facts", invalid);
        }
    }

    /** Never equate Maven artifact ID with Eclipse .project name. */
    public static Assessment bind(Snapshot tycho, M3EclipseBuildHandoffWire.Snapshot eclipse)
            throws IOException {
        Objects.requireNonNull(tycho, "tycho");
        Objects.requireNonNull(eclipse, "eclipse");
        if (!tycho.eclipseProject().equals(eclipse.project())
                || !tycho.invocation().equals(eclipse.invocation())
                || !tycho.eclipseSelected().equals(eclipse.selectedConfiguration())) {
            throw new IOException("foreign Tycho final p2 evidence or Eclipse build generation");
        }
        return new Assessment(tycho, eclipse.buildKind(), eclipse.deltaComplete());
    }

    public static Assessment inspect(Path tychoFacts, Path eclipseHandoff) throws IOException {
        return bind(read(tychoFacts), M3EclipseBuildHandoffWire.read(eclipseHandoff));
    }

    private static int count(String value) {
        if (!value.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("noncanonical final p2 unit count");
        }
        int number = Integer.parseInt(value);
        if (number > MAX_UNITS) {
            throw new IllegalArgumentException("original final p2 IU count exceeds bound");
        }
        return number;
    }

    private static String fieldValue(String row, String key) throws IOException {
        String prefix = key + '\t';
        if (!row.startsWith(prefix) || row.indexOf('\t', prefix.length()) >= 0) {
            throw new IOException("missing or malformed original Tycho record: " + key);
        }
        return row.substring(prefix.length());
    }

    private static String decode64(String encoded, boolean empty) throws IOException {
        if (encoded.length() > 5500 || !encoded.matches("[A-Za-z0-9_-]*")) {
            throw new IOException("noncanonical Tycho Base64URL metadata");
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(encoded);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(encoded)) {
                throw new IOException("noncanonical Tycho Base64URL");
            }
            return valid(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString(), empty);
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            throw new IOException("invalid original Tycho UTF-8 field", invalid);
        }
    }

    private static String valid(String value, boolean empty) {
        Objects.requireNonNull(value, "Tycho original identity");
        if ((!empty && value.isBlank()) || value.length() > 4096
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid original Tycho source owner identity");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("invalid Tycho Unicode");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("invalid Tycho Unicode");
            }
        }
        return value;
    }

    private static void field(StringBuilder out, String key, String value) {
        out.append(',');
        quote(out, key);
        out.append(':');
        quote(out, value);
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
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
