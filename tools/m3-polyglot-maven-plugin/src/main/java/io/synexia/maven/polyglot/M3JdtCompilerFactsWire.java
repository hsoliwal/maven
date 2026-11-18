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
 * Pure Java 17 consumer for a JDT-owned compiler configuration receipt.
 * JDT remains the authority for classpath resolution and compiler options;
 * Maven and Gradle neither parse Java source nor execute ECJ here.
 *
 * <p>SHA-256 fields are original-owner claims about observed JDT model
 * metadata. They do not prove input coverage, classpath artifact bytes,
 * effective toolchain identity, successful compilation, or cache eligibility.
 * The original Eclipse invocation must match exactly, or the receipt fails.</p>
 */
public final class M3JdtCompilerFactsWire {
    public static final String MAGIC = "M3-JDT-COMPILER-FACTS-V1";
    public static final int MAX_BYTES = 16 * 1024;
    public static final int MAX_CLASSPATH = 16_384;

    private M3JdtCompilerFactsWire() { }

    public record Snapshot(String provider, String project, String invocation,
            String selectedConfiguration, String outputLocation,
            String rawClasspathSha256, String resolvedClasspathSha256,
            String compilerOptionsSha256, int rawCount, int resolvedCount) {
        public Snapshot {
            if (!"eclipse.jdt".equals(provider)) {
                throw new IllegalArgumentException("unknown JDT facts owner");
            }
            project = valid(project, false);
            selectedConfiguration = valid(selectedConfiguration, true);
            outputLocation = valid(outputLocation, false);
            try {
                if (!UUID.fromString(invocation).toString().equals(invocation)) {
                    throw new IllegalArgumentException("noncanonical JDT invocation");
                }
            } catch (RuntimeException bad) {
                throw new IllegalArgumentException("invalid JDT invocation UUID", bad);
            }
            rawClasspathSha256 = sha(rawClasspathSha256);
            resolvedClasspathSha256 = sha(resolvedClasspathSha256);
            compilerOptionsSha256 = sha(compilerOptionsSha256);
            if (rawCount < 0 || resolvedCount < 0
                    || rawCount > MAX_CLASSPATH || resolvedCount > MAX_CLASSPATH) {
                throw new IllegalArgumentException("JDT classpath count exceeds bounds");
            }
        }
        /** Never authorizes Maven or Gradle to skip work. */
        public boolean authorizesBuildSkipping() { return false; }
    }

    /** The validated JDT claim and its original Eclipse source-owner context. */
    public record Assessment(Snapshot facts, String eclipseBuildKind,
            boolean originalDeltaComplete) {
        public Assessment {
            Objects.requireNonNull(facts, "facts");
            eclipseBuildKind = valid(eclipseBuildKind, false);
        }

        /** Stable diagnostics, not a build-cache decision. */
        public String toJson() {
            Snapshot f = facts;
            StringBuilder out = new StringBuilder("{\"schema\":\"m3-jdt-compiler-facts-v1\"");
            field(out, "provider", f.provider());
            field(out, "project", f.project());
            field(out, "invocation", f.invocation());
            field(out, "selectedConfiguration", f.selectedConfiguration());
            field(out, "outputLocation", f.outputLocation());
            field(out, "rawClasspathSha256", f.rawClasspathSha256());
            field(out, "resolvedClasspathSha256", f.resolvedClasspathSha256());
            field(out, "compilerOptionsSha256", f.compilerOptionsSha256());
            out.append(",\"rawCount\":").append(f.rawCount());
            out.append(",\"resolvedCount\":").append(f.resolvedCount());
            field(out, "eclipseBuildKind", eclipseBuildKind);
            out.append(",\"originalDeltaComplete\":").append(originalDeltaComplete);
            return out.append(",\"authorizesBuildSkipping\":false}").toString();
        }
    }

