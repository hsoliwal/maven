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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;
import org.apache.maven.plugins.clustering.Lexer.Kind;
import org.apache.maven.plugins.clustering.Lexer.Token;

/**
 * Lexical front end: atoms of the languages without a dedicated parser front end. In brace languages
 * ({@link BraceBlocks}), a block whose header names a type (class, struct, interface, enum, trait, impl, object,
 * record, union) is a TYPE atom. A block whose header has a name followed by a parameter list, or ends in an arrow, is
 * a METHOD atom. Control statements are not atoms. C, C++ and Objective-C headers follow {@link CFamilyHeader}. In
 * indentation languages ({@link IndentedAtoms}), {@code def} and {@code class} lines open atoms. The planes follow the
 * javac front end ({@link LexicalPlanes}). LOGIC numbers the names the atom declares: its own name, its parameters,
 * and names bound by a declaration keyword, {@code :=}, a plain assignment, or {@code in}/{@code of}. Other names and
 * all literals are kept. STRUCTURE keeps keywords and operators and reduces names and literals to their class. An
 * atom's lines are those of its first and last tokens.
 */
public final class LexicalAtoms {
    static final Set<String> BRACE = Set.of("c", "h", "cc", "cpp", "cxx", "hpp", "hh", "m", "mm", "cs", "js", "mjs",
            "cjs", "jsx", "ts", "tsx", "go", "rs", "kt", "kts", "scala", "swift", "php", "dart", "groovy");
    static final Set<String> INDENTED = Set.of("py");
    /** Words that open a control statement: a block after them is not an atom. */
    static final Set<String> CONTROL = Set.of("if", "for", "while", "switch", "catch", "synchronized",
            "using", "lock", "foreach", "elif", "when", "match", "guard", "defer", "unless", "do", "else", "try",
            "finally", "return", "with", "loop", "sizeof", "typeof", "new", "throw");
    private static final Set<String> TYPE_WORDS = Set.of("class", "struct", "interface", "enum", "trait", "impl",
            "object", "record", "union");
    /** Declaration keywords: a name after one is bound in the atom. */
    static final Set<String> DECLARE = Set.of("let", "const", "var", "val", "def", "fn", "func", "function",
            "fun", "auto", "local", "my");

    private LexicalAtoms() { }

    /**
     * An open block. {@code objc} marks an Objective-C method, whose symbol is its selector without a parameter
     * count, or an {@code @implementation}, which {@code @end} closes instead of a brace.
     */
    record Frame(int headerStart, String kind, String name, List<Token> params, boolean objc) {
        Frame(int headerStart, String kind, String name, List<Token> params) {
            this(headerStart, kind, name, params, false);
        }

        boolean container() {
            return objc && "TYPE".equals(kind);
        }

        Frame at(int start) {
            return new Frame(start, kind, name, params, objc);
        }
    }

    static boolean accepts(Path file) {
        String ext = AtomFrontEnd.extension(file);
        return BRACE.contains(ext) || INDENTED.contains(ext);
    }

