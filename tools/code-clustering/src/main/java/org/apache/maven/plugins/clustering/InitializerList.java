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
package org.apache.maven.plugins.clustering;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.apache.maven.plugins.clustering.Lexer.Kind;
import org.apache.maven.plugins.clustering.Lexer.Token;

/**
 * The token rules of a C++ member-initializer list that {@link BraceBlocks} follows: which header may start one, when
 * a brace belongs to an element, when a {@code ,} ends an element, and which tokens may follow an element's brace.
 */
final class InitializerList {
    /** Tokens that may follow an initializer-list element's closing brace: the list goes on or the body opens. */
    static final Set<String> GOES_ON = Set.of(",", ":", "...", "{");
    private static final Set<String> BRACES = Set.of("{", "}");

    private InitializerList() { }

    /**
     * Whether a header may be followed by a member-initializer list: it ends in its function's parameter list
     * ({@link CFunctionName#endsInParameters}) and is not a class head ({@code struct ALIGNED(16) Vec : Base},
     * {@code struct DECLARE(Foo) : Base}).
     */
    static boolean constructorHead(List<Token> h) {
        return CFunctionName.endsInParameters(h) && !"TYPE".equals(LexicalAtoms.classify(h, 0).kind());
    }

    /**
     * Whether the tokens of an element so far are only its qualified name ({@code fOrigin}, {@code Base<T>},
     * {@code ns::Base}), so that a brace after them is its initializer. After an initializer ({@code fB(2)},
     * {@code fA{1}}) or a macro that stands for one ({@code st STATE_DEFAULT}) the brace is the body.
     */
    static boolean elementName(List<Token> t) {
        int[] angle = new int[t.size() + 1];
        IntStream.range(0, t.size()).forEach(k -> angle[k + 1] = angle[k] + switch (t.get(k).text()) {
            case "<" -> 1;
            case ">" -> -1;
            default -> 0;
        });
        return !t.isEmpty() && IntStream.range(0, t.size()).allMatch(k -> angle[k] > 0 ? !BRACES.contains(t.get(k).text())
                : t.get(k).text().equals("::") || t.get(k).text().equals("<") || name(t, k));
    }

    /** A name at the start of an element or after {@code ::}. */
    private static boolean name(List<Token> t, int k) {
        return t.get(k).kind() == Kind.ID && (k == 0 || t.get(k - 1).text().equals("::"));
    }

    /**
     * Template argument lists still open in the tokens of an element, counting {@code <} and {@code >} outside
     * parentheses, brackets and braces: a {@code ,} inside {@code Base<A, B>} does not end the element.
     */
    static int openAngles(List<Token> t) {
        int[] nest = new int[t.size() + 1];
        IntStream.range(0, t.size()).forEach(k -> nest[k + 1] = nest[k] + switch (t.get(k).text()) {
            case "(", "[", "{" -> 1;
            case ")", "]", "}" -> -1;
            default -> 0;
        });
        return IntStream.range(0, t.size()).filter(k -> nest[k] == 0)
                .map(k -> t.get(k).text().equals("<") ? 1 : t.get(k).text().equals(">") ? -1 : 0).sum();
    }
}