    /** Existing file is consumed once with no symbolic-link following. */
    public static Snapshot read(Path packet) throws IOException {
        Objects.requireNonNull(packet, "packet");
        if (!Files.isRegularFile(packet, LinkOption.NOFOLLOW_LINKS)
                || Files.size(packet) > MAX_BYTES) {
            throw new IOException("JDT original-owner packet must be a regular file under 16 KiB");
        }
        try (InputStream in = Channels.newInputStream(Files.newByteChannel(packet,
                Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            return decode(in.readNBytes(MAX_BYTES + 1));
        }
    }

    public static Snapshot decode(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IOException("JDT facts packet byte budget exceeded");
        }
        final String body;
        try {
            body = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException bad) {
            throw new IOException("JDT owner packet is not UTF-8", bad);
        }
        if (!body.endsWith("\n") || body.charAt(0) == '\uFEFF'
                || body.indexOf('\r') >= 0) {
            throw new IOException("JDT owner packet requires LF and no BOM");
        }
        String[] rows = body.split("\n", -1);
        if (rows.length != 12 || !MAGIC.equals(rows[0]) || !rows[11].isEmpty()) {
            throw new IOException("unsupported JDT owner packet schema or row count");
        }
        try {
            return new Snapshot(
                    fieldValue(rows[1], "provider"),
                    decode64(fieldValue(rows[2], "project64"), false),
                    fieldValue(rows[3], "invocation"),
                    decode64(fieldValue(rows[4], "selected64"), true),
                    decode64(fieldValue(rows[5], "output64"), false),
                    fieldValue(rows[6], "rawSha256"),
                    fieldValue(rows[7], "resolvedSha256"),
                    fieldValue(rows[8], "optionsSha256"),
                    boundedCount(fieldValue(rows[9], "rawCount")),
                    boundedCount(fieldValue(rows[10], "resolvedCount")));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid original JDT compiler facts", invalid);
        }
    }

    /** Reject cross-project, cross-configuration, or cross-invocation splicing. */
    public static Assessment bind(Snapshot facts, M3EclipseBuildHandoffWire.Snapshot handoff)
            throws IOException {
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(handoff, "handoff");
        if (!facts.project().equals(handoff.project())
                || !facts.invocation().equals(handoff.invocation())
                || !facts.selectedConfiguration().equals(handoff.selectedConfiguration())) {
            throw new IOException("foreign JDT compiler facts or Eclipse build generation");
        }
        return new Assessment(facts, handoff.buildKind(), handoff.deltaComplete());
    }

    public static Assessment inspect(Path facts, Path eclipseHandoff) throws IOException {
        return bind(read(facts), M3EclipseBuildHandoffWire.read(eclipseHandoff));
    }

    private static int boundedCount(String number) {
        if (!number.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("noncanonical JDT entry count");
        }
        int value = Integer.parseInt(number);
        if (value > MAX_CLASSPATH) {
            throw new IllegalArgumentException("JDT entry count exceeds limit");
        }
        return value;
    }

    private static String fieldValue(String row, String key) throws IOException {
        String prefix = key + '\t';
        if (!row.startsWith(prefix) || row.indexOf('\t', prefix.length()) >= 0) {
            throw new IOException("malformed original JDT field: " + key);
        }
        return row.substring(prefix.length());
    }

    private static String decode64(String encoded, boolean empty) throws IOException {
        if (encoded.length() > 5500 || !encoded.matches("[A-Za-z0-9_-]*")) {
            throw new IOException("noncanonical JDT metadata encoding");
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(encoded);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(encoded)) {
                throw new IOException("noncanonical JDT metadata base64");
            }
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
            return valid(value, empty);
        } catch (CharacterCodingException | IllegalArgumentException bad) {
            throw new IOException("invalid UTF-8 in JDT metadata", bad);
        }
    }

    private static String sha(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("noncanonical JDT metadata digest");
        }
        return value;
    }

    private static String valid(String value, boolean empty) {
        Objects.requireNonNull(value, "JDT metadata");
        if ((!empty && value.isBlank()) || value.length() > 4096
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid original JDT metadata");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("malformed JDT identity");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("malformed JDT identity");
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
