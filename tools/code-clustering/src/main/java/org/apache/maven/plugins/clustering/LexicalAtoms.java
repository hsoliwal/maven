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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;
import org.apache.maven.plugins.clustering.Lexer.Kind;
import org.apache.maven.plugins.clustering.Lexer.Token;

/**
 * Lexical front end: atoms of the languages without a dedicated parser front end. In brace languages, a block whose
 * header names a type (class, struct, interface, enum, trait, impl, object, record, union) is a TYPE atom. A block
 * whose header has a name followed by a parameter list, or ends in an arrow, is a METHOD atom. Control statements
 * are not atoms. In indentation languages, {@code def} and {@code class} lines open atoms that run to the next line
 * indented as far or less, with their decorators. The planes follow the javac front end. LOGIC numbers the names
 * the atom declares: its own name, its parameters, and names bound by a declaration keyword, {@code :=}, a plain
 * assignment, or {@code in}/{@code of}. Other names and all literals are kept. STRUCTURE keeps keywords and
 * operators and reduces names and literals to their class.
 */
public final class LexicalAtoms {
    static final Set<String> BRACE = Set.of("c", "h", "cc", "cpp", "cxx", "hpp", "hh", "cs", "js", "mjs", "cjs", "jsx",
            "ts", "tsx", "go", "rs", "kt", "kts", "scala", "swift", "php", "dart", "groovy");
    static final Set<String> INDENTED = Set.of("py");
    private static final Set<String> TYPE_WORDS = Set.of("class", "struct", "interface", "enum", "trait", "impl",
            "object", "record", "union");
    private static final Set<String> CONTROL = Set.of("if", "for", "while", "switch", "catch", "synchronized",
            "using", "lock", "foreach", "elif", "when", "match", "guard", "defer", "unless", "do", "else", "try",
            "finally", "return", "with", "loop", "sizeof", "typeof", "new", "throw");
    private static final Set<String> DECLARE = Set.of("let", "const", "var", "val", "def", "fn", "func", "function",
            "fun", "auto", "local", "my");

    private LexicalAtoms() { }

    static boolean accepts(Path file) {
        String ext = extension(file);
        return BRACE.contains(ext) || INDENTED.contains(ext);
    }

