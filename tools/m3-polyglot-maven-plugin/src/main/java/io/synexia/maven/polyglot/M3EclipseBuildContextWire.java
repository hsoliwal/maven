/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure Java 17 reader for an original Eclipse owner's build-configuration
 * exchange. No Eclipse/Maven/Gradle runtime or filesystem scan is required.
 *
 * <p>A selected Eclipse IBuildConfiguration may differ from the active one.
 * Builder order and nature declarations are kept exactly as the owner emitted.
 * This is not a successful build, dependency identity, toolchain identity,
 * source generation, or permission to skip any build or restore a cache.</p>
 */
public final class M3EclipseBuildContextWire {
    public static final String MAGIC = "M3-ECLIPSE-BUILD-CONTEXT-V1";
    public static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_ITEMS = 2048;

    private M3EclipseBuildContextWire() {}

    public record Snapshot(String provider, String project, String selectedConfiguration,
            String activeConfiguration, List<String> configurations, List<String> builders,
            List<String> natures) {
        public Snapshot {
            if (provider == null || !provider.matches("[a-z][a-z0-9_.-]{0,127}")) {
                throw new IllegalArgumentException("invalid Eclipse source-owner ID");
            }
            project = checked(project, false);
            selectedConfiguration = checked(selectedConfiguration, true);
            activeConfiguration = checked(activeConfiguration, true);
            configurations = List.copyOf(configurations);
            builders = List.copyOf(builders);
            natures = List.copyOf(natures);
            if (configurations.isEmpty() || configurations.size() > MAX_ITEMS
                    || builders.size() > MAX_ITEMS || natures.size() > MAX_ITEMS) {
                throw new IllegalArgumentException("invalid Eclipse context size");
            }
            Set<String> declared = new HashSet<>();
            for (String config : configurations) {
                if (!declared.add(checked(config, true))) {
                    throw new IllegalArgumentException("duplicate Eclipse build configuration");
                }
            }
            if (!declared.contains(selectedConfiguration) || !declared.contains(activeConfiguration)) {
                throw new IllegalArgumentException("unknown selected or active Eclipse configuration");
            }
            for (String builder : builders) checked(builder, false);
            for (String nature : natures) checked(nature, false);
        }

        /** Deterministic, versioned JSON with no executable tool commands. */
        public String toJson() {
            StringBuilder out = new StringBuilder("{\"schema\":\"m3-eclipse-build-context-v1\"");
            field(out, "provider", provider);
            field(out, "project", project);
            field(out, "selectedConfiguration", selectedConfiguration);
            field(out, "activeConfiguration", activeConfiguration);
            array(out, "configurations", configurations);
            array(out, "builders", builders);
            array(out, "natures", natures);
            return out.append('}').toString();
        }
    }

    /** Fail closed on symlinks and oversized or changing packet files. */
    public static Snapshot read(Path packet) throws IOException {
        Objects.requireNonNull(packet, "packet");
        if (!Files.isRegularFile(packet, LinkOption.NOFOLLOW_LINKS) || Files.size(packet) > MAX_BYTES) {
            throw new IOException("Eclipse context packet must be a regular file under 1 MiB");
        }
        byte[] data = Files.readAllBytes(packet);
        return decode(data);
    }

    /** Strict canonical UTF-8/LF/URL-safe-base64 decoding of original owner facts. */
    public static Snapshot decode(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IOException("Eclipse context packet byte limit");
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException bad) {
            throw new IOException("Eclipse context packet must be UTF-8", bad);
        }
        if (!text.endsWith("\n") || text.charAt(0) == '\uFEFF' || text.indexOf('\r') >= 0) {
            throw new IOException("Eclipse context packet must be LF-only, without BOM");
        }
        String[] rows = text.split("\n", -1);
        if (rows.length < 7 || !MAGIC.equals(rows[0])) {
            throw new IOException("unknown Eclipse context packet version");
        }
        String provider = required(rows[1], "provider");
        String project = decoded(required(rows[2], "project64"), false);
        String selected = decoded(required(rows[3], "selected64"), true);
        String active = decoded(required(rows[4], "active64"), true);
        List<String> configurations = new ArrayList<>();
        List<String> builders = new ArrayList<>();
        List<String> natures = new ArrayList<>();
        int phase = 0;
        // Last row must be the empty segment after the final LF.
        for (int i = 5; i < rows.length - 1; i++) {
            String row = rows[i];
            int tab = row.indexOf('\t');
            if (tab < 0 || tab != row.lastIndexOf('\t')) {
                throw new IOException("malformed Eclipse context row");
            }
            String key = row.substring(0, tab);
            int next = switch (key) {
                case "configuration64" -> 0;
                case "builder64" -> 1;
                case "nature64" -> 2;
                default -> throw new IOException("unknown Eclipse context row");
            };
            if (next < phase) {
                throw new IOException("Eclipse context rows are out of order");
            }
            phase = next;
            String value = decoded(row.substring(tab + 1), next == 0);
            switch (phase) {
                case 0 -> configurations.add(value);
                case 1 -> builders.add(value);
                case 2 -> natures.add(value);
                default -> throw new IOException("unreachable Eclipse context phase");
            }
            if (configurations.size() > MAX_ITEMS || builders.size() > MAX_ITEMS
                    || natures.size() > MAX_ITEMS) {
                throw new IOException("Eclipse context record budget exceeded");
            }
        }
        if (rows[rows.length - 1].length() != 0) {
            throw new IOException("unterminated Eclipse context packet");
        }
        try {
            return new Snapshot(provider, project, selected, active, configurations, builders, natures);
        } catch (IllegalArgumentException bad) {
            throw new IOException("inconsistent Eclipse context owner claims", bad);
        }
    }

    private static String required(String row, String key) throws IOException {
        String prefix = key + '\t';
        if (!row.startsWith(prefix) || row.indexOf('\t', prefix.length()) >= 0) {
            throw new IOException("missing or malformed Eclipse context field: " + key);
        }
        return row.substring(prefix.length());
    }

    private static String decoded(String encoded, boolean allowEmpty) throws IOException {
        if (encoded.length() > 5500 || !encoded.matches("[A-Za-z0-9_-]*")) {
            throw new IOException("noncanonical Eclipse context encoding");
        }
        final byte[] data;
        try {
            data = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException bad) {
            throw new IOException("invalid Eclipse context base64", bad);
        }
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(data).equals(encoded)) {
            throw new IOException("noncanonical Eclipse context base64");
        }
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
            return checked(value, allowEmpty);
        } catch (CharacterCodingException | IllegalArgumentException bad) {
            throw new IOException("invalid Eclipse context UTF-8 field", bad);
        }
    }

    private static String checked(String value, boolean allowEmpty) {
        Objects.requireNonNull(value, "Eclipse context field");
        if ((!allowEmpty && value.isBlank()) || value.length() > 1024
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid Eclipse context name");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("invalid Eclipse context Unicode");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("invalid Eclipse context Unicode");
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

    private static void array(StringBuilder out, String key, List<String> values) {
        out.append(',');
        quote(out, key);
        out.append(":[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            quote(out, values.get(i));
        }
        out.append(']');
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
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
