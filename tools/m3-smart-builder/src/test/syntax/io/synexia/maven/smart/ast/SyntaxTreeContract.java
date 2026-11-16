// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/** Standalone Java 21 contract; Maven/SDK and installed-image proof remain separate. */
public final class SyntaxTreeContract {
    private static int checks;
    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
        checks++;
    }
    private static void rejects(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("expected rejection");
    }
    private record N(String kind, String field, int start, int end, int flags, List<N> children) { }
    private static final SyntaxTree.ForeignNode<N> ADAPTER = new SyntaxTree.ForeignNode<>() {
        public String kind(N n) { return n.kind; }
        public String field(N n) { return n.field; }
        public int startByte(N n) { return n.start; }
        public int endByte(N n) { return n.end; }
        public int flags(N n) { return n.flags; }
        public Iterable<N> children(N n) { return n.children; }
    };
    public static void main(String[] args) {
        byte[] source = " x\r\n😀;".getBytes(StandardCharsets.UTF_8);
        int n = source.length;
        N root = new N("source_file", null, 0, n, SyntaxTree.NAMED, List.of(
                new N("identifier", "name", 1, 2,
                        SyntaxTree.NAMED | SyntaxTree.TOKEN, List.of()),
                new N("comment", "", 2, 4,
                        SyntaxTree.TRIVIA | SyntaxTree.TOKEN, List.of()),
                new N("ERROR", "", 4, 8, SyntaxTree.ERROR | SyntaxTree.TOKEN, List.of()),
                new N("missing", "expected", 8, 8,
                        SyntaxTree.MISSING | SyntaxTree.TOKEN, List.of())));
        SyntaxTree tree = SyntaxTree.project("tree-sitter/java@fixture", source, root, ADAPTER);
        check(tree.size() == 5 && tree.parent(4) == 0, "dense preorder");
        check(tree.firstChild(0) == 1 && tree.nextSibling(1) == 2
                && tree.nextSibling(3) == 4 && tree.nextSibling(4) == -1, "sibling indexes");
        check(tree.kind(3).equals("ERROR") && tree.field(4).equals("expected"), "foreign names");
        check(tree.hasFlag(2, SyntaxTree.TRIVIA) && tree.hasFlag(4, SyntaxTree.MISSING), "flags");
        check(Arrays.equals(tree.sourceBytes(), source) && tree.deepestAtByte(0) == 0,
                "exact source and unparsed gaps");
        check(tree.deepestAtByte(4) == 3 && tree.deepestAtByte(n) == -1, "byte lookup");
        check(tree.sourceSha256().matches("[a-f0-9]{64}")
                && tree.projectionSha256().matches("[a-f0-9]{64}"), "hash");
        check(tree.projectionSha256().equals(
                SyntaxTree.project("tree-sitter/java@fixture", source, root, ADAPTER)
                        .projectionSha256()), "fixed shape identity");
        N fragment = new N("method", "body", 1, 2, SyntaxTree.NAMED, List.of());
        var projected = SyntaxTree.projectFragment("eclipse/jdt@fixture", source, fragment, ADAPTER);
        check(projected.size() == 2 && projected.kind(0).equals("m3.source_image")
                && projected.startByte(1) == 1 && projected.endByte(1) == 2,
                "partial AST maps through synthetic root");
        var invalid = SyntaxTree.builder("grammar", source);
        invalid.open("root", "", 0, 0);
        invalid.leaf("first", "", 1, 3, SyntaxTree.TOKEN);
        rejects(() -> invalid.leaf("overlap", "", 2, 4, SyntaxTree.TOKEN));
        rejects(() -> invalid.leaf("missing", "", 3, 4, SyntaxTree.MISSING));
        invalid.close(n);
        invalid.build();
        byte[] mutated = tree.sourceBytes(); mutated[0]++;
        check(Arrays.equals(tree.sourceBytes(), source), "defensive source ownership");
        // Iterative importer: a deep external tree must not recurse Java stack.
        N deep = new N("bottom", "", 0, n, 0, List.of());
        for (int i = 0; i < 5000; i++) deep = new N("nested", "", 0, n, 0, List.of(deep));
        check(SyntaxTree.project("deep/fixture", source, deep, ADAPTER).size() == 5001,
                "iterative deep projection");
        // Reject cycles before they can exhaust memory in a foreign provider.
        final class Cyclic {
            final List<Cyclic> children = new ArrayList<>();
        }
        SyntaxTree.ForeignNode<Cyclic> cyclicAdapter = new SyntaxTree.ForeignNode<>() {
            public String kind(Cyclic node) { return "cycle"; }
            public String field(Cyclic node) { return ""; }
            public int startByte(Cyclic node) { return 0; }
            public int endByte(Cyclic node) { return n; }
            public int flags(Cyclic node) { return 0; }
            public Iterable<Cyclic> children(Cyclic node) { return node.children; }
        };
        Cyclic loop = new Cyclic();
        loop.children.add(loop);
        rejects(() -> SyntaxTree.project("cyclic/fixture", source, loop, cyclicAdapter));
        rejects(() -> SyntaxTree.projectBounded("cyclic/fixture", source,
                loop, cyclicAdapter, 5, 5));
        // Bounded import refuses both depth and node count without recursion.
        try {
            SyntaxTree.projectBounded("tree-sitter/java@fixture", source, root, ADAPTER, 3, 8);
            throw new AssertionError("node budget accepted");
        } catch (IllegalStateException expected) { checks++; }
        try {
            SyntaxTree.projectBounded("tree-sitter/java@fixture", source, root, ADAPTER, 5, 1);
            throw new AssertionError("depth budget accepted");
        } catch (IllegalStateException expected) { checks++; }
        check(SyntaxTree.projectBounded("tree-sitter/java@fixture", source,
                root, ADAPTER, 5, 5).projectionSha256().equals(tree.projectionSha256()),
                "bounded and default projection are byte-identical");
        check(SyntaxTree.projectFragmentBounded("jdt/fixture", source,
                fragment, ADAPTER, 1, 1).size() == 2, "fragment wrapper excluded from quota");
        rejects(() -> SyntaxTree.projectBounded("tree-sitter/java@fixture",
                source, root, ADAPTER, 0, 5));
        // A provider that never finishes producing children must hit the bound.
        final class Infinite { }
        Infinite endless = new Infinite();
        SyntaxTree.ForeignNode<Infinite> infiniteAdapter = new SyntaxTree.ForeignNode<>() {
            public String kind(Infinite item) { return "infinite"; }
            public String field(Infinite item) { return ""; }
            public int startByte(Infinite item) { return 0; }
            public int endByte(Infinite item) { return n; }
            public int flags(Infinite item) { return 0; }
            public Iterable<Infinite> children(Infinite item) {
                return () -> Collections.nCopies(1000, new Infinite()).iterator();
            }
        };
        try {
            SyntaxTree.projectBounded("infinite/fixture", source,
                    endless, infiniteAdapter, 6, 6);
            throw new AssertionError("foreign provider quota accepted");
        } catch (IllegalStateException expected) { checks++; }
        System.out.println("SYNTAX_TREE_PASS checks=" + checks);
    }
}
