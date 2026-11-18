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
 * Read-only Java 17 receiver for source-owned CDT native scanner facts.
 * Neither Maven nor Gradle may infer C/C++ compiler argv from a .cproject,
 * compile_commands.json or this advisory evidence packet.
 */
public final class M3CdtCompilerFactsWire {
    public static final String MAGIC = "M3-CDT-COMPILER-FACTS-V1";
    public static final int MAX_BYTES = 64 * 1024;
    public static final int MAX_RESOURCES = 8192;
    public static final int MAX_LANGUAGES = MAX_RESOURCES * 64;
    public static final int MAX_ENTRIES = 262_144;

    private M3CdtCompilerFactsWire() { }

    public record Snapshot(String provider, String project, String invocation,
            String eclipseSelected, String cdtConfigId, String cdtConfigName,
            String cdtActiveConfigId, String buildSystemId, String scannerSha256,
            String sourcesSha256, String buildSettingSha256, int resources,
            int languages, int entries) {
        public Snapshot {
            if (!"eclipse.cdt".equals(provider)) {
                throw new IllegalArgumentException("unexpected CDT original facts provider");
            }
            project = valid(project, false);
            invocation = valid(invocation, false);
            try {
                if (!UUID.fromString(invocation).toString().equals(invocation)) {
                    throw new IllegalArgumentException("noncanonical CDT invocation");
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("invalid CDT build invocation", invalid);
            }
            eclipseSelected = valid(eclipseSelected, true);
            cdtConfigId = valid(cdtConfigId, false);
            cdtConfigName = valid(cdtConfigName, false);
            cdtActiveConfigId = valid(cdtActiveConfigId, false);
            buildSystemId = valid(buildSystemId, false);
            scannerSha256 = sha(scannerSha256);
            sourcesSha256 = sha(sourcesSha256);
            buildSettingSha256 = sha(buildSettingSha256);
            if (resources < 0 || resources > MAX_RESOURCES
                    || languages < 0 || languages > MAX_LANGUAGES
                    || entries < 0 || entries > MAX_ENTRIES) {
                throw new IllegalArgumentException("CDT metadata count budget exceeded");
            }
        }

        /** No original CDT compiler result or source tree is authenticated here. */
        public boolean authorizesBuildSkipping() { return false; }
    }

    public record Assessment(Snapshot facts, String eclipseBuildKind,
            boolean originalDeltaComplete) {
        public Assessment { Objects.requireNonNull(facts, "facts"); }

        public String toJson() {
            Snapshot f = facts;
            StringBuilder out = new StringBuilder("{\"schema\":\"m3-cdt-compiler-facts-v1\"");
            field(out, "provider", f.provider());
            field(out, "project", f.project());
            field(out, "invocation", f.invocation());
            field(out, "eclipseSelected", f.eclipseSelected());
            field(out, "cdtConfigId", f.cdtConfigId());
            field(out, "cdtConfigName", f.cdtConfigName());
            field(out, "cdtActiveConfigId", f.cdtActiveConfigId());
            field(out, "buildSystemId", f.buildSystemId());
            field(out, "scannerSha256", f.scannerSha256());
            field(out, "sourcesSha256", f.sourcesSha256());
            field(out, "buildSettingSha256", f.buildSettingSha256());
            out.append(",\"resourceCount\":").append(f.resources());
            out.append(",\"languageCount\":").append(f.languages());
            out.append(",\"entryCount\":").append(f.entries());
            field(out, "eclipseBuildKind", eclipseBuildKind);
            out.append(",\"originalDeltaComplete\":").append(originalDeltaComplete);
            return out.append(",\"authorizesBuildSkipping\":false}").toString();
        }

        /** Advisory evidence cannot be treated as an artifact cache receipt. */
        public boolean authorizesBuildSkipping() { return false; }
    }

    /** Canonical strict UTF-8/LF original CDT packet with no symlink following. */
    public static Snapshot read(Path packet) throws IOException {
        Objects.requireNonNull(packet, "packet");
        if (!Files.isRegularFile(packet, LinkOption.NOFOLLOW_LINKS)
                || Files.size(packet) > MAX_BYTES) {
            throw new IOException("CDT original-owner facts must be a regular file under 64 KiB");
        }
        try (InputStream in = Channels.newInputStream(Files.newByteChannel(packet,
                Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            return decode(in.readNBytes(MAX_BYTES + 1));
        }
    }

    public static Snapshot decode(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IOException("CDT original-owner packet byte budget exceeded");
        }
        final String wire;
        try {
            wire = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("non-UTF-8 CDT owner evidence", invalid);
        }
        if (!wire.endsWith("\n") || wire.charAt(0) == '\uFEFF' || wire.indexOf('\r') >= 0) {
            throw new IOException("CDT packet requires LF-only without BOM");
        }
        String[] rows = wire.split("\n", -1);
        if (rows.length != 16 || !MAGIC.equals(rows[0]) || !rows[15].isEmpty()) {
            throw new IOException("unsupported CDT facts version or record count");
        }
        try {
            return new Snapshot(
                    fieldValue(rows[1], "provider"),
                    decode64(fieldValue(rows[2], "project64"), false),
                    fieldValue(rows[3], "invocation"),
                    decode64(fieldValue(rows[4], "eclipseSelected64"), true),
                    decode64(fieldValue(rows[5], "cdtConfigId64"), false),
                    decode64(fieldValue(rows[6], "cdtConfigName64"), false),
                    decode64(fieldValue(rows[7], "cdtActiveId64"), false),
                    decode64(fieldValue(rows[8], "buildSystem64"), false),
                    fieldValue(rows[9], "scannerSha256"),
                    fieldValue(rows[10], "sourcesSha256"),
                    fieldValue(rows[11], "buildSettingSha256"),
                    count(fieldValue(rows[12], "resourceCount"), MAX_RESOURCES),
                    count(fieldValue(rows[13], "languageCount"), MAX_LANGUAGES),
                    count(fieldValue(rows[14], "entryCount"), MAX_ENTRIES));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("inconsistent original CDT compiler facts", invalid);
        }
    }

    /**
     * Bind only matching Eclipse project, original invocation UUID and
     * Eclipse-selected configuration. CDT config ID is deliberately separate
     * from Eclipse-selected IBuildConfiguration or the active CDT config.
     */
    public static Assessment bind(Snapshot cdt, M3EclipseBuildHandoffWire.Snapshot eclipse)
            throws IOException {
        Objects.requireNonNull(cdt, "cdt");
        Objects.requireNonNull(eclipse, "eclipse");
        if (!cdt.project().equals(eclipse.project())
                || !cdt.invocation().equals(eclipse.invocation())
                || !cdt.eclipseSelected().equals(eclipse.selectedConfiguration())) {
            throw new IOException("foreign CDT compiler facts or Eclipse build generation");
        }
        return new Assessment(cdt, eclipse.buildKind(), eclipse.deltaComplete());
    }

    public static Assessment inspect(Path cdt, Path handoff) throws IOException {
        return bind(read(cdt), M3EclipseBuildHandoffWire.read(handoff));
    }

    private static int count(String string, int maximum) {
        if (!string.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("noncanonical CDT metadata count");
        }
        int value = Integer.parseInt(string);
        if (value > maximum) {
            throw new IllegalArgumentException("original CDT metadata count exceeds budget");
        }
        return value;
    }

    private static String fieldValue(String row, String key) throws IOException {
        String prefix = key + '\t';
        if (!row.startsWith(prefix) || row.indexOf('\t', prefix.length()) >= 0) {
            throw new IOException("missing or malformed original CDT record: " + key);
        }
        return row.substring(prefix.length());
    }

    private static String decode64(String encoded, boolean empty) throws IOException {
        if (encoded.length() > 5500 || !encoded.matches("[A-Za-z0-9_-]*")) {
            throw new IOException("invalid canonical CDT metadata encoding");
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) {
                throw new IOException("noncanonical CDT Base64URL");
            }
            return valid(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString(), empty);
        } catch (CharacterCodingException | IllegalArgumentException invalid) {
            throw new IOException("invalid original CDT UTF-8 identity", invalid);
        }
    }

    private static String valid(String string, boolean empty) {
        Objects.requireNonNull(string, "original CDT identity");
        if ((!empty && string.isBlank()) || string.length() > 4096
                || string.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid CDT identity");
        }
        for (int index = 0; index < string.length(); index++) {
            char c = string.charAt(index);
            if (Character.isHighSurrogate(c)) {
                if (++index >= string.length() || !Character.isLowSurrogate(string.charAt(index))) {
                    throw new IllegalArgumentException("invalid CDT Unicode");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("invalid CDT Unicode");
            }
        }
        return string;
    }

    private static String sha(String string) {
        if (string == null || !string.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("noncanonical original CDT SHA-256");
        }
        return string;
    }

    private static void field(StringBuilder out, String label, String value) {
        out.append(',');
        quote(out, label);
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
