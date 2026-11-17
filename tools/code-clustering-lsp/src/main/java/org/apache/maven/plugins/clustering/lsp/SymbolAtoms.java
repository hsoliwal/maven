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
import java.util.Set;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;
import org.apache.maven.plugins.clustering.LexicalAtoms;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Turns a server's document symbols into atoms. Class, interface, struct, enum and object symbols become TYPE atoms.
 * Method, function and constructor symbols become METHOD atoms. Other symbols are not atoms, but their children are
 * still visited. A qualified name such as {@code Point::norm} or {@code Point.norm} is split into owner and name.
 * Planes come from {@link LexicalAtoms#rangeAtom}: the server decides the boundaries, the shared rules give the
 * hashes. Positions are LSP line and UTF-16 character offsets, the same units as Java string indices.
 */
final class SymbolAtoms {
    private static final Set<SymbolKind> TYPES = Set.of(SymbolKind.Class, SymbolKind.Interface, SymbolKind.Struct,
            SymbolKind.Enum, SymbolKind.Object);
    private static final Set<SymbolKind> METHODS = Set.of(SymbolKind.Method, SymbolKind.Function, SymbolKind.Constructor);

    private final String path;
    private final String text;
    private final boolean indented;
    private final int[] lineStarts;
    private final List<Atom> atoms = new ArrayList<>();

    private SymbolAtoms(String path, String text, boolean indented) {
        this.path = path;
        this.text = text;
        this.indented = indented;
        this.lineStarts = text.lines().count() == 0 ? new int[] {0} : starts(text);
    }

    static List<Atom> atoms(List<Either<SymbolInformation, DocumentSymbol>> symbols, String path, String text, boolean indented) {
        SymbolAtoms collector = new SymbolAtoms(path, text, indented);
        for (Either<SymbolInformation, DocumentSymbol> symbol : symbols) {
            if (symbol.isRight()) collector.visit(symbol.getRight(), "");
            else collector.flat(symbol.getLeft());
        }
        return collector.atoms;
    }

    private void visit(DocumentSymbol symbol, String owner) {
        String[] split = split(owner, symbol.getName());
        String kind = kind(symbol.getKind());
        if (kind != null) add(kind, split[1], split[0], symbol.getRange());
        String inner = "TYPE".equals(kind) ? join(split[0], split[1]) : owner;
        if (symbol.getChildren() != null) symbol.getChildren().forEach(child -> visit(child, inner));
    }

    /** The flat, older symbol form (deprecated in LSP 3.16) that servers without hierarchical support still send. */
    @SuppressWarnings("deprecation")
    private void flat(SymbolInformation symbol) {
        String kind = kind(symbol.getKind());
        if (kind == null) return;
        String container = symbol.getContainerName() == null ? "" : symbol.getContainerName();
        String[] split = split(container, symbol.getName());
        add(kind, split[1], split[0], symbol.getLocation().getRange());
    }

    private void add(String kind, String name, String owner, Range range) {
        int from = offset(range.getStart());
        int to = offset(range.getEnd());
        if (to > from) atoms.add(LexicalAtoms.rangeAtom(path, text, from, to, kind, name, owner, indented));
    }

    private static String kind(SymbolKind kind) {
        if (TYPES.contains(kind)) return "TYPE";
        return METHODS.contains(kind) ? "METHOD" : null;
    }

    /**
     * {@code Point::norm} under owner {@code ""} becomes owner {@code Point}, name {@code norm}. A qualifier that
     * repeats the enclosing owner ({@code Bag::size} inside {@code Bag}) is not added twice.
     */
    private static String[] split(String owner, String name) {
        String plain = name.replace("::", ".");
        int paren = plain.indexOf('(');
        if (paren > 0) plain = plain.substring(0, paren);
        int dot = plain.lastIndexOf('.');
        if (dot < 0) return new String[] {owner, plain};
        String qualifier = plain.substring(0, dot);
        boolean repeated = owner.equals(qualifier) || owner.endsWith("." + qualifier);
        return new String[] {repeated ? owner : join(owner, qualifier), plain.substring(dot + 1)};
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
