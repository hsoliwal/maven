// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast;

import java.util.Objects;
import java.util.function.IntUnaryOperator;

/**
 * Thin coordinate adapter over the *existing* six-method ForeignNode shape.
 * Parser APIs report offsets in different units: Tree-sitter reports UTF-8
 * bytes, javac/JDT typically report UTF-16 code units. This wrapper performs
 * explicit validated translation without relabeling those units as bytes.
 * It creates no AST nodes and does not retain provider-owned parser objects.
 */
public final class SyntaxCoordinates {
    private SyntaxCoordinates() { }

    /**
     * Adapt a parser whose original startByte/endByte accessors return source
     * offsets in another declared coordinate system. The supplied mapper must
     * translate its native unit to a physical source byte offset and refuse
     * ambiguous/unrepresentable boundaries (such as mid-surrogate positions).
     */
    public static <N> SyntaxTree project(String grammar, byte[] source, N root,
            SyntaxTree.ForeignNode<N> nativeAdapter, IntUnaryOperator nativeToByte) {
        return SyntaxTree.project(grammar, source, root, byteAdapter(nativeAdapter, nativeToByte));
    }
    public static <N> SyntaxTree projectFragment(String grammar, byte[] source, N root,
            SyntaxTree.ForeignNode<N> nativeAdapter, IntUnaryOperator nativeToByte) {
        return SyntaxTree.projectFragment(grammar, source, root,
                byteAdapter(nativeAdapter, nativeToByte));
    }
    /** Useful for any parser with a native integer source-offset representation. */
    public static <N> SyntaxTree.ForeignNode<N> byteAdapter(
            SyntaxTree.ForeignNode<N> nativeAdapter, IntUnaryOperator nativeToByte) {
        Objects.requireNonNull(nativeAdapter, "nativeAdapter");
        Objects.requireNonNull(nativeToByte, "nativeToByte");
        return new SyntaxTree.ForeignNode<>() {
            @Override public String kind(N node) { return nativeAdapter.kind(node); }
            @Override public String field(N node) { return nativeAdapter.field(node); }
            @Override public int startByte(N node) {
                return nativeToByte.applyAsInt(nativeAdapter.startByte(node));
            }
            @Override public int endByte(N node) {
                return nativeToByte.applyAsInt(nativeAdapter.endByte(node));
            }
            @Override public int flags(N node) { return nativeAdapter.flags(node); }
            @Override public Iterable<N> children(N node) { return nativeAdapter.children(node); }
        };
    }
}
