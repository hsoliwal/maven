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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;
import org.apache.maven.plugins.clustering.LexicalAtoms.Frame;
import org.apache.maven.plugins.clustering.Lexer.Kind;
import org.apache.maven.plugins.clustering.Lexer.Token;

/**
 * Atoms of an indentation language (Python): {@code def}, {@code async def} and {@code class} lines open atoms that run
 * to the next line indented as far or less, with the decorator lines above them. Blank and comment lines do not end
 * an atom. A method's owner is the chain of classes whose bodies hold it.
 */
final class IndentedAtoms {
    /** A class atom: its line, its last line, its indentation and its name. */
    private record Owner(int line, int last, int indent, String name) { }

    private final String path;
    private final String text;
    private final List<Token> tokens;
    private final List<Atom> atoms;
    private final String[] lines;
    private final int[] offsets;
    private final List<Owner> classes = new ArrayList<>();

    private IndentedAtoms(String path, String text, List<Token> tokens, List<Atom> atoms) {
        this.path = path;
        this.text = text;
        this.tokens = tokens;
        this.atoms = atoms;
        this.lines = text.split("\n", -1);
        this.offsets = new int[lines.length + 1];
        IntStream.range(0, lines.length).forEach(i -> offsets[i + 1] = offsets[i] + lines[i].length() + 1);
    }

    static void scan(String path, String text, List<Token> tokens, List<Atom> atoms) {
        IndentedAtoms scan = new IndentedAtoms(path, text, tokens, atoms);
        IntStream.range(0, scan.lines.length).forEach(scan::line);
    }

    /** Line {@code i} opens an atom when it starts with {@code def}, {@code async def} or {@code class}. */
    private void line(int i) {
        String stripped = lines[i].strip();
        boolean type = stripped.startsWith("class ");
        if (!type && !stripped.startsWith("def ") && !stripped.startsWith("async def ")) return;
        int indent = indent(lines[i]);
        int last = last(i, indent);
        List<Token> span = span(first(i), last);
        int nameAt = nameAt(span);
        if (nameAt < 0) return;
        String name = span.get(nameAt).text();
        int paren = nameAt + 1 < span.size() && span.get(nameAt + 1).text().equals("(") ? nameAt + 1 : -1;
        Frame frame = new Frame(0, type ? "TYPE" : "METHOD", name, paren < 0 ? List.of() : LexicalAtoms.group(span, paren));
        atoms.add(LexicalPlanes.atom(path, text, span, frame, owner(i, indent), 0));
        if (type) classes.add(new Owner(i, last, indent, name));
    }

    private static int indent(String line) {
        return line.length() - line.stripLeading().length();
    }

    /** The first decorator line right above line {@code i}, or {@code i}. */
    private int first(int i) {
        return IntStream.iterate(i - 1, f -> f >= 0 && lines[f].strip().startsWith("@"), f -> f - 1)
                .reduce((a, b) -> b).orElse(i);
    }

    /** The last line of the block that line {@code i} opens: the last line indented further before one that is not. */
    private int last(int i, int indent) {
        return IntStream.range(i + 1, lines.length).filter(j -> !lines[j].strip().isEmpty() && !lines[j].strip().startsWith("#"))
                .takeWhile(j -> indent(lines[j]) > indent).reduce((a, b) -> b).orElse(i);
    }

    /** The tokens of lines {@code first} to {@code last}, without indentation tokens at either end. */
    private List<Token> span(int first, int last) {
        int from = offsets[first];
        int to = Math.min(offsets[last + 1], text.length());
        return LexicalAtoms.trim(tokens.stream().filter(t -> t.start() >= from && t.start() < to).toList());
    }

    private static int nameAt(List<Token> span) {
        return IntStream.range(0, span.size() - 1).filter(i -> (span.get(i).text().equals("def")
                || span.get(i).text().equals("class")) && span.get(i + 1).kind() == Kind.ID).findFirst().orElse(-2) + 1;
    }

    private String owner(int line, int indent) {
        return classes.stream().filter(c -> c.line() < line && line <= c.last() && c.indent() < indent).map(Owner::name)
                .collect(Collectors.joining("."));
    }
}