    static List<Atom> scan(Path root, List<Path> files) throws IOException {
        List<Atom> atoms = new ArrayList<>();
        for (Path file : files) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String path = root.relativize(file).toString().replace('\\', '/');
            String extension = AtomFrontEnd.extension(file);
            boolean indented = INDENTED.contains(extension);
            List<Token> tokens = Lexer.tokens(text, indented);
            if (indented) IndentedAtoms.scan(path, text, tokens, atoms);
            else BraceBlocks.scan(path, text, tokens, atoms, extension);
        }
        return atoms;
    }

    /** The header is the tokens since the last {@code ; { }}; the part after its last unclosed bracket names the block. */
    static Frame classify(List<Token> header, int offset) {
        int tail = tail(header);
        List<Token> h = header.subList(tail, header.size());
        int start = offset + tail;
        if (h.isEmpty() || CONTROL.contains(h.get(0).text())) return new Frame(start, null, null, List.of());
        return goType(h, start).or(() -> type(h, start)).or(() -> method(h, start)).orElseGet(() -> arrow(h, start));
    }

    /** Where the part after the header's last unclosed parenthesis or square bracket starts; 0 when all are closed. */
    private static int tail(List<Token> header) {
        int unclosed = unclosed(header);
        for (int i = header.size() - 1, d = 0; i >= 0 && unclosed > 0; i--) {
            String t = header.get(i).text();
            if (t.equals(")") || t.equals("]")) d++;
            if (t.equals("(") || t.equals("[")) {
                if (d == 0) return i + 1;
                d--;
            }
        }
        return 0;
    }

    private static int unclosed(List<Token> header) {
        int depth = 0;
        for (Token token : header) {
            String t = token.text();
            if (t.equals("(") || t.equals("[")) depth++;
            if ((t.equals(")") || t.equals("]")) && depth > 0) depth--;
        }
        return depth;
    }

    /** Go {@code type Name struct}. */
    private static Optional<Frame> goType(List<Token> h, int start) {
        int n = h.size();
        return IntStream.iterate(n - 3, k -> k >= 0, k -> k - 1)
                .filter(k -> h.get(k).text().equals("type") && h.get(k + 1).kind() == Kind.ID
                        && TYPE_WORDS.contains(h.get(n - 1).text()))
                .mapToObj(k -> new Frame(start + k, "TYPE", h.get(k + 1).text(), List.of())).findFirst();
    }

    /** A type word and a name, before any parameter list or right before it ({@code class Foo(val a: Int)}). */
    private static Optional<Frame> type(List<Token> h, int start) {
        int paren = firstParen(h);
        return IntStream.range(0, h.size() - 1)
                .filter(k -> h.get(k).kind() == Kind.KEYWORD && TYPE_WORDS.contains(h.get(k).text())
                        && h.get(k + 1).kind() == Kind.ID && (paren < 0 || paren == k + 2))
                .mapToObj(k -> new Frame(start, "TYPE", h.get(k + 1).text(), List.of())).findFirst();
    }

    /** The first name followed by a parameter list outside brackets. */
    private static Optional<Frame> method(List<Token> h, int start) {
        return IntStream.range(0, h.size() - 1)
                .filter(k -> h.get(k).kind() == Kind.ID && h.get(k + 1).text().equals("(") && depthAt(h, k) == 0)
                .mapToObj(k -> new Frame(start, "METHOD", h.get(k).text(), group(h, k + 1))).findFirst();
    }

    /** A header that ends in an arrow, or an anonymous {@code function}: a METHOD; otherwise no atom. */
    private static Frame arrow(List<Token> h, int start) {
        boolean arrow = h.get(h.size() - 1).text().equals("=>");
        boolean anonymous = DECLARE.contains(h.get(0).text()) && h.get(0).text().startsWith("f");
        if (!arrow && !anonymous) return new Frame(start, null, null, List.of());
        int paren = firstParen(h);
        String name = h.size() > 1 && h.get(1).kind() == Kind.ID && DECLARE.contains(h.get(0).text()) ? h.get(1).text() : "<lambda>";
        return new Frame(start, "METHOD", name, paren < 0 ? List.of() : group(h, paren));
    }

    private static int firstParen(List<Token> h) {
        return IntStream.range(0, h.size()).filter(i -> h.get(i).text().equals("(")).findFirst().orElse(-1);
    }

    private static int depthAt(List<Token> h, int k) {
        int depth = 0;
        for (int i = 0; i < k; i++) {
            String t = h.get(i).text();
            if (t.equals("(") || t.equals("[") || t.equals("<")) depth++;
            if ((t.equals(")") || t.equals("]") || t.equals(">")) && depth > 0) depth--;
        }
        return depth;
    }

    /** Tokens inside the parenthesis group that opens at {@code open}; up to the end when it is not closed. */
    static List<Token> group(List<Token> h, int open) {
        int depth = 0;
        for (int i = open; i < h.size(); i++) {
            String t = h.get(i).text();
            if (t.equals("(")) depth++;
            if (t.equals(")") && --depth == 0) return h.subList(open + 1, i);
        }
        return h.subList(open + 1, h.size());
    }

    /** The names of the TYPE blocks that are open, outermost first, joined with {@code .}. */
    static String owners(Deque<Frame> open) {
        List<String> names = new ArrayList<>();
        open.descendingIterator().forEachRemaining(f -> {
            if ("TYPE".equals(f.kind())) names.add(f.name());
        });
        return String.join(".", names);
    }

    /** Drops the synthetic indentation tokens at either end, so nesting depth does not change an atom's planes. */
    static List<Token> trim(List<Token> span) {
        int from = 0;
        int to = span.size();
        while (from < to && span.get(from).start() == span.get(from).end()) from++;
        while (to > from && span.get(to - 1).start() == span.get(to - 1).end()) to--;
        return span.subList(from, to);
    }

    /**
     * The atom of a range that another front end found, for example a document symbol reported by a language
     * server: the range's tokens go through the same plane rules as the lexical front end's own atoms.
     *
     * The symbol is written as the lexical front end writes it: {@code Owner.name(parameterCount)} for a METHOD,
     * {@code Owner.Name} for a TYPE. The atom's lines are those of the first and last tokens in the range, counted in
     * the whole text, so a comment or blank line at either end of the range is not included.
     *
     * @param text     the whole file
     * @param from     the range's first character in {@code text}
     * @param to       the end of the range in {@code text} (exclusive)
     * @param indented true for indentation languages (comments start with {@code #})
     * @param name     the symbol's own name, declared first
     * @param owner    the enclosing type names joined with {@code .}, or empty
     */
    public static Atom rangeAtom(String path, String text, int from, int to, String kind, String name, String owner,
            boolean indented) {
        String source = text.substring(from, to);
        List<Token> span = trim(Lexer.tokens(source, indented));
        int lineBase = lineOf(text, from) - 1;
        if (span.isEmpty()) {
            return AtomBuilder.atom(path, kind, (owner.isEmpty() ? "" : owner + ".") + name, source)
                    .lines(lineBase + 1, lineOf(text, Math.max(from, to - 1))).build();
        }
        int paren = IntStream.range(0, span.size() - 1).filter(i -> span.get(i).text().equals(name)
                && span.get(i + 1).text().equals("(")).findFirst().orElse(-2) + 1;
        Frame frame = new Frame(0, kind, name, paren < 0 ? List.of() : group(span, paren));
        return LexicalPlanes.atom(path, source, span, frame, owner, lineBase);
    }

    /** The 1-based line of a character offset. */
    private static int lineOf(String text, int offset) {
        return 1 + (int) text.chars().limit(offset).filter(c -> c == '\n').count();
    }
}
