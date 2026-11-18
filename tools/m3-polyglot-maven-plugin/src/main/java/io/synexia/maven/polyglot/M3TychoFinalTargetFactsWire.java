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
 * Java 17 read-only receiver for Tycho's resolved FINAL p2 target evidence.
 * The original Tycho owner must have resolved p2 and written a packet; this
 * class neither resolves p2, downloads artifacts nor starts OSGi.
 *
 * <p>Installable-unit coordinates alone do NOT identify artifact bytes,
 * requirements/wiring, execution environment, selected compiler or outputs.
 * A valid packet is diagnostic evidence only, never cache/goal admission.</p>
 */
public final class M3TychoFinalTargetFactsWire {
    public static final String MAGIC = "M3-TYCHO-FINAL-TARGET-FACTS-V1";
    public static final int MAX_BYTES = 64 * 1024;
    public static final int MAX_UNITS = 262_144;

    private M3TychoFinalTargetFactsWire() { }

    public record Snapshot(String provider, String eclipseProject, String invocation,
            String eclipseSelected, String reactorGroup, String reactorArtifact,
            String reactorVersion, String reactorPackaging, String unitSha256,
            int unitCount, int reactorOriginCount, int mavenOriginCount) {
        public Snapshot {
            if (!"eclipse.tycho".equals(provider)) {
                throw new IllegalArgumentException("unexpected original Tycho provider");
            }
            eclipseProject = valid(eclipseProject, false);
            try {
                if (!UUID.fromString(invocation).toString().equals(invocation)) {
                    throw new IllegalArgumentException("noncanonical original Tycho UUID");
                }
            } catch (IllegalArgumentException bad) {
                throw new IllegalArgumentException("invalid Tycho invocation", bad);
            }
            eclipseSelected = valid(eclipseSelected, true);
            reactorGroup = valid(reactorGroup, false);
            reactorArtifact = valid(reactorArtifact, false);
            reactorVersion = valid(reactorVersion, false);
            reactorPackaging = valid(reactorPackaging, false);
            unitSha256 = sha(unitSha256);
            if (unitCount < 0 || unitCount > MAX_UNITS
                    || reactorOriginCount < 0 || reactorOriginCount > MAX_UNITS
                    || mavenOriginCount < 0 || mavenOriginCount > MAX_UNITS) {
                throw new IllegalArgumentException("Tycho original p2 unit budget exceeded");
            }
        }

        public boolean authorizesBuildSkipping() { return false; }
    }

    public record Assessment(Snapshot original, String eclipseBuildKind,
            boolean originalDeltaComplete) {
        public Assessment {
            Objects.requireNonNull(original, "original");
            eclipseBuildKind = valid(eclipseBuildKind, false);
        }

        public boolean authorizesBuildSkipping() { return false; }

        public String toJson() {
            var f = original;
            StringBuilder json = new StringBuilder("{\"schema\":\"m3-tycho-final-target-facts-v1\"");
            field(json, "provider", f.provider());
            field(json, "project", f.eclipseProject());
            field(json, "invocation", f.invocation());
            field(json, "eclipseSelected", f.eclipseSelected());
            field(json, "reactorGroup", f.reactorGroup());
            field(json, "reactorArtifact", f.reactorArtifact());
            field(json, "reactorVersion", f.reactorVersion());
            field(json, "reactorPackaging", f.reactorPackaging());
            field(json, "unitSha256", f.unitSha256());
            json.append(",\"unitCount\":").append(f.unitCount());
            json.append(",\"reactorOriginCount\":").append(f.reactorOriginCount());
            json.append(",\"mavenOriginCount\":").append(f.mavenOriginCount());
            field(json, "eclipseBuildKind", eclipseBuildKind);
            json.append(",\"originalDeltaComplete\":").append(originalDeltaComplete);
            return json.append(",\"authorizesBuildSkipping\":false}").toString();
        }
    }

