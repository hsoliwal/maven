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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.apache.maven.plugins.clustering.Lexer.Kind;
import org.apache.maven.plugins.clustering.Lexer.Token;

/**
 * Objective-C syntax for the lexical front end ({@code .m}, {@code .mm}): a method definition ({@code -} or {@code +}
 * that starts the header or a line after macro names, an optional {@code (type)}, then the selector parts
 * {@code name:(type)argument}, or one name without arguments that is not a keyword other than {@code class},
 * {@code self} and {@code new}), {@code @implementation Name} or {@code @implementation Name (Category)}, and
 * {@code @end}. A sign after {@code case} or {@code operator} is never a method. C and C++ functions in
 * the same files keep the brace rules. A string {@code @"..."} lexes as {@code @} and a string literal, so braces in
 * it open nothing.
 */
final class ObjectiveC {
    static final Set<String> EXTENSIONS = Set.of("m", "mm");
    private static final Token COMMA = new Token(Kind.OP, ",", 0, 0, 0);
    /** Keywords of the shared keyword set that are also NSObject messages ({@code -class}, {@code -self}, {@code +new}). */
    private static final Set<String> MESSAGE_KEYWORDS = Set.of("class", "self", "new");

    /**
     * A method header: the index of its {@code -} or {@code +} in the header, its full selector ({@code drawRect:},
     * {@code initWithFrame:style:}, {@code dealloc}) and its argument names separated by commas.
     */
    record Method(int start, String selector, List<Token> args) { }

    /** An {@code @implementation}: its name ({@code Class} or {@code Class+Category}) and the index after its header. */
    record Implementation(String name, int next) { }

    private ObjectiveC() { }

    /**
     * The method a block header defines: a {@code -} or {@code +} that starts the header, or starts a line after
     * macro names only ({@code AWT_NS_WINDOW_IMPLEMENTATION} on the line above), then a whole method header to its
     * end. Any other sign is an operator ({@code case -kLimit:}, {@code operator-() const}).
     */
    static Optional<Method> method(List<Token> header) {
        return IntStream.range(0, header.size()).filter(k -> sign(header.get(k)) && starts(header, k))
                .mapToObj(k -> parse(header, k)).flatMap(Optional::stream).findFirst();
    }

    /** Whether the sign at {@code k} can start a method: first in the header, or first on its line after names only. */
    private static boolean starts(List<Token> h, int k) {
        return k == 0 || h.get(k - 1).endLine() < h.get(k).line() && !h.get(k - 1).text().equals("operator")
                && h.subList(0, k).stream().allMatch(t -> t.kind() == Kind.ID);
    }

    /** Whether the header holds {@code @} and a word, a directive such as {@code @synchronized} or {@code @interface}. */
    static boolean hasDirective(List<Token> header) {
        return IntStream.range(0, header.size() - 1).anyMatch(i -> at(header.get(i)) && word(header.get(i + 1)));
    }

    static Optional<Implementation> implementation(List<Token> tokens, int i) {
        if (!directive(tokens, i, "implementation") || i + 2 >= tokens.size() || tokens.get(i + 2).kind() != Kind.ID) {
            return Optional.empty();
        }
        String name = tokens.get(i + 2).text();
        boolean category = i + 5 < tokens.size() && tokens.get(i + 3).text().equals("(")
                && tokens.get(i + 4).kind() == Kind.ID && tokens.get(i + 5).text().equals(")");
        return Optional.of(category ? new Implementation(name + "+" + tokens.get(i + 4).text(), i + 6)
                : new Implementation(name, i + 3));
    }

    static boolean end(List<Token> tokens, int i) {
        return directive(tokens, i, "end");
    }

    private static Optional<Method> parse(List<Token> h, int k) {
        int i = k + 1 < h.size() && h.get(k + 1).text().equals("(") ? skip(h, k + 1) : k + 1;
        StringBuilder selector = new StringBuilder();
        List<Token> args = new ArrayList<>();
        int next;
        while ((next = part(h, i, selector, args)) > i) i = next;
        if (selector.length() == 0 && i < h.size() && word(h.get(i))) {
            if (!message(h.get(i))) return Optional.empty();
            selector.append(h.get(i++).text());
        }
        boolean rest = h.subList(i, h.size()).stream()
                .allMatch(t -> t.kind() == Kind.ID || t.text().equals(",") || t.text().equals("..."));
        return selector.length() > 0 && rest ? Optional.of(new Method(k, selector.toString(), args)) : Optional.empty();
    }

    /** One selector part {@code name:} or {@code :} and its {@code (type)argument}; returns the index after it, {@code i} when there is none. */
    private static int part(List<Token> h, int i, StringBuilder selector, List<Token> args) {
        boolean named = i + 1 < h.size() && word(h.get(i)) && h.get(i + 1).text().equals(":");
        if (!named && !(i < h.size() && h.get(i).text().equals(":"))) return i;
        selector.append(named ? h.get(i).text() : "").append(':');
        int j = named ? i + 2 : i + 1;
        j = j < h.size() && h.get(j).text().equals("(") ? skip(h, j) : j;
        if (j < h.size() && word(h.get(j))) {
            if (!args.isEmpty()) args.add(COMMA);
            args.add(h.get(j++));
        }
        return j;
    }

    /** The index after the parenthesis that closes the one at {@code open}. */
    private static int skip(List<Token> h, int open) {
        int depth = 0;
        for (int i = open; i < h.size(); i++) {
            if (h.get(i).text().equals("(")) depth++;
            if (h.get(i).text().equals(")") && --depth == 0) return i + 1;
        }
        return h.size();
    }

    private static boolean directive(List<Token> tokens, int i, String word) {
        return i + 1 < tokens.size() && at(tokens.get(i)) && tokens.get(i + 1).text().equals(word)
                && tokens.get(i).end() == tokens.get(i + 1).start();
    }

    /** A selector without arguments: a name, or one of the keywords NSObject answers as messages. */
    private static boolean message(Token t) {
        return t.kind() == Kind.ID || MESSAGE_KEYWORDS.contains(t.text());
    }

    private static boolean sign(Token t) {
        return t.kind() == Kind.OP && (t.text().equals("-") || t.text().equals("+"));
    }

    private static boolean at(Token t) {
        return t.kind() == Kind.OP && t.text().equals("@");
    }

    private static boolean word(Token t) {
        return t.kind() == Kind.ID || t.kind() == Kind.KEYWORD;
    }
}