    static List<Atom> scan(Path root, List<Path> files) throws IOException {
        List<Atom> atoms = new ArrayList<>();
        for (Path file : files) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String path = root.relativize(file).toString().replace('\\', '/');
            boolean indented = INDENTED.contains(extension(file));
            List<Token> tokens = Lexer.tokens(text, indented);
            if (indented) indentedAtoms(path, text, tokens, atoms);
            else braceAtoms(path, text, tokens, atoms);
        }
        return atoms;
    }

    private record Frame(int headerStart, String kind, String name, List<Token> params) { }

    private static void braceAtoms(String path, String text, List<Token> tokens, List<Atom> atoms) {
        Deque<Frame> open = new ArrayDeque<>();
        int boundary = 0;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i).kind() == Kind.OP ? tokens.get(i).text() : "";
            if (t.equals("{")) {
                Frame frame = classify(tokens.subList(boundary, i), boundary);
                open.push(frame);
                boundary = i + 1;
            } else if (t.equals("}") && !open.isEmpty()) {
                Frame frame = open.pop();
                if (frame.kind() != null) {
                    atoms.add(atom(path, text, tokens.subList(frame.headerStart(), i + 1), frame, owners(open)));
                }
                boundary = i + 1;
            } else if (t.equals(";")) {
                boundary = i + 1;
            }
        }
    }

    /** The header is the tokens since the last {@code ; { }}; the part after its last unclosed bracket names the block. */
    private static Frame classify(List<Token> header, int offset) {
        int tail = 0;
        int depth = 0;
        for (int i = 0; i < header.size(); i++) {
            String t = header.get(i).text();
            if (t.equals("(") || t.equals("[")) depth++;
            if ((t.equals(")") || t.equals("]")) && depth > 0) depth--;
        }
        for (int i = header.size() - 1, d = 0; i >= 0 && depth > 0; i--) {
            String t = header.get(i).text();
            if (t.equals(")") || t.equals("]")) d++;
            if (t.equals("(") || t.equals("[")) {
                if (d == 0) {
                    tail = i + 1;
                    break;
                }
                d--;
            }
        }
        List<Token> h = header.subList(tail, header.size());
        int start = offset + tail;
        if (h.isEmpty() || CONTROL.contains(h.get(0).text())) return new Frame(start, null, null, List.of());
        int n = h.size();
        for (int k = n - 3; k >= 0; k--) {
            if (h.get(k).text().equals("type") && h.get(k + 1).kind() == Kind.ID && TYPE_WORDS.contains(h.get(n - 1).text())) {
                return new Frame(start + k, "TYPE", h.get(k + 1).text(), List.of());
            }
        }
        for (int k = 0; k + 1 < h.size(); k++) {
            if (h.get(k).kind() == Kind.KEYWORD && TYPE_WORDS.contains(h.get(k).text()) && h.get(k + 1).kind() == Kind.ID
                    && (firstParen(h) < 0 || firstParen(h) == k + 2)) return new Frame(start, "TYPE", h.get(k + 1).text(), List.of());
        }
        for (int k = 0; k + 1 < h.size(); k++) {
            if (h.get(k).kind() == Kind.ID && h.get(k + 1).text().equals("(") && depthAt(h, k) == 0) {
                return new Frame(start, "METHOD", h.get(k).text(), group(h, k + 1));
            }
        }
        boolean arrow = h.get(h.size() - 1).text().equals("=>");
        boolean anonymous = DECLARE.contains(h.get(0).text()) && h.get(0).text().startsWith("f");
        if (!arrow && !anonymous) return new Frame(start, null, null, List.of());
        int paren = firstParen(h);
        String name = h.size() > 1 && h.get(1).kind() == Kind.ID && DECLARE.contains(h.get(0).text()) ? h.get(1).text() : "<lambda>";
        return new Frame(start, "METHOD", name, paren < 0 ? List.of() : group(h, paren));
    }

    private static int firstParen(List<Token> h) {
        for (int i = 0; i < h.size(); i++) if (h.get(i).text().equals("(")) return i;
        return -1;
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

    /** Tokens inside the bracket group that opens at {@code open}. */
    private static List<Token> group(List<Token> h, int open) {
        int depth = 0;
        for (int i = open; i < h.size(); i++) {
            String t = h.get(i).text();
            if (t.equals("(")) depth++;
            if (t.equals(")") && --depth == 0) return h.subList(open + 1, i);
        }
        return h.subList(open + 1, h.size());
    }

    private static String owners(Deque<Frame> open) {
        List<String> names = new ArrayList<>();
        open.descendingIterator().forEachRemaining(f -> {
            if ("TYPE".equals(f.kind())) names.add(f.name());
        });
        return String.join(".", names);
    }

    private static void indentedAtoms(String path, String text, List<Token> tokens, List<Atom> atoms) {
        String[] lines = text.split("\n", -1);
        int[] offsets = new int[lines.length + 1];
        for (int i = 0; i < lines.length; i++) offsets[i + 1] = offsets[i] + lines[i].length() + 1;
        List<int[]> classes = new ArrayList<>();
        List<String> classNames = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].strip();
            boolean type = stripped.startsWith("class ");
            if (!type && !stripped.startsWith("def ") && !stripped.startsWith("async def ")) continue;
            int indent = lines[i].length() - lines[i].stripLeading().length();
            int first = i;
            while (first > 0 && lines[first - 1].strip().startsWith("@")) first--;
            int last = i;
            for (int j = i + 1; j < lines.length; j++) {
                String s = lines[j].strip();
                if (s.isEmpty() || s.startsWith("#")) continue;
                if (lines[j].length() - lines[j].stripLeading().length() <= indent) break;
                last = j;
            }
            int from = offsets[first];
            int to = Math.min(offsets[last + 1], text.length());
            List<Token> span = trim(tokens.stream().filter(t -> t.start() >= from && t.start() < to).toList());
            int nameAt = indexOfName(span);
            if (nameAt < 0) continue;
            String name = span.get(nameAt).text();
            String owner = owner(classes, classNames, i, indent);
            int paren = nameAt + 1 < span.size() && span.get(nameAt + 1).text().equals("(") ? nameAt + 1 : -1;
            Frame frame = new Frame(0, type ? "TYPE" : "METHOD", name, paren < 0 ? List.of() : group(span, paren));
            atoms.add(atom(path, text, span, frame, owner));
            if (type) {
                classes.add(new int[] {i, last, indent});
                classNames.add(name);
            }
        }
    }

    /** Drops the synthetic indentation tokens at either end, so nesting depth does not change an atom's planes. */
    private static List<Token> trim(List<Token> span) {
        int from = 0;
        int to = span.size();
        while (from < to && span.get(from).start() == span.get(from).end()) from++;
        while (to > from && span.get(to - 1).start() == span.get(to - 1).end()) to--;
        return span.subList(from, to);
    }

    private static int indexOfName(List<Token> span) {
        for (int i = 0; i + 1 < span.size(); i++) {
            String t = span.get(i).text();
            if ((t.equals("def") || t.equals("class")) && span.get(i + 1).kind() == Kind.ID) return i + 1;
        }
        return -1;
    }

    private static String owner(List<int[]> classes, List<String> names, int line, int indent) {
        List<String> chain = new ArrayList<>();
        for (int c = 0; c < classes.size(); c++) {
            int[] span = classes.get(c);
            if (span[0] < line && line <= span[1] && span[2] < indent) chain.add(names.get(c));
        }
        return String.join(".", chain);
    }

    private static Atom atom(String path, String text, List<Token> span, Frame frame, String owner) {
        int from = span.get(0).start();
        int to = span.get(span.size() - 1).end();
        String symbol = (owner.isEmpty() ? "" : owner + ".") + frame.name()
                + ("METHOD".equals(frame.kind()) ? "(" + arity(frame.params()) + ")" : "");
        AtomBuilder builder = AtomBuilder.atom(path, frame.kind(), symbol, text.substring(from, to));
        declared(span, frame).forEach(builder::declare);
        for (int i = 0; i < span.size(); i++) {
            Token t = span.get(i);
            boolean member = i > 0 && (span.get(i - 1).text().equals(".") || span.get(i - 1).text().equals("->"));
            switch (t.kind()) {
                case ID -> {
                    if (member) builder.literal("ID", t.text());
                    else builder.name("ID", t.text());
                }
                case NUMBER -> builder.literal("NUM", t.text());
                case STRING -> builder.literal("STR", t.text());
                default -> builder.node(t.text());
            }
        }
        return builder.build();
    }

    /**
     * The atom of a range that another front end found, for example a document symbol reported by a language
     * server: the range's tokens go through the same plane rules as the lexical front end's own atoms.
     *
     * @param indented true for indentation languages (comments start with {@code #})
     * @param name     the symbol's own name, declared first
     * @param symbol   the symbol written to the atom table
     */
    public static Atom rangeAtom(String path, String text, int from, int to, String kind, String name, String symbol,
            boolean indented) {
        List<Token> span = Lexer.tokens(text.substring(from, to), indented);
        if (span.isEmpty()) return AtomBuilder.atom(path, kind, symbol, text.substring(from, to)).build();
        int paren = -1;
        for (int i = 0; i + 1 < span.size() && paren < 0; i++) {
            if (span.get(i).text().equals(name) && span.get(i + 1).text().equals("(")) paren = i + 1;
        }
        Frame frame = new Frame(0, kind, name, paren < 0 ? List.of() : group(span, paren));
        Atom atom = atom(path, text.substring(from, to), trim(span), frame, "");
        return new Atom(path, kind, symbol, atom.tokens(), atom.exact(), atom.logic(), atom.structure(), atom.simhash());
    }

    private static int arity(List<Token> params) {
        if (params.isEmpty()) return 0;
        int depth = 0;
        int commas = 0;
        for (Token t : params) {
            String s = t.text();
            if (s.equals("(") || s.equals("[") || s.equals("{") || s.equals("<")) depth++;
            if ((s.equals(")") || s.equals("]") || s.equals("}") || s.equals(">")) && depth > 0) depth--;
            if (s.equals(",") && depth == 0) commas++;
        }
        return commas + 1;
    }

    /** Names the atom binds, in order: its own name, its parameters, then local bindings. */
    private static Set<String> declared(List<Token> span, Frame frame) {
        Set<String> declared = new LinkedHashSet<>();
        declared.add(frame.name());
        List<Token> params = frame.params();
        for (int i = 0; i < params.size(); i++) {
            String next = i + 1 < params.size() ? params.get(i + 1).text() : ")";
            if (params.get(i).kind() == Kind.ID && Set.of(",", ")", "=", ":").contains(next)) declared.add(params.get(i).text());
        }
        for (int i = 0; i + 1 < span.size(); i++) {
            Token t = span.get(i);
            if (t.kind() != Kind.ID) continue;
            String prev = i > 0 ? span.get(i - 1).text() : ";";
            Token prevToken = i > 0 ? span.get(i - 1) : null;
            String next = span.get(i + 1).text();
            boolean afterKeyword = prevToken != null && prevToken.kind() == Kind.KEYWORD && DECLARE.contains(prev);
            boolean assigned = next.equals("=") && (prevToken == null || prevToken.kind() == Kind.ID
                    || Set.of("*", ">", "]", ";", "{", "}", "INDENT", "DEDENT", ",").contains(prev));
            boolean loop = next.equals("in") || next.equals("of") || next.equals(":=")
                    || next.equals(":") && prevToken != null && prevToken.kind() == Kind.ID;
            if (afterKeyword || assigned || loop) declared.add(t.text());
        }
        return declared;
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
