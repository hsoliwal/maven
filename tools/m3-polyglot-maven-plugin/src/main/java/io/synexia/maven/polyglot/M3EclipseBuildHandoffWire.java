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
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.OpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pure Java 17 decoder of one Eclipse-owned build invocation. One packet
 * contains both the actual selected IBuildConfiguration and the original
 * IResourceDelta. Neither Maven nor Gradle synthesizes Eclipse build facts.
 *
 * <p>Change paths are array-backed; entry objects are materialized on demand.
 * This is advisory evidence, not a compiler-result or build-cache receipt.</p>
 */
public final class M3EclipseBuildHandoffWire {
    public static final String MAGIC = "M3-ECLIPSE-BUILD-HANDOFF-V1";
    public static final int MAX_BYTES = 12 * 1024 * 1024;
    public static final int MAX_CHANGES = 65_536;
    private static final int MAX_ITEMS = 2048;

    private M3EclipseBuildHandoffWire() { }

    public enum ChangeKind { ADDED, MODIFIED, DELETED }
    public record Change(String path, ChangeKind kind) { }

    /** Immutable, detached original-owner facts; no mutable IDE objects retained. */
    public static final class Snapshot {
        private final String provider;
        private final String project;
        private final String invocation;
        private final String selected;
        private final String active;
        private final String kind;
        private final boolean complete;
        private final List<String> configurations;
        private final List<String> builders;
        private final List<String> natures;
        private final String[] paths;
        private final byte[] kinds;

        private Snapshot(String provider, String project, String invocation,
                String selected, String active, String kind, boolean complete,
                List<String> configurations, List<String> builders, List<String> natures,
                String[] paths, byte[] kinds) {
            this.provider = provider;
            this.project = project;
            this.invocation = invocation;
            this.selected = selected;
            this.active = active;
            this.kind = kind;
            this.complete = complete;
            this.configurations = List.copyOf(configurations);
            this.builders = List.copyOf(builders);
            this.natures = List.copyOf(natures);
            this.paths = paths.clone();
            this.kinds = kinds.clone();
        }

        public String provider() { return provider; }
        public String project() { return project; }
        public String invocation() { return invocation; }
        public String selectedConfiguration() { return selected; }
        public String activeConfiguration() { return active; }
        public String buildKind() { return kind; }
        public boolean deltaComplete() { return complete; }
        public List<String> configurations() { return configurations; }
        public List<String> builders() { return builders; }
        public List<String> natures() { return natures; }
        public int size() { return paths.length; }
        public Change entry(int index) { return new Change(paths[index], ChangeKind.values()[kinds[index]]); }

        /** Stable configuration scope, *not* a verified build-cache key. */
        public String scopeSha256(String workspaceOwnerId) {
            valid(workspaceOwnerId, false);
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                update(md, "m3-eclipse-owner-scope-v1");
                update(md, provider);
                update(md, workspaceOwnerId);
                update(md, project);
                update(md, selected);
                return java.util.HexFormat.of().formatHex(md.digest());
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 required by the JDK", impossible);
            }
        }

