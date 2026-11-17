// SPDX-License-Identifier: Apache-2.0
package jdk.m3.indexdb;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable content-addressed opaque artifact persisted by M3IndexDB. */
public record M3IndexDbArtifact(
        String name,
        String kind,
        int formatVersion,
        String contentSha256,
        byte[] payload) {

    public M3IndexDbArtifact {
        name = requireText(name, 512, "name");
        kind = requireText(kind, 64, "kind");
        if (formatVersion <= 0) {
            throw new IllegalArgumentException("formatVersion must be positive");
        }
        contentSha256 = Objects.requireNonNull(contentSha256, "contentSha256");
        if (!contentSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentSha256 must be lowercase SHA-256");
        }
        payload = Objects.requireNonNull(payload, "payload").clone();
        String actual = sha256(payload);
        if (!actual.equals(contentSha256)) {
            throw new IllegalArgumentException("payload does not match contentSha256");
        }
    }

    public static M3IndexDbArtifact create(
            String name,
            String kind,
            int formatVersion,
            byte[] payload) {
        byte[] checked = Objects.requireNonNull(payload, "payload").clone();
        return new M3IndexDbArtifact(
                name,
                kind,
                formatVersion,
                sha256(checked),
                checked);
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    public long payloadLength() {
        return payload.length;
    }

    private static String requireText(String value, int maximum, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank() || checked.length() > maximum || checked.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid artifact " + name);
        }
        return checked;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JDK does not provide SHA-256", error);
        }
    }
}
