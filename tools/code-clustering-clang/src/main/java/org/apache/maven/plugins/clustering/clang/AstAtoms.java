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
package org.apache.maven.plugins.clustering.clang;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.maven.plugins.clustering.AtomBuilder;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * The atoms of one main file, from the top-level declarations {@link AstStream} kept.
 * <ul>
 *   <li>METHOD: a definition (a declaration with a body) of a function, method, constructor, destructor, conversion
 *       function or Objective-C method. Its symbol is {@code Owner.name(parameters)} with clang's name, so
 *       {@code ~Point}, {@code operator==} and a function whose name a macro pasted together ({@code get_7}) are
 *       exact. Definitions inside a definition (lambdas, local classes) belong to the enclosing atom.</li>
 *   <li>TYPE: the definition of a named struct, class or union, an enum with its constants, or an Objective-C
 *       interface, implementation, protocol or category. Its symbol is {@code Owner.Name}; a category is
 *       {@code Class(Category)}.</li>
 * </ul>
 * Owners are the enclosing records, as in the lexical front end (namespaces are not owners). A member function
 * defined outside its class takes the class from {@code parentDeclContextId}. Implicit declarations and template
 * instantiations are skipped; the instantiation of a class template has the range of its pattern and is not
 * counted again. An atom's text runs from the range's first token to the end of its last token; when the last
 * token comes from a macro expansion, to the end of that macro call's argument list.
 */
final class AstAtoms {
    static final Set<String> FUNCTIONS = Set.of("FunctionDecl", "CXXMethodDecl", "CXXConstructorDecl",
            "CXXDestructorDecl", "CXXConversionDecl", "ObjCMethodDecl");
    static final Set<String> CONTAINERS = Set.of("ObjCInterfaceDecl", "ObjCImplementationDecl", "ObjCProtocolDecl",
            "ObjCCategoryDecl", "ObjCCategoryImplDecl");
    private static final Set<String> BODIES = Set.of("CompoundStmt", "CXXTryStmt");

    private final String path;
    private final SourceText text;
    private final Predicate<String> isMain;
    private final Map<String, String> records = new HashMap<>();
    private final Set<String> spans = new HashSet<>();
    private final Deque<String> owners = new ArrayDeque<>();
    private final List<Atom> atoms = new ArrayList<>();

    AstAtoms(String path, SourceText text, Predicate<String> isMain) {
        this.path = path;
        this.text = text;
        this.isMain = isMain;
    }

    /** The record id to owner-qualified name table that {@link AstStream} fills. */
    Map<String, String> records() {
        return records;
    }

    List<Atom> atoms() {
        return atoms;
    }

    /** Adds the atoms of one kept top-level declaration. */
    void visit(AstNode n) {
        if (n.flag("isImplicit") || n.attrs.containsKey("TemplateInstantiationPattern")) return;
        if (FUNCTIONS.contains(n.kind)) {
            if (n.children.stream().anyMatch(c -> BODIES.contains(c.kind))) function(n);
            return;
        }
        boolean type = isType(n);
        if (type) emit(n, "TYPE", qualified(owner(), ownerName(n)));
        if (type) owners.push(ownerName(n));
        n.children.forEach(this::visit);
        if (type) owners.pop();
    }

    private void function(AstNode n) {
        String declaredIn = n.attr("parentDeclContextId");
        String owner = declaredIn.isEmpty() ? owner() : records.getOrDefault(declaredIn, "");
        long parameters = n.children.stream().filter(c -> c.kind.equals("ParmVarDecl") && !c.flag("isImplicit")).count();
        emit(n, "METHOD", qualified(owner, n.name) + "(" + parameters + ")");
    }

    static boolean isType(AstNode n) {
        if (n.name.isEmpty()) return false;
        if (AstStream.RECORDS.contains(n.kind)) return n.flag("completeDefinition");
        if (n.kind.equals("EnumDecl")) return n.children.stream().anyMatch(c -> c.kind.equals("EnumConstantDecl"));
        return CONTAINERS.contains(n.kind);
    }

    private static String ownerName(AstNode n) {
        boolean category = n.kind.startsWith("ObjCCategory") && !n.attr("interface").isEmpty();
        return category ? n.attr("interface") + "(" + n.name + ")" : n.name;
    }

    private String owner() {
        List<String> chain = new ArrayList<>();
        owners.descendingIterator().forEachRemaining(chain::add);
        return String.join(".", chain);
    }

    private static String qualified(String owner, String name) {
        return owner.isEmpty() ? name : owner + "." + name;
    }

    private void emit(AstNode n, String kind, String symbol) {
        int[] span = span(n);
        if (span == null || !spans.add(kind + ":" + span[0] + ":" + span[1])) return;
        AtomBuilder builder = AtomBuilder.atom(path, kind, symbol, text.text(span[0], span[1]))
                .lines(text.line(span[0]), text.line(span[1] - 1));
        atoms.add(AstPlanes.atom(builder, n));
    }

    /** The node's byte range in the main file, or null when it does not lie there. */
    private int[] span(AstNode n) {
        AstLoc first = n.start();
        AstLoc last = n.last();
        if (first == null || last == null || !isMain.test(first.file()) || !isMain.test(last.file())) return null;
        int to = last.macro() ? text.macroCallEnd(last.end()) : last.end();
        return first.offset() < to && to <= text.length() ? new int[] {first.offset(), to} : null;
    }
}