        /** Bounded, deterministic diagnostics for Gradle and Maven consumers. */
        public String toJson() throws IOException {
            StringBuilder out = new StringBuilder(1024);
            out.append("{\"schema\":\"m3-eclipse-build-handoff-v1\"");
            field(out, "provider", provider);
            field(out, "project", project);
            field(out, "invocation", invocation);
            field(out, "selectedConfiguration", selected);
            field(out, "activeConfiguration", active);
            field(out, "buildKind", kind);
            out.append(",\"deltaComplete\":").append(complete);
            array(out, "configurations", configurations);
            array(out, "builders", builders);
            array(out, "natures", natures);
            out.append(",\"changeCount\":").append(paths.length);
            out.append(",\"changes\":[");
            for (int i = 0; i < paths.length; i++) {
                if (i != 0) out.append(',');
                out.append("{\"kind\":");
                quote(out, ChangeKind.values()[kinds[i]].name());
                out.append(",\"path\":");
                quote(out, paths[i]);
                out.append('}');
                if (out.length() > 24 * 1024 * 1024) {
                    throw new IOException("Eclipse diagnostic report exceeds 24 MiB");
                }
            }
            out.append("]}");
            return out.toString();
        }
    }

    /** Validate actual bytes, not merely a previously checked path. */
    public static Snapshot read(Path packet) throws IOException {
        Objects.requireNonNull(packet, "packet");
        if (!Files.isRegularFile(packet, LinkOption.NOFOLLOW_LINKS)
                || Files.size(packet) > MAX_BYTES) {
            throw new IOException("Eclipse handoff must be a regular packet <= 12 MiB");
        }
        try (InputStream in = Channels.newInputStream(Files.newByteChannel(
                packet, Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            return decode(bytes);
        }
    }

    public static Snapshot decode(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IOException("Eclipse handoff byte budget exceeded");
        }
        // Bound row count before String.split allocates per-row objects.
        // Eight fixed records plus the maximum configuration, builder, nature
        // and original Eclipse resource change rows.
        final int maxLines = 8 + 3 * MAX_ITEMS + MAX_CHANGES;
        int lines = 0;
        for (byte value : bytes) {
            if (value == '\n' && ++lines > maxLines) {
                throw new IOException("Eclipse handoff row count exceeds budget");
            }
        }
        final String body;
        try {
            body = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException bad) {
            throw new IOException("Eclipse handoff is not strict UTF-8", bad);
        }
        if (!body.endsWith("\n") || body.charAt(0) == '\uFEFF' || body.indexOf('\r') >= 0) {
            throw new IOException("Eclipse handoff requires canonical LF, no BOM");
        }
        String[] rows = body.split("\n", -1);
        if (rows.length < 10 || !MAGIC.equals(rows[0])) {
            throw new IOException("unknown or incomplete Eclipse handoff version");
        }
        String provider = field(rows[1], "provider");
        if (!"eclipse.m2e".equals(provider)) {
            throw new IOException("unexpected Eclipse handoff source owner");
        }
        String project = decode64(field(rows[2], "project64"), false);
        String invocation = field(rows[3], "invocation");
        try {
            if (!UUID.fromString(invocation).toString().equals(invocation)) {
                throw new IllegalArgumentException("noncanonical UUID");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid Eclipse invocation identity", invalid);
        }
        String selected = decode64(field(rows[4], "selected64"), true);
        String active = decode64(field(rows[5], "active64"), true);
        String kind = field(rows[6], "kind");
        if (!List.of("FULL", "CLEAN", "INCREMENTAL", "AUTO").contains(kind)) {
            throw new IOException("unsupported Eclipse build kind");
        }
        String completeValue = field(rows[7], "complete");
        if (!completeValue.equals("true") && !completeValue.equals("false")) {
            throw new IOException("noncanonical Eclipse delta completeness");
        }
        boolean complete = Boolean.parseBoolean(completeValue);
        if (complete && ("FULL".equals(kind) || "CLEAN".equals(kind))) {
            throw new IOException("full/clean Eclipse build cannot claim a complete delta");
        }

        List<String> configs = new ArrayList<>();
        List<String> builders = new ArrayList<>();
        List<String> natures = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        byte[] kindBuffer = new byte[MAX_CHANGES];
        String lastPath = null;
        int phase = 0;
        for (int i = 8; i < rows.length - 1; i++) {
            String row = rows[i];
            int firstTab = row.indexOf('\t');
            if (firstTab <= 0) throw new IOException("invalid Eclipse handoff row");
            String label = row.substring(0, firstTab);
            int next = switch (label) {
                case "configuration64" -> 0;
                case "builder64" -> 1;
                case "nature64" -> 2;
                case "change" -> 3;
                default -> throw new IOException("unsupported Eclipse handoff record");
            };
            if (next < phase) throw new IOException("Eclipse handoff rows are out of order");
            phase = next;
            if (phase < 3) {
                if (row.indexOf('\t', firstTab + 1) >= 0) {
                    throw new IOException("extra Eclipse metadata field");
                }
                String value = decode64(row.substring(firstTab + 1), phase == 0);
                switch (phase) {
                    case 0 -> configs.add(value);
                    case 1 -> builders.add(value);
                    case 2 -> natures.add(value);
                    default -> throw new IOException("unexpected metadata phase");
                }
                if (configs.size() > MAX_ITEMS || builders.size() > MAX_ITEMS
                        || natures.size() > MAX_ITEMS) {
                    throw new IOException("Eclipse handoff metadata budget exceeded");
                }
            } else {
                int secondTab = row.indexOf('\t', firstTab + 1);
                if (secondTab <= firstTab + 1 || row.indexOf('\t', secondTab + 1) >= 0
                        || paths.size() == MAX_CHANGES) {
                    throw new IOException("invalid or excessive Eclipse resource changes");
                }
                String value = row.substring(firstTab + 1, secondTab);
                ChangeKind changeKind;
                try {
                    changeKind = ChangeKind.valueOf(value);
                } catch (IllegalArgumentException unknown) {
                    throw new IOException("unknown original Eclipse change kind", unknown);
                }
                String path = checkedPath(row.substring(secondTab + 1));
                if (lastPath != null && lastPath.compareTo(path) >= 0) {
                    throw new IOException("Eclipse resource paths are unsorted or duplicated");
                }
                kindBuffer[paths.size()] = (byte) changeKind.ordinal();
                paths.add(path);
                lastPath = path;
            }
        }
        if (!rows[rows.length - 1].isEmpty() || configs.isEmpty()) {
            throw new IOException("missing Eclipse configurations or terminal LF");
        }
        try {
            Set<String> declared = new HashSet<>();
            for (String value : configs) {
                if (!declared.add(value)) {
                    throw new IllegalArgumentException("duplicate Eclipse configurations");
                }
            }
            if (!declared.contains(selected) || !declared.contains(active)) {
                throw new IllegalArgumentException("unknown selected or active Eclipse configuration");
            }
            return new Snapshot(provider, project, invocation, selected, active, kind, complete,
                    configs, builders, natures, paths.toArray(String[]::new),
                    Arrays.copyOf(kindBuffer, paths.size()));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("inconsistent original Eclipse owner handoff", invalid);
        }
    }

    private static String field(String row, String label) throws IOException {
        String prefix = label + '\t';
        if (!row.startsWith(prefix) || row.indexOf('\t', prefix.length()) >= 0) {
            throw new IOException("missing or malformed original Eclipse handoff field: " + label);
        }
        return row.substring(prefix.length());
    }

    private static String decode64(String encoded, boolean allowEmpty) throws IOException {
        if (encoded.length() > 5500 || !encoded.matches("[A-Za-z0-9_-]*")) {
            throw new IOException("invalid Eclipse base64 token");
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(encoded);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(encoded)) {
                throw new IOException("noncanonical Eclipse base64 token");
            }
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
            return valid(text, allowEmpty);
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            throw new IOException("invalid UTF-8 in Eclipse metadata", invalid);
        }
    }

    private static String valid(String value, boolean allowEmpty) {
        Objects.requireNonNull(value, "Eclipse metadata");
        if ((!allowEmpty && value.isBlank()) || value.length() > 1024
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid Eclipse identity");
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("malformed Eclipse UTF-16");
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("malformed Eclipse UTF-16");
            }
        }
        return value;
    }

    private static String checkedPath(String path) throws IOException {
        if (path.isEmpty() || path.length() > 2048 || path.startsWith("/")
                || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0
                || path.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("invalid Eclipse resource path");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IOException("noncanonical Eclipse resource path");
            }
        }
        try { valid(path, false); }
        catch (IllegalArgumentException invalid) { throw new IOException("malformed resource Unicode", invalid); }
        return path;
    }

    private static void update(MessageDigest digest, String text) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (raw.length >>> 24));
        digest.update((byte) (raw.length >>> 16));
        digest.update((byte) (raw.length >>> 8));
        digest.update((byte) raw.length);
        digest.update(raw);
    }

    private static void field(StringBuilder out, String name, String value) {
        out.append(',');
        quote(out, name);
        out.append(':');
        quote(out, value);
    }

    private static void array(StringBuilder out, String name, List<String> values) {
        out.append(',');
        quote(out, name);
        out.append(":[");
        for (int i = 0; i < values.size(); i++) {
            if (i != 0) out.append(',');
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
                    } else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
