// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Original Java21 proof of grammar-aware, position-independent syntax identities. */
public final class SyntaxIndexContract {
    private static int checks;
    private static void ok(boolean p, String why) {
        if (!p) throw new AssertionError(why);
        checks++;
    }
    private static void rejects(Class<? extends Throwable> type, Runnable run) {
        try { run.run(); }
        catch (Throwable t) {
            if (!type.isInstance(t)) throw new AssertionError("wrong refusal type", t);
            checks++;
            return;
        }
        throw new AssertionError("accepted invalid source/semantic state");
    }
    private static SyntaxTree tree(String original, int shift, String grammar,
                                     String firstKind, String firstField) {
        byte[] b = original.getBytes(StandardCharsets.UTF_8);
        SyntaxTree.Builder out = SyntaxTree.builder(grammar, b);
        out.open("file", "", 0, SyntaxTree.NAMED);
        out.leaf(firstKind, firstField, shift, shift + 1,
                SyntaxTree.NAMED | SyntaxTree.TOKEN);
        out.leaf("identifier", "right", shift + 2, shift + 3,
                SyntaxTree.NAMED | SyntaxTree.TOKEN);
        out.close(b.length);
        return out.build();
    }
    public static void main(String[] args) {
        var t1 = tree("x:y", 0, "tree-sitter/test", "identifier", "left");
        var t2 = tree("# x:y", 2, "tree-sitter/test", "identifier", "left");
        var t3 = tree("x;y", 0, "tree-sitter/test", "identifier", "left");
        var i1 = new SyntaxIndex(t1);
        ok(i1.subtreeSha256(1).equals("89cf93104399597a1c03ac0827233bb2b2412712f0f66156d6dc6009f0b9eb39")
                && i1.subtreeSha256(2).equals("90fdf3a005fdc288bbe8024a4501c62eb5211bec1adaef2da3f69f802207d747")
                && i1.rootSha256().equals("0acc56947aa1d8176851beffb35aece8bc0a67e62ca8b3ffe6bac5aa7490e747"),
                "V1 exact UTF-16 framed subtree SHA-256 identity");
        var i2 = new SyntaxIndex(t2);
        var i3 = new SyntaxIndex(t3);
        ok(i1.sameCandidateSubtree(1, i2, 1), "same token survives a text insertion and offset shift");
        ok(i1.sameCandidateSubtree(2, i2, 2), "same trailing occurrence survives relocation");
        ok(!i1.sameCandidateSubtree(0, i2, 0), "source-wide comment/gap changes root");
        ok(!i1.sameCandidateSubtree(0, i3, 0), "unmodeled punctuation survives root hashing");
        ok(!t1.projectionSha256().equals(t2.projectionSha256()), "nonmatching tree projection");
        ok(i1.firstChildWithField(0, "left") == 1 && i1.firstChildWithField(0, "absent") == -1,
                "field-based navigation");
        ok(Arrays.equals(i1.nodesOfKind("identifier"), new int[] {1, 2})
                && i1.nodesOfKind("missing").length == 0, "kind occurrence index");
        var changedKind = new SyntaxIndex(tree("x:y", 0, "tree-sitter/test", "name", "left"));
        ok(!i1.sameCandidateSubtree(1, changedKind, 1), "grammar-native kind is part of identity");
        var changedField = new SyntaxIndex(tree("x:y", 0, "tree-sitter/test", "identifier", "object"));
        ok(!i1.sameCandidateSubtree(1, changedField, 1), "edge field is part of identity");
        var otherGrammar = new SyntaxIndex(tree("x:y", 0, "babel/test", "identifier", "left"));
        ok(!i1.sameCandidateSubtree(1, otherGrammar, 1), "grammar identification is part of identity");
        var facts = SyntaxIndex.facts(t1)
                .add(1, "javac", "type", "java.lang.String")
                .add(1, "javac", "symbol", "pkg.T#s")
                .add(2, "clang", "diagnostic", "recovered")
                .add(1, "javac", "type", "nullable? false")
                .freeze();
        facts.requireSource(t1);
        ok(facts.size() == 4 && facts.count(1) == 3 && facts.count(2) == 1,
                "sparse grouped semantic columns");
        ok(facts.first(1, "javac:type").equals("java.lang.String")
                && facts.value(1, 2).equals("nullable? false"), "ordered duplicate facts");
        ok(facts.first(1, "unknown") == null && facts.count(0) == 0,
                "zero-or-more facts");
        rejects(IllegalStateException.class, () -> facts.requireSource(t2));
        rejects(IndexOutOfBoundsException.class, () -> SyntaxIndex.facts(t1).add(8,"x","k","v"));
        rejects(IllegalArgumentException.class, () -> SyntaxIndex.facts(t1).add(1,"x:y","k","v"));
        var once = SyntaxIndex.facts(t1); once.add(1,"javac","symbol","s"); once.freeze();
        rejects(IllegalStateException.class, () -> once.add(1,"javac","symbol","t"));
        rejects(IllegalStateException.class, once::freeze);
        ok(i1.rootSha256().matches("[0-9a-f]{64}")
                && i1.rootSha256().equals(new SyntaxIndex(t1).rootSha256()), "stable digests");
        // Full depth 5k is iterative; no recursive Java node graph or source rehash per ancestor.
        byte[] source = "x".getBytes(StandardCharsets.UTF_8);
        var b = SyntaxTree.builder("deep/1", source);
        for (int n=0; n<5000; n++) b.open("nest", "", 0, 0);
        for (int n=0; n<5000; n++) b.close(1);
        SyntaxIndex deep = new SyntaxIndex(b.build());
        ok(deep.rootSha256().matches("[0-9a-f]{64}"), "iterative bottom-up subtrees");
        System.out.println("SYNTAX_INDEX_PASS checks=" + checks);
    }
}
