// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Conservatively preserve generated syntax macro/inclusion/embedded origins. */
public final class SyntaxOriginsContract {
    private static int checks;
    private static void ok(boolean cond, String why) {
        if (!cond) throw new AssertionError(why);
        checks++;
    }
    private static void rejects(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); }
        catch (Throwable t) {
            if (!type.isInstance(t)) throw new AssertionError("wrong rejection", t);
            checks++; return;
        }
        throw new AssertionError("invalid source correspondence accepted");
    }
    private static String sha(byte[] source) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static byte[] bytes(String source) { return source.getBytes(StandardCharsets.UTF_8); }
    public static void main(String[] args) {
        byte[] expanded = "ABαCDxyz".getBytes(StandardCharsets.UTF_8);
        var b = SyntaxTree.builder("lang/one", expanded);
        b.open("root", "", 0, 0); b.close(expanded.length);
        var tree = b.build();
        String a = "a".repeat(64), c = "c".repeat(64), d = "d".repeat(64);
        var map = SyntaxOrigins.builder(tree)
                .add(0, 2, "source/A", a, 4, 6, SyntaxOrigins.Relation.EXACT_BYTES)
                .add(2, 5, "source/MACRO", c, 30, 35, SyntaxOrigins.Relation.MACRO_EXPANSION)
                .add(2, 5, "source/A", a, 100, 103, SyntaxOrigins.Relation.DERIVED)
                .add(5, 7, "source/C", d, 0, 2, SyntaxOrigins.Relation.EXACT_BYTES)
                .freeze();
        ok(map.count() == 4 && map.projectionSha256().equals(tree.projectionSha256()), "bound projection");
        ok(map.exactOriginalByteAt(0) == 4 && map.exactOriginalByteAt(1) == 5,
                "unique exact byte mapping");
        ok(Arrays.equals(map.originRowsAt(3), new int[] {1, 2})
                && map.exactOriginalByteAt(3) == -1, "overlapping macro origins are ambiguous");
        ok(map.origin(1).sourceId().equals("source/MACRO")
                && map.origin(1).relation() == SyntaxOrigins.Relation.MACRO_EXPANSION,
                "preserve source identity and provenance relation");
        ok(map.exactOriginalByteAt(5) == 0 && map.exactOriginalByteAt(7) == -1,
                "half-open boundaries and only covered points");
        ok(map.originRowsAt(expanded.length).length == 0, "EOF is not silently mapped");
        rejects(IndexOutOfBoundsException.class, () -> map.originRowsAt(expanded.length + 1));
        rejects(IllegalArgumentException.class, () -> SyntaxOrigins.builder(tree)
                .add(0, 2, "source/A", a, 0, 3, SyntaxOrigins.Relation.EXACT_BYTES));
        map.requireProjection(tree);
        var nextBuilder = SyntaxTree.builder("lang/one", "else".getBytes(StandardCharsets.UTF_8));
        nextBuilder.open("root", "", 0, 0); nextBuilder.close(4);
        rejects(IllegalStateException.class, () -> map.requireProjection(nextBuilder.build()));
        rejects(IllegalArgumentException.class, () -> SyntaxOrigins.builder(tree)
                .add(0, 2, "id", "broken", 0, 1, SyntaxOrigins.Relation.DERIVED));
        rejects(IllegalArgumentException.class, () -> SyntaxOrigins.builder(tree)
                .add(2, 3, "source/A", a, 0, 1, SyntaxOrigins.Relation.EXACT_BYTES)
                .add(1, 2, "source/A", a, 0, 1, SyntaxOrigins.Relation.EXACT_BYTES));
        rejects(IllegalArgumentException.class, () -> SyntaxOrigins.builder(tree)
                .add(0, 1, "source/A", a, 0, 1, SyntaxOrigins.Relation.EXACT_BYTES)
                .add(1, 2, "source/A", c, 0, 1, SyntaxOrigins.Relation.EXACT_BYTES));
        var singleton = SyntaxOrigins.builder(tree);
        singleton.add(0, 1, "source/A", a, 0, 1, SyntaxOrigins.Relation.EXACT_BYTES);
        singleton.freeze();
        rejects(IllegalStateException.class, singleton::freeze);
        rejects(IllegalStateException.class, () -> singleton.add(1, 2, "source/A", a, 2, 3,
                SyntaxOrigins.Relation.EXACT_BYTES));
        var big = SyntaxOrigins.builder(tree);
        for (int i = 0; i < 70; i++) big.add(0, 1, "file-" + i, a, i, i+1,
                SyntaxOrigins.Relation.EXACT_BYTES);
        var many = big.freeze();
        ok(many.originRowsAt(0).length == 70 && many.exactOriginalByteAt(0) == -1,
                "growth and many-overlapping-sources refuse point projection");
        // Many independent translation units are expected in a generated
        // source graph. Preserve first-registration dictionary IDs without
        // an O(unique-files) linear scan on each new provenance record.
        int sourceCount = 8192;
        var indexedBuilder = SyntaxOrigins.builder(tree);
        for (int i = 0; i < sourceCount; i++) {
            indexedBuilder.add(0, 1, "source/generated-" + i, a,
                    i, i + 1, SyntaxOrigins.Relation.EXACT_BYTES);
        }
        // Reuse source #12 instead of registering a duplicate dictionary row.
        indexedBuilder.add(1, 2, "source/generated-12", a, 20000, 20001,
                SyntaxOrigins.Relation.EXACT_BYTES);
        var indexed = indexedBuilder.freeze();
        ok(indexed.count() == sourceCount + 1, "indexed source-origin count");
        for (int i : new int[] {0, 1, 12, 70, 4096, sourceCount - 1}) {
            var item = indexed.origin(i);
            ok(item.sourceId().equals("source/generated-" + i)
                    && item.sha256().equals(a)
                    && item.startByte() == i && item.endByte() == i + 1,
                    "stable insertion-order source identity for " + i);
        }
        ok(indexed.origin(sourceCount).sourceId().equals("source/generated-12")
                && indexed.origin(sourceCount).startByte() == 20000,
                "repeated provenance uses the original indexed source identity");
        rejects(IllegalArgumentException.class, () -> SyntaxOrigins.builder(tree)
                .add(0, 1, "source/generated-12", a, 0, 1, SyntaxOrigins.Relation.EXACT_BYTES)
                .add(1, 2, "source/generated-12", c, 0, 1, SyntaxOrigins.Relation.EXACT_BYTES));
        ok(indexed.originRowsAt(0).length == sourceCount
                && indexed.exactOriginalByteAt(0) == -1,
                "overlapping multi-source origins stay ambiguous");
        // The old metadata-only EXACT_BYTES relation checks length, not
        // actual original bytes. Verify physical images before treating it as
        // proof. This avoids silent origin-coordinate corruption in external
        // parsers, generated sources and macro/embedded-language projections.
        byte[] generated = bytes("XbcY");
        var sourceBuilder = SyntaxTree.builder("lang/verified", generated);
        sourceBuilder.open("file", "", 0, SyntaxTree.NAMED);
        sourceBuilder.close(generated.length);
        var verifiedTree = sourceBuilder.build();
        byte[] fileA = bytes("abcdef"), fileB = bytes("Y!");
        String hashA = sha(fileA), hashB = sha(fileB);
        var declared = SyntaxOrigins.builder(verifiedTree)
                .add(0, 1, "file/A", hashA, 0, 1, SyntaxOrigins.Relation.DERIVED)
                .add(1, 3, "file/A", hashA, 1, 3, SyntaxOrigins.Relation.EXACT_BYTES)
                .add(3, 4, "file/B", hashB, 0, 1, SyntaxOrigins.Relation.EXACT_BYTES)
                .freeze();
        var sources = Map.of("file/A", fileA, "file/B", fileB);
        int[] loads = {0};
        var proof = declared.verify(verifiedTree, id -> {
            loads[0]++;
            return sources.get(id);
        }, 2, generated.length + fileA.length + fileB.length);
        ok(proof.distinctSources() == 2 && proof.retainedSourceBytes() == 8
                && loads[0] == 2, "one copied/hashed image per distinct source ID");
        var firstProof = proof.exactAtByte(1);
        ok(firstProof != null && firstProof.sourceId().equals("file/A")
                && firstProof.sha256().equals(hashA)
                && firstProof.byteOffset() == 1 && firstProof.value() == (byte) 'b',
                "byte-checked original source coordinate and content");
        ok(proof.exactAtByte(2).byteOffset() == 2
                && proof.exactAtByte(3).sourceId().equals("file/B"),
                "exact byte points across independently hashed source images");
        ok(proof.exactAtByte(0) == null && proof.exactAtByte(generated.length) == null,
                "derived rows and EOF cannot be promoted to exact points");
        fileA[1] = (byte) 'Z';
        ok(proof.exactAtByte(1).value() == (byte) 'b',
                "verified source is a private immutable snapshot, not a caller alias");
        rejects(IllegalArgumentException.class, () -> declared.verify(verifiedTree,
                sources::get, 2, 100));
        rejects(IllegalArgumentException.class, () -> declared.verify(verifiedTree,
                id -> null, 2, 100));
        rejects(IllegalArgumentException.class, () -> declared.verify(verifiedTree,
                id -> id.equals("file/A") ? bytes("abcdef") : bytes("Y!"), 1, 100));
        rejects(IllegalArgumentException.class, () -> declared.verify(verifiedTree,
                id -> id.equals("file/A") ? bytes("abcdef") : bytes("Y!"), 2, 11));
        var wrongPhysicalContent = SyntaxOrigins.builder(verifiedTree)
                .add(1, 3, "other/A", sha(bytes("axcdef")), 1, 3,
                        SyntaxOrigins.Relation.EXACT_BYTES).freeze();
        ok(wrongPhysicalContent.exactOriginalByteAt(1) == 1,
                "metadata offset candidate alone cannot establish matching bytes");
        rejects(IllegalArgumentException.class, () -> wrongPhysicalContent.verify(verifiedTree,
                id -> bytes("axcdef"), 1, 100));
        var beyond = SyntaxOrigins.builder(verifiedTree)
                .add(0, 1, "file/A", hashA, 800, 801, SyntaxOrigins.Relation.DERIVED)
                .freeze();
        rejects(IllegalArgumentException.class, () -> beyond.verify(verifiedTree,
                id -> bytes("abcdef"), 1, 100));
        proof.requireProjection(verifiedTree);
        rejects(IllegalStateException.class, () -> proof.requireProjection(tree));
        rejects(IllegalStateException.class, () -> declared.verify(tree,
                id -> bytes("abcdef"), 2, 100));
        var overlapping = SyntaxOrigins.builder(verifiedTree)
                .add(1, 3, "file/A", hashA, 1, 3, SyntaxOrigins.Relation.EXACT_BYTES)
                .add(2, 3, "file/A", hashA, 2, 3, SyntaxOrigins.Relation.EXACT_BYTES)
                .freeze().verify(verifiedTree, id -> bytes("abcdef"), 1, 100);
        ok(overlapping.exactAtByte(1) != null && overlapping.exactAtByte(2) == null,
                "overlapping exact rows must remain ambiguous after source proof");
        rejects(IndexOutOfBoundsException.class, () -> proof.exactAtByte(5));
        rejects(IllegalArgumentException.class, () -> declared.verify(verifiedTree,
                sources::get, -1, 100));
        rejects(IllegalArgumentException.class, () -> declared.verify(verifiedTree,
                sources::get, 2, -1));
        // Admission budget is per independent source generation, not per row.
        var oneByteTreeBuilder = SyntaxTree.builder("one-byte", bytes("x"));
        oneByteTreeBuilder.open("source", "", 0, 0);
        oneByteTreeBuilder.close(1);
        var oneByteTree = oneByteTreeBuilder.build();
        int sourceCardinality = 1024;
        String xHash = sha(bytes("x"));
        var manySources = SyntaxOrigins.builder(oneByteTree);
        for (int i = 0; i < sourceCardinality; i++)
            manySources.add(0, 1, "imported-" + i, xHash, 0, 1,
                    SyntaxOrigins.Relation.EXACT_BYTES);
        var qualified = manySources.freeze().verify(oneByteTree,
                id -> bytes("x"), sourceCardinality, sourceCardinality + 1);
        ok(qualified.distinctSources() == sourceCardinality
                && qualified.retainedSourceBytes() == sourceCardinality
                && qualified.exactAtByte(0) == null,
                "bounded thousand-source multi-origin proof refuses ambiguity");
        System.out.println("SYNTAX_ORIGINS_PASS checks=" + checks);
    }
}
