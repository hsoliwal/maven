/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

/**
 * Synthetic SPI fixture. Does NOT claim that original PyDev compilation,
 * interpreters or environment resolution are implemented by Maven/Gradle.
 */
public final class M3TestPyDevEvidenceProvider implements M3EclipseLanguageEvidenceProvider {
    public static final String ID = "test.pydev";
    public static final String NATURE = "org.python.pydev.pythonNature";

    @Override public String id() { return ID; }
    @Override public Set<String> builderIds() { return Set.of(); }
    @Override public Set<String> natureIds() { return Set.of(NATURE); }

    @Override
    public Evidence decode(byte[] source) throws IOException {
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source)).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IOException("synthetic language packet must use UTF-8", invalid);
        }
        if (!text.endsWith("\n") || text.indexOf('\r') >= 0) {
            throw new IOException("synthetic packet must have canonical LF");
        }
        String[] lines = text.split("\n", -1);
        if (lines.length != 7 || !lines[0].equals("M3-TEST-PYDEV-FACTS-V1")
                || !lines[6].isEmpty()) {
            throw new IOException("unexpected synthetic language packet version or records");
        }
        String project = decode64(field(lines[1], "project64"));
        String invocation = field(lines[2], "invocation");
        String selected = decode64(field(lines[3], "selected64"));
        int count;
        try {
            String value = field(lines[4], "observedCount");
            if (!value.matches("0|[1-9][0-9]*")) throw new NumberFormatException();
            count = Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IOException("invalid language fixture item count", invalid);
        }
        String digest = field(lines[5], "observedSha256");
        try {
            return new Evidence("M3-TEST-PYDEV-FACTS-V1", project, invocation,
                    selected, count, digest);
        } catch (RuntimeException invalid) {
            throw new IOException("invalid synthetic language owner evidence", invalid);
        }
    }

    private static String field(String row, String name) throws IOException {
        String prefix = name + '\t';
        if (!row.startsWith(prefix) || row.indexOf('\t', prefix.length()) >= 0) {
            throw new IOException("invalid synthetic language field: " + name);
        }
        return row.substring(prefix.length());
    }

    private static String decode64(String value) throws IOException {
        if (!value.matches("[A-Za-z0-9_-]*")) {
            throw new IOException("invalid synthetic Base64URL");
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(value);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(value)) {
                throw new IOException("noncanonical synthetic Base64URL");
            }
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException invalid) {
            throw new IOException("invalid synthetic UTF-8 field", invalid);
        }
    }
}
