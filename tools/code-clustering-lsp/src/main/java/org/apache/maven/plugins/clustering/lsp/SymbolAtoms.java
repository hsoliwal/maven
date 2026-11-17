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
package org.apache.maven.plugins.clustering.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;
import org.apache.maven.plugins.clustering.LexicalAtoms;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Turns a server's document symbols into atoms. A {@link SymbolMapping} decides which symbols become TYPE or METHOD
 * atoms; for code, class-like symbols are TYPE atoms and method, function and constructor symbols are METHOD atoms.
 * Other symbols are not atoms, but their children are still visited. For code, a qualified name such as
 * {@code Point::norm} or {@code Point.norm} is split into owner and name. A TYPE atom is the owner of the atoms
 * inside it. In the flat symbol form, the owner is the server's container name, and a symbol has children when
 * another symbol's range lies inside its own. Planes come from {@link LexicalAtoms#rangeAtom}: the server decides the
 * boundaries, the shared rules give the hashes. Positions are LSP line and UTF-16 character offsets, the same units
 * as Java string indices.
 */
final class SymbolAtoms {
    /** gopls's method name: the receiver type in parentheses (pointer star and type arguments dropped), a dot, the name. */
    private static final Pattern GO_RECEIVER = Pattern.compile("\\(\\*?([A-Za-z_][A-Za-z0-9_]*)(?:\\[[^)]*])?\\)\\.(\\w+)");
    private final String path;
    private final String text;
    private final boolean indented;
    private final SymbolMapping mapping;
    private final int[] lineStarts;
    private final List<Atom> atoms = new ArrayList<>();

    private SymbolAtoms(String path, String text, boolean indented, SymbolMapping mapping) {
        this.path = path;
        this.text = text;
        this.indented = indented;
        this.mapping = mapping;
        this.lineStarts = text.lines().count() == 0 ? new int[] {0} : starts(text);
    }

    static List<Atom> atoms(List<Either<SymbolInformation, DocumentSymbol>> symbols, String path, String text,
            boolean indented, SymbolMapping mapping) {
        SymbolAtoms collector = new SymbolAtoms(path, text, indented, mapping);
        List<SymbolInformation> flat = symbols.stream().filter(Either::isLeft).map(Either::getLeft)
                .collect(Collectors.toList());
        for (Either<SymbolInformation, DocumentSymbol> symbol : symbols) {
            if (symbol.isRight()) collector.visit(symbol.getRight(), "");
            else collector.flat(symbol.getLeft(), flat);
        }
        return collector.atoms;
    }

    private void visit(DocumentSymbol symbol, String owner) {
        List<DocumentSymbol> children = symbol.getChildren() == null ? List.of() : symbol.getChildren();
        String[] split = split(owner, symbol.getName());
        Optional<String> kind = mapping.kind(symbol.getKind(), () -> !children.isEmpty());
        kind.ifPresent(k -> add(k, split[1], split[0], symbol.getRange()));
        String inner = kind.filter("TYPE"::equals).isPresent() ? join(split[0], split[1]) : owner;
        children.forEach(child -> visit(child, inner));
    }

    /** The flat, older symbol form (deprecated in LSP 3.16) that servers without hierarchical support still send. */
    @SuppressWarnings("deprecation")
    private void flat(SymbolInformation symbol, List<SymbolInformation> all) {
        Range range = symbol.getLocation().getRange();
        Optional<String> kind = mapping.kind(symbol.getKind(),
                () -> all.stream().anyMatch(other -> other != symbol && inside(other.getLocation().getRange(), range)));
        if (kind.isEmpty()) return;
        String container = symbol.getContainerName() == null ? "" : symbol.getContainerName();
        String[] split = split(container, symbol.getName());
        add(kind.get(), split[1], split[0], range);
    }

    private void add(String kind, String name, String owner, Range range) {
        int from = offset(range.getStart());
        int to = offset(range.getEnd());
        if (to > from) atoms.add(LexicalAtoms.rangeAtom(path, text, from, to, kind, name, owner, indented));
    }

    private String[] split(String owner, String name) {
        return mapping.splitsNames() ? qualified(owner, name) : new String[] {owner, name};
    }

    /**
     * {@code Point::norm} under owner {@code ""} becomes owner {@code Point}, name {@code norm}. A qualifier that
     * repeats the enclosing owner ({@code Bag::size} inside {@code Bag}) is not added twice. A Go method as gopls names
     * it, {@code (Point).Norm}, {@code (*Point).Norm} or {@code (*List[T]).Push}, is owned by its receiver type.
     */
    private static String[] qualified(String owner, String name) {
        Matcher receiver = GO_RECEIVER.matcher(name);
        String plain = receiver.matches() ? receiver.group(1) + "." + receiver.group(2) : name.replace("::", ".");
        int paren = plain.indexOf('(');
        if (paren > 0) plain = plain.substring(0, paren);
        int dot = plain.lastIndexOf('.');
        if (dot < 0) return new String[] {owner, plain};
        String qualifier = plain.substring(0, dot);
        boolean repeated = owner.equals(qualifier) || owner.endsWith("." + qualifier);
        return new String[] {repeated ? owner : join(owner, qualifier), plain.substring(dot + 1)};
    }

    /** True when {@code inner} lies inside {@code outer} and is not the same range. */
    private static boolean inside(Range inner, Range outer) {
        return !inner.equals(outer) && compare(outer.getStart(), inner.getStart()) <= 0
                && compare(inner.getEnd(), outer.getEnd()) <= 0;
    }

    private static int compare(Position a, Position b) {
        return a.getLine() != b.getLine() ? Integer.compare(a.getLine(), b.getLine())
                : Integer.compare(a.getCharacter(), b.getCharacter());
    }

    private static String join(String owner, String name) {
        return owner.isEmpty() ? name : owner + "." + name;
    }

    private int offset(Position position) {
        int line = Math.min(position.getLine(), lineStarts.length - 1);
        return Math.min(lineStarts[line] + position.getCharacter(), text.length());
    }

    private static int[] starts(String text) {
        List<Integer> starts = new ArrayList<>(List.of(0));
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }
}
