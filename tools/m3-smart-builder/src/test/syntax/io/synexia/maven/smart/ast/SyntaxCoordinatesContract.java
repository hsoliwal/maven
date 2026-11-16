// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Real public javac AST projected through one declared UTF-16-to-byte adapter. */
public final class SyntaxCoordinatesContract {
    private static int checks;
    private static void ok(boolean p, String why) {
        if (!p) throw new AssertionError(why);
        checks++;
    }
    private static final class N {
        final String kind;
        final int startUtf16, endUtf16;
        final List<N> children = new ArrayList<>();
        N(String kind, int start, int end) {
            this.kind = kind; startUtf16=start; endUtf16=end;
        }
    }
    private static final SyntaxTree.ForeignNode<N> UTF16 = new SyntaxTree.ForeignNode<>() {
        @Override public String kind(N n) { return n.kind; }
        @Override public String field(N n) { return ""; }
        @Override public int startByte(N n) { return n.startUtf16; }
        @Override public int endByte(N n) { return n.endUtf16; }
        @Override public int flags(N n) { return SyntaxTree.NAMED; }
        @Override public Iterable<N> children(N n) { return n.children; }
    };
    public static void main(String[] args) throws Exception {
        String code = "class T { /* α */ String s = \"😀\"; int f() { return 1; } }";
        byte[] source = code.getBytes(StandardCharsets.UTF_8);
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///T.java"),
                JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return code; }
        };
        JavacTask parser = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(
                null, null, null, List.of("-proc:none"), null, List.of(file));
        CompilationUnitTree unit = parser.parse().iterator().next();
        SourcePositions positions = Trees.instance(parser).getSourcePositions();
        ArrayDeque<N> parents = new ArrayDeque<>();
        N[] root = {null};
        new TreeScanner<Void, Void>() {
            @Override public Void scan(Tree tree, Void unused) {
                if (tree == null) return null;
                long from = positions.getStartPosition(unit, tree);
                long to = positions.getEndPosition(unit, tree);
                if (from < 0 || to < from) return super.scan(tree, unused);
                N n = new N(tree.getKind().name(), Math.toIntExact(from), Math.toIntExact(to));
                if (parents.isEmpty()) root[0] = n;
                else parents.peek().children.add(n);
                parents.push(n);
                super.scan(tree, unused);
                parents.pop();
                return null;
            }
        }.scan(unit, null);
        // Oracle only: a file source's byteAtUtf16 supplies this in real file consumers.
        IntUnaryOperator mapper = index -> {
            if (index < 0 || index > code.length()) throw new IndexOutOfBoundsException(index);
            if (index > 0 && index < code.length()
                    && Character.isHighSurrogate(code.charAt(index-1))
                    && Character.isLowSurrogate(code.charAt(index)))
                throw new IllegalArgumentException("split surrogate pair");
            return code.substring(0,index).getBytes(StandardCharsets.UTF_8).length;
        };
        SyntaxTree mapped = SyntaxCoordinates.project("jdk.compiler/21", source,
                root[0], UTF16, mapper);
        ok(mapped.size() >= 5 && mapped.kind(0).equals("COMPILATION_UNIT"), "real JDK21 tree");
        ok(mapped.startByte(0) == 0 && mapped.endByte(0) == source.length,
                "UTF-16 to UTF-8 mapping over non-ASCII and surrogate pairs");
        ok(mapped.projectionSha256().matches("[0-9a-f]{64}"), "projection retains grammar/source");
        for (int i = 1; i < mapped.size(); i++) {
            int parent = mapped.parent(i);
            ok(parent >= 0 && mapped.startByte(i) >= mapped.startByte(parent)
                    && mapped.endByte(i) <= mapped.endByte(parent), "nested converted byte ranges");
        }
        N fragment = new N("identifier", 9, 10);
        var leaf = SyntaxCoordinates.projectFragment("jdt/fixture", source, fragment,
                UTF16, mapper);
        ok(leaf.size() == 2 && leaf.startByte(1) == mapper.applyAsInt(9), "fragment offsets");
        int emoji = code.indexOf("😀");
        try {
            mapper.applyAsInt(emoji+1);
            throw new AssertionError("accepted surrogate midpoint");
        } catch (IllegalArgumentException expected) { checks++; }
        System.out.println("SYNTAX_COORDINATES_PASS checks="+checks);
    }
}
