/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.recipes.javac;

import java.util.Optional;

import org.openrewrite.Tree;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JLeftPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.marker.Markers;

/**
 * Gives back the {@code +} chains of string literals that javac's parser folded into one literal. javac keeps its
 * default string folding, so the compile is the build's own; the parser visitor then makes one {@link J.Literal} whose
 * source is the whole chain. This visitor splits it into the left-associative {@link J.Binary} nodes the classic
 * parser (folding off) builds: a folded literal that ends a {@code +} chain is spliced into that chain, as javac
 * re-associated it. A chain is split only when its literals' values join to javac's value; a chain longer than
 * {@link #MAX_LITERALS}, one with escaped surrogates (whose source the parser visitor rewrites) or one that cannot be
 * read is left folded and counted, and the file is then a print mismatch.
 */
final class FoldedStrings extends JavaVisitor<Integer> {
    /** The most literals one folded literal is split into; deeper {@code +} trees risk the stack of the compile. */
    static final int MAX_LITERALS = 128;

    private final Optional<JavaType> stringType;
    private int unsplit;

    private FoldedStrings(Optional<JavaType> stringType) {
        this.stringType = stringType;
    }

    /**
     * @param lst the tree the parser visitor built
     * @param unsplit how many folded literals were left as one literal
     */
    record Result(J.CompilationUnit lst, int unsplit) {}

    /**
     * Splits every folded literal of a tree.
     *
     * @param lst the tree the parser visitor built from javac's trees
     * @param stringType the type of {@code java.lang.String} in the compile's type cache, for chains that are whole
     *     expressions; without it they are left folded
     * @return the tree and how many folded literals stayed folded
     */
    static Result unfold(J.CompilationUnit lst, Optional<JavaType> stringType) {
        FoldedStrings visitor = new FoldedStrings(stringType);
        J.CompilationUnit unfolded = (J.CompilationUnit) visitor.visitNonNull(lst, 0);
        return new Result(unfolded, visitor.unsplit);
    }

    @Override
    public J visitBinary(J.Binary binary, Integer p) {
        Optional<StringChain> chain =
                binary.getOperator() == J.Binary.Type.Addition ? splittable(binary.getRight()) : Optional.empty();
        if (chain.isEmpty()) {
            return super.visitBinary(binary, p);
        }
        Expression left = (Expression) visitNonNull(binary.getLeft(), p);
        Space before = binary.getPadding().getOperator().getBefore();
        J.Binary first =
                add(left, before, literal(chain.get(), 0, binary.getRight().getPrefix()), binary.getType());
        J.Binary chained = (J.Binary) appended(first, chain.get(), binary.getType());
        return chained.withPrefix(binary.getPrefix()).withMarkers(binary.getMarkers());
    }

    @Override
    public J visitLiteral(J.Literal literal, Integer p) {
        Optional<StringChain> read = read(literal);
        if (read.isEmpty() || !read.get().folded()) {
            return super.visitLiteral(literal, p);
        }
        Optional<StringChain> chain = splittable(literal).filter(c -> stringType.isPresent());
        if (chain.isEmpty()) {
            unsplit++;
            return super.visitLiteral(literal, p);
        }
        Expression chained = appended(literal(chain.get(), 0, Space.EMPTY), chain.get(), stringType.get());
        return chained.withPrefix(literal.getPrefix()).withMarkers(literal.getMarkers());
    }

    /** The chain of a string literal's source; empty for other literals and for text that cannot be read. */
    private static Optional<StringChain> read(J.Literal literal) {
        return literal.getType() == JavaType.Primitive.String && literal.getValueSource() != null
                ? StringChain.read(literal.getValueSource())
                : Optional.empty();
    }

    /** The chain of a folded literal that can be split as it is; empty for anything else. */
    private static Optional<StringChain> splittable(Expression expression) {
        if (!(expression instanceof J.Literal literal) || literal.getUnicodeEscapes() != null) {
            return Optional.empty();
        }
        return read(literal)
                .filter(StringChain::folded)
                .filter(chain -> chain.literals().size() <= MAX_LITERALS)
                .filter(chain -> chain.value().equals(literal.getValue()));
    }

    /** {@code start + l1 + l2 ...} for the chain's literals after the first, left-associative. */
    private static Expression appended(Expression start, StringChain chain, JavaType type) {
        Expression chained = start;
        for (int i = 1; i < chain.literals().size(); i++) {
            J.Literal next = literal(chain, i, Space.format(chain.after().get(i - 1)));
            chained = add(chained, Space.format(chain.before().get(i - 1)), next, type);
        }
        return chained;
    }

    private static J.Literal literal(StringChain chain, int index, Space prefix) {
        return new J.Literal(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                chain.values().get(index),
                chain.literals().get(index),
                null,
                JavaType.Primitive.String);
    }

    private static J.Binary add(Expression left, Space before, Expression right, JavaType type) {
        return new J.Binary(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                left,
                new JLeftPadded<>(before, J.Binary.Type.Addition, Markers.EMPTY),
                right,
                type);
    }
}