    /** File bytes are re-read with NOFOLLOW_LINKS, not trusted from path metadata. */
    public static Snapshot read(Path original) throws IOException {
        Objects.requireNonNull(original, "original");
        if (!Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)
                || Files.size(original) > MAX_BYTES) {
            throw new IOException("Tycho final p2 packet must be a regular file under 64 KiB");
        }
        try (InputStream in = Channels.newInputStream(Files.newByteChannel(original,
                Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            return decode(in.readNBytes(MAX_BYTES + 1));
        }
    }

    public static Snapshot decode(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IOException("Tycho final target packet byte budget exceeded");
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException bad) {
            throw new IOException("non-UTF-8 original Tycho target packet", bad);
        }
        if (!text.endsWith("\n") || text.charAt(0) == '\uFEFF' || text.indexOf('\r') >= 0) {
            throw new IOException("Tycho packet requires canonical LF without BOM");
        }
        String[] rows = text.split("\n", -1);
        if (rows.length != 14 || !MAGIC.equals(rows[0]) || !rows[13].isEmpty()) {
            throw new IOException("unknown Tycho final target schema or row count");
        }
        try {
            return new Snapshot(
                    value(rows[1], "provider"),
                    decode64(value(rows[2], "project64"), false),
                    value(rows[3], "invocation"),
                    decode64(value(rows[4], "eclipseSelected64"), true),
                    decode64(value(rows[5], "reactorGroup64"), false),
                    decode64(value(rows[6], "reactorArtifact64"), false),
                    decode64(value(rows[7], "reactorVersion64"), false),
                    decode64(value(rows[8], "reactorPackaging64"), false),
                    value(rows[9], "unitSha256"),
                    count(value(rows[10], "unitCount")),
                    count(value(rows[11], "reactorOriginCount")),
                    count(value(rows[12], "mavenOriginCount")));
        } catch (IllegalArgumentException bad) {
            throw new IOException("inconsistent original Tycho final target claims", bad);
        }
    }

    /** Only the original Eclipse owner can correlate an exact invocation. */
    public static Assessment bind(Snapshot tycho, M3EclipseBuildHandoffWire.Snapshot eclipse)
            throws IOException {
        Objects.requireNonNull(tycho, "tycho");
        Objects.requireNonNull(eclipse, "eclipse");
        if (!tycho.eclipseProject().equals(eclipse.project())
                || !tycho.invocation().equals(eclipse.invocation())
                || !tycho.eclipseSelected().equals(eclipse.selectedConfiguration())) {
            throw new IOException("foreign Tycho final target facts or Eclipse build generation");
        }
        return new Assessment(tycho, eclipse.buildKind(), eclipse.deltaComplete());
    }

    public static Assessment inspect(Path tycho, Path eclipse) throws IOException {
        return bind(read(tycho), M3EclipseBuildHandoffWire.read(eclipse));
    }

    private static String value(String row, String label) throws IOException {
        String prefix = label + '\t';
        if (!row.startsWith(prefix) || row.indexOf('\t', prefix.length()) >= 0) {
            throw new IOException("malformed Tycho source-owner record: " + label);
        }
        return row.substring(prefix.length());
    }

    private static String decode64(String input, boolean empty) throws IOException {
        if (input.length() > 5500 || !input.matches("[A-Za-z0-9_-]*")) {
            throw new IOException("invalid original Tycho Base64URL token");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(input);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(input)) {
                throw new IOException("noncanonical original Tycho Base64URL");
            }
            return valid(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded)).toString(), empty);
        } catch (CharacterCodingException | IllegalArgumentException bad) {
            throw new IOException("invalid original Tycho UTF-8 metadata", bad);
        }
    }

    private static int count(String input) {
        if (!input.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("noncanonical original Tycho unit count");
        }
        int count = Integer.parseInt(input);
        if (count > MAX_UNITS) {
            throw new IllegalArgumentException("original Tycho p2 unit count exceeded bound");
        }
        return count;
    }

    private static String sha(String hex) {
        if (hex == null || !hex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("noncanonical Tycho original SHA-256");
        }
        return hex;
    }

    private static String valid(String input, boolean empty) {
        Objects.requireNonNull(input, "original Tycho identity");
        if ((!empty && input.isBlank()) || input.length() > 4096
                || input.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid original Tycho identity");
        }
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= input.length() || !Character.isLowSurrogate(input.charAt(i))) {
                    throw new IllegalArgumentException("noncanonical Tycho Unicode");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("noncanonical Tycho Unicode");
            }
        }
        return input;
    }

    private static void field(StringBuilder out, String label, String value) {
        out.append(',');
        quote(out, label);
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
