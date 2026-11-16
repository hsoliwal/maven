// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Content-addressed ledgers (M3-SCALE v3.0 section 6.4). Atom: hash and tab-separated ledger I/O. Pattern: content
 * address; every recorded file state is a SHA-256 digest, and a ledger digest is the digest of its sorted rows.
 */
final class Ledger {
    private Ledger() {
    }

    static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Hashes each repository-relative path; a missing path is {@code DELETED}, a directory {@code DIRECTORY}. */
    static Map<String, String> hashes(Path root, Collection<String> paths) throws IOException {
        Map<String, String> out = new TreeMap<>();
        for (String path : paths) {
            Path file = root.resolve(path);
            out.put(path, Files.isRegularFile(file) ? sha256(file) : Files.isDirectory(file) ? "DIRECTORY" : "DELETED");
        }
        return out;
    }

    static String ledger(Map<String, String> rows) {
        return "path\tsha256\n" + new TreeMap<>(rows).entrySet().stream().map(e -> e.getKey() + "\t" + e.getValue() + "\n")
                .collect(Collectors.joining());
    }

    static String digest(Map<String, String> rows) {
        return sha256(ledger(rows).getBytes(StandardCharsets.UTF_8));
    }

    /** Reads a two-column ledger after its header row; a missing file reads as empty. */
    static Map<String, String> table(Path file) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return out;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        lines.subList(Math.min(1, lines.size()), lines.size()).stream().map(l -> l.split("\t", 2)).filter(c -> c.length == 2)
                .forEach(c -> out.put(c[0], c[1]));
        return out;
    }

    static String keyValues(Map<String, String> rows) {
        return "key\tvalue\n" + rows.entrySet().stream().map(e -> e.getKey() + "\t" + clean(e.getValue()) + "\n")
                .collect(Collectors.joining());
    }

    static String clean(String text) {
        return text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    static BufferedReader reader(Path file) throws IOException {
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)));
    }

    static void write(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    static void append(Path dir, String name, String line) throws IOException {
        Files.writeString(dir.resolve(name), line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }
}
