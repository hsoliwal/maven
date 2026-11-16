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
import java.util.Arrays;
import java.util.List;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Real jdk.compiler TreeScanner -> parser-neutral primitive source projection. */
public final class SyntaxJavacContract {
    private SyntaxJavacContract() { }
    private static int checks;
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
    private static final class Node {
        final String kind;
        final int start, end;
        final List<Node> children = new ArrayList<>();
        Node(String kind, int start, int end) {
            this.kind = kind; this.start = start; this.end = end;
        }
    }
    private static final SyntaxTree.ForeignNode<Node> ADAPTER = new SyntaxTree.ForeignNode<>() {
        @Override public String kind(Node n) { return n.kind; }
        @Override public String field(Node n) { return ""; }
        @Override public int startByte(Node n) { return n.start; }
        @Override public int endByte(Node n) { return n.end; }
        @Override public int flags(Node n) { return SyntaxTree.NAMED; }
        @Override public Iterable<Node> children(Node n) { return n.children; }
    };
    public static void main(String[] args) throws Exception {
        String source = "class T { /* α */ int f(int x) { return x + 1; } }";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///T.java"),
                JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        JavacTask javac = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(
                null, null, null, List.of("-proc:none"), null, List.of(file));
        CompilationUnitTree compilation = javac.parse().iterator().next();
        SourcePositions positions = Trees.instance(javac).getSourcePositions();
        ArrayDeque<Node> parents = new ArrayDeque<>();
        Node[] root = {null};
        new TreeScanner<Void, Void>() {
            @Override public Void scan(Tree original, Void unused) {
                if (original == null) return null;
                long start = positions.getStartPosition(compilation, original);
                long end = positions.getEndPosition(compilation, original);
                if (start < 0 || end < start) return super.scan(original, unused);
                Node mapped = new Node(original.getKind().name(), utf8ByteAt(source, (int) start),
                        utf8ByteAt(source, (int) end));
                if (parents.isEmpty()) root[0] = mapped;
                else parents.peek().children.add(mapped);
                parents.push(mapped);
                super.scan(original, unused);
                parents.pop();
                return null;
            }
        }.scan(compilation, null);
        check(root[0] != null, "compilation node imported");
        SyntaxTree result = SyntaxTree.project("jdk.compiler/21", bytes, root[0], ADAPTER);
        check(result.size() >= 10 && result.kind(0).equals("COMPILATION_UNIT"),
                "real Java parser nodes");
        check(result.startByte(0) == 0 && result.endByte(0) == bytes.length,
                "compiler source range converted from UTF-16 to bytes");
        check(result.sourceLength() == source.length() + 1,
                "multibyte comment retains a wider physical byte position");
        check(Arrays.equals(result.sourceBytes(), bytes), "original parser source bytes retained");
        check(result.projectionSha256().matches("[a-f0-9]{64}"), "framed projection digest");
        for (int i = 1; i < result.size(); i++) {
            int parent = result.parent(i);
            check(parent >= 0 && parent < i && result.startByte(parent) <= result.startByte(i)
                    && result.endByte(i) <= result.endByte(parent), "nested compiler byte spans");
        }
        System.out.println("JAVAC_AST_PASS checks=" + checks);
    }
    private static int utf8ByteAt(String source, int utf16Offset) {
        // Fixture-only position oracle; product callers use a checked file source index.
        return source.substring(0, utf16Offset).getBytes(StandardCharsets.UTF_8).length;
    }
}
