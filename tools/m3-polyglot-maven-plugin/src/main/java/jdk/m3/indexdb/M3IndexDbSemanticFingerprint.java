// SPDX-License-Identifier: Apache-2.0
package jdk.m3.indexdb;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Canonical structural/logic/SimHash fingerprint algebra owned by M3IndexDB.
 *
 * <p>Structural identity is deliberately separate from normalized logic identity. Role and child
 * order participate in parent composition. SimHash is a similarity signal only and never proves
 * equivalence.
 */
public record M3IndexDbSemanticFingerprint(
        String exactSha256,
        String structuralSha256,
        String logicSha256,
        long structuralHash64,
        long logicHash64,
        long simHash64,
        String normalizedComposition) {

    public M3IndexDbSemanticFingerprint {
        exactSha256 = requireSha(exactSha256, "exactSha256");
        structuralSha256 = requireSha(structuralSha256, "structuralSha256");
        logicSha256 = requireSha(logicSha256, "logicSha256");
        normalizedComposition =
                Objects.requireNonNull(normalizedComposition, "normalizedComposition");
    }

    public record Component(String role, M3IndexDbSemanticFingerprint fingerprint) {
        public Component {
            role = token(role, "role");
            fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        }
    }

    public static M3IndexDbSemanticFingerprint leaf(
            CharSequence domain,
            List<String> structuralFeatures,
            List<String> logicFeatures,
            CharSequence exactSource) {
        String checkedDomain = token(domain, "domain");
        List<String> structure = List.copyOf(structuralFeatures);
        List<String> logic = List.copyOf(logicFeatures);
        String exact = Objects.requireNonNull(exactSource, "exactSource").toString();

        String structuralSha = framedSha(join(checkedDomain, structure));
        String logicSha = framedSha(join(checkedDomain, logic));

        long structural64 = domainHash("STRUCTURE:" + checkedDomain);
        for (String value : structure) {
            structural64 = combine(
                    structural64,
                    feature("S", Objects.requireNonNull(value, "structuralFeature")));
        }

        long logic64 = domainHash("LOGIC:" + checkedDomain);
        int[] votes = new int[Long.SIZE];
        for (String value : logic) {
            long hashed = feature("L", Objects.requireNonNull(value, "logicFeature"));
            logic64 = combine(logic64, hashed);
            vote(votes, hashed, 1);
        }
        if (logic.isEmpty()) vote(votes, feature("L", checkedDomain), 1);

        return new M3IndexDbSemanticFingerprint(
                utf16Sha256(exact),
                structuralSha,
                logicSha,
                avalanche(structural64 ^ structure.size()),
                avalanche(logic64 ^ logic.size()),
                finishVotes(votes),
                checkedDomain + "|" + String.join("|", logic));
    }

    public static M3IndexDbSemanticFingerprint compose(
            CharSequence domain,
            List<Component> orderedChildren) {
        String checkedDomain = token(domain, "domain");
        List<Component> children = List.copyOf(orderedChildren);
        ArrayList<String> structural = new ArrayList<>(children.size());
        ArrayList<String> logic = new ArrayList<>(children.size());
        StringBuilder exact = new StringBuilder(checkedDomain);
        long structural64 = domainHash("STRUCTURE:" + checkedDomain);
        long logic64 = domainHash("LOGIC:" + checkedDomain);
        int[] votes = new int[Long.SIZE];

        for (int ordinal = 0; ordinal < children.size(); ordinal++) {
            Component component = Objects.requireNonNull(children.get(ordinal), "component");
            M3IndexDbSemanticFingerprint child = component.fingerprint();
            String roleOrdinal = component.role() + "#" + ordinal;

            structural.add(roleOrdinal + ":" + child.structuralSha256());
            logic.add(roleOrdinal + ":" + child.logicSha256());
            exact.append('|').append(roleOrdinal).append(':').append(child.exactSha256());

            long roleHash = feature("ROLE", roleOrdinal);
            structural64 = combine(structural64, roleHash);
            structural64 = combine(structural64, child.structuralHash64());
            logic64 = combine(logic64, roleHash);
            logic64 = combine(logic64, child.logicHash64());
            vote(votes, roleHash, 1);
            vote(votes, mix(child.simHash64() ^ roleHash), 1);
        }
        if (children.isEmpty()) vote(votes, feature("EMPTY", checkedDomain), 1);

        return new M3IndexDbSemanticFingerprint(
                utf16Sha256(exact),
                framedSha(join(checkedDomain, structural)),
                framedSha(join(checkedDomain, logic)),
                avalanche(structural64 ^ children.size()),
                avalanche(logic64 ^ children.size()),
                finishVotes(votes),
                checkedDomain + "|" + String.join("|", logic));
    }

    public static int hammingDistance(long left, long right) {
        return Long.bitCount(left ^ right);
    }

    public String structuralHash64Hex() {
        return HexFormat.of().toHexDigits(structuralHash64);
    }

    public String logicHash64Hex() {
        return HexFormat.of().toHexDigits(logicHash64);
    }

    public String simHash64Hex() {
        return HexFormat.of().toHexDigits(simHash64);
    }

    public static String utf16Sha256(CharSequence value) {
        Objects.requireNonNull(value, "value");
        MessageDigest digest = digest();
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            digest.update((byte) (unit >>> 8));
            digest.update((byte) unit);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<String> join(String domain, List<String> features) {
        ArrayList<String> values = new ArrayList<>(features.size() + 1);
        values.add(domain);
        values.addAll(features);
        return values;
    }

    private static String framedSha(List<String> values) {
        MessageDigest digest = digest();
        for (String value : values) frame(digest, Objects.requireNonNull(value, "feature"));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void frame(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static long domainHash(String value) {
        return avalanche(feature("DOMAIN", value) ^ 0x4d33494e44455821L);
    }

    private static long feature(String domain, String value) {
        long hash = 0xcbf29ce484222325L;
        hash = fnv(hash, domain);
        hash ^= 0xffL;
        hash *= 0x100000001b3L;
        return avalanche(fnv(hash, value));
    }

    private static long fnv(long seed, String text) {
        long hash = seed;
        for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
            hash ^= Byte.toUnsignedInt(value);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long combine(long hash, long value) {
        return Long.rotateLeft(hash ^ avalanche(value + 0x9e3779b97f4a7c15L), 27)
                * 0x94d049bb133111ebL + 0x52dce729L;
    }

    private static long mix(long value) {
        return avalanche(value ^ 0x6a09e667f3bcc909L);
    }

    private static long avalanche(long value) {
        long result = value;
        result = (result ^ (result >>> 30)) * 0xbf58476d1ce4e5b9L;
        result = (result ^ (result >>> 27)) * 0x94d049bb133111ebL;
        return result ^ (result >>> 31);
    }

    private static void vote(int[] votes, long feature, int weight) {
        for (int bit = 0; bit < Long.SIZE; bit++) {
            votes[bit] += ((feature >>> bit) & 1L) == 0L ? -weight : weight;
        }
    }

    private static long finishVotes(int[] votes) {
        long result = 0L;
        for (int bit = 0; bit < votes.length; bit++) {
            if (votes[bit] >= 0) result |= 1L << bit;
        }
        return result;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String requireSha(String value, String field) {
        String checked = Objects.requireNonNull(value, field);
        if (!checked.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(field);
        return checked;
    }

    private static String token(CharSequence value, String field) {
        String checked = Objects.requireNonNull(value, field).toString().strip();
        if (checked.isEmpty() || checked.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field);
        }
        return checked;
    }
}
