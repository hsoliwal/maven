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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.maven.plugins.clustering.LexicalAtoms.Frame;
import org.apache.maven.plugins.clustering.Lexer.Kind;
import org.apache.maven.plugins.clustering.Lexer.Token;

/**
 * The name of a C, C++ or Objective-C function in a block header. Candidates are the names followed by a parameter
 * list at the top level of the header (outside parentheses, brackets and angle brackets), and {@code operator} with
 * its symbol ({@code operator+}, {@code operator()}, {@code operator new[]}, {@code operator bool}). A destructor is
 * {@code ~Name}. The function is the first candidate that is not a macro call: a candidate is a macro call when a
 * declaration word (a name or keyword other than {@link #TRAILING}) follows its argument list and another candidate
 * comes later, as in {@code DEFINE(Int, jint)} or {@code METHODDEF(void)} on the line above a function. Candidates
 * after a member-initializer list ({@code : base(a), m(0)}) do not count, and specifiers with an argument
 * ({@code noexcept(true)}, {@code __attribute__((hot))}) are never candidates. The atom starts after the last macro
 * call and after an access label ({@code public:}).
 */
final class CFunctionName {
    /** C and C++ words that take a parenthesized argument in a function header but do not name the function. */
    static final Set<String> SPECIFIERS = Set.of("noexcept", "decltype", "requires", "alignas", "__attribute__",
            "__declspec", "__typeof__", "typeof", "asm", "__asm__");
    /** Words that may follow a parameter list without starting another declaration. */
    static final Set<String> TRAILING = Stream.concat(SPECIFIERS.stream(),
            Stream.of("override", "final", "try", "mutable", "throw")).collect(Collectors.toUnmodifiableSet());
    private static final String OPERATOR = "operator";

    /** A candidate: where its name starts, the name, its parameter list's parenthesis and the index after the list. */
    private record Candidate(int start, String name, int paren, int after, boolean operator) { }

    private final List<Token> h;
    private final int[] depth;
    private final List<Candidate> candidates;

    private CFunctionName(List<Token> h) {
        this.h = h;
        this.depth = depths(h);
        this.candidates = candidates();
    }

    /**
     * Whether a frame that the shared rules gave a C-family header gets its name here: a METHOD, or a header without a
     * kind that defines an operator and does not open a control statement.
     */
    static boolean applies(List<Token> header, int offset, Frame frame) {
        if ("METHOD".equals(frame.kind())) return true;
        List<Token> h = header.subList(frame.headerStart() - offset, header.size());
        return frame.kind() == null && !h.isEmpty() && !LexicalAtoms.CONTROL.contains(h.get(0).text())
                && IntStream.range(0, h.size()).anyMatch(i -> operator(h, i).isPresent());
    }

    /** The function the header names, or {@code frame} unchanged when it has no candidate. */
    static Frame of(List<Token> header, int offset, Frame frame) {
        int from = frame.headerStart() - offset;
        CFunctionName name = new CFunctionName(header.subList(from, header.size()));
        return IntStream.range(0, name.candidates.size()).filter(c -> !name.macroCall(c)).boxed().findFirst()
                .map(c -> name.frame(c, offset + from)).orElse(frame);
    }

    private Frame frame(int c, int offset) {
        Candidate named = candidates.get(c);
        int afterMacros = c == 0 ? 0 : candidates.get(c - 1).after();
        int afterLabel = IntStream.range(0, named.start()).filter(i -> depth[i] == 0 && h.get(i).text().equals(":"))
                .max().orElse(-1) + 1;
        return new Frame(offset + Math.max(afterMacros, afterLabel), "METHOD", named.name(),
                LexicalAtoms.group(h, named.paren()));
    }

    /** The candidates in order, up to a member-initializer list after the first one's parameter list. */
    private List<Candidate> candidates() {
        List<Candidate> all = IntStream.range(0, h.size()).filter(i -> depth[i] == 0).mapToObj(this::candidate)
                .flatMap(Optional::stream).toList();
        if (all.isEmpty()) return all;
        int stop = IntStream.range(all.get(0).after(), h.size()).filter(i -> depth[i] == 0 && h.get(i).text().equals(":"))
                .findFirst().orElse(h.size());
        return all.stream().filter(c -> c.start() < stop).toList();
    }

    private Optional<Candidate> candidate(int i) {
        Token t = h.get(i);
        if (t.kind() != Kind.ID) return Optional.empty();
        Optional<Candidate> operator = operator(h, i);
        boolean call = i + 1 < h.size() && h.get(i + 1).text().equals("(") && !SPECIFIERS.contains(t.text());
        boolean converted = i > 0 && h.get(i - 1).text().equals(OPERATOR);
        if (operator.isPresent() || !call || converted) return operator;
        boolean destructor = i > 0 && h.get(i - 1).text().equals("~");
        String name = (destructor ? "~" : "") + t.text();
        return Optional.of(new Candidate(destructor ? i - 1 : i, name, i + 1, after(h, i + 1), false));
    }

    /** {@code operator} at {@code i} with a symbol before its parameter list ({@code operator()} has {@code ()}). */
    private static Optional<Candidate> operator(List<Token> h, int i) {
        if (!h.get(i).text().equals(OPERATOR) || h.get(i).kind() != Kind.ID) return Optional.empty();
        boolean call = i + 3 < h.size() && h.get(i + 1).text().equals("(") && h.get(i + 2).text().equals(")")
                && h.get(i + 3).text().equals("(");
        int paren = call ? i + 3 : IntStream.range(i + 1, h.size()).filter(k -> h.get(k).text().equals("(")).findFirst().orElse(-1);
        if (paren <= i + 1) return Optional.empty();
        return Optional.of(new Candidate(i, OPERATOR + symbol(h.subList(i + 1, paren)), paren, after(h, paren), true));
    }

    /** The operator's symbol as written, with a space before a word ({@code new[]}, {@code  bool}, {@code +}). */
    private static String symbol(List<Token> parts) {
        StringBuilder s = new StringBuilder();
        parts.forEach(t -> s.append(word(t) && (s.length() == 0 || Character.isLetterOrDigit(s.charAt(s.length() - 1)))
                ? " " : "").append(t.text()));
        return s.toString();
    }

    /** Whether candidate {@code c} is a macro call: a declaration word follows its arguments and a candidate follows. */
    private boolean macroCall(int c) {
        Candidate candidate = candidates.get(c);
        if (candidate.operator() || c == candidates.size() - 1 || candidate.after() >= h.size()) return false;
        Token next = h.get(candidate.after());
        return word(next) && !TRAILING.contains(next.text());
    }

    /** The index after the parenthesis that closes the one at {@code open}; the size when it is not closed. */
    private static int after(List<Token> h, int open) {
        return Math.min(h.size(), open + LexicalAtoms.group(h, open).size() + 2);
    }

    private static boolean word(Token t) {
        return t.kind() == Kind.ID || t.kind() == Kind.KEYWORD;
    }

    /** Bracket depth before each token: parentheses, square and angle brackets, never below 0. */
    private static int[] depths(List<Token> h) {
        int[] depth = new int[h.size() + 1];
        IntStream.range(0, h.size()).forEach(i -> depth[i + 1] = Math.max(0, depth[i] + switch (h.get(i).text()) {
            case "(", "[", "<" -> 1;
            case ")", "]", ">" -> -1;
            default -> 0;
        }));
        return depth;
    }
}
