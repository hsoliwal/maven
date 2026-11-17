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

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The token streams of one atom: {@link #structure()} lists node kinds in pre-order; {@link #logic()} adds names and
 * literals, with every name the atom declares (its own name, parameters, locals, type parameters) replaced by the index
 * of its first appearance, so a consistent rename leaves the logic stream unchanged.
 */
final class PlaneTokens {
    private final List<String> structure = new ArrayList<>();
    private final List<String> logic = new ArrayList<>();
    private final Map<String, String> declared = new HashMap<>();

    private PlaneTokens() { }

    static PlaneTokens of(Tree atom) {
        PlaneTokens tokens = new PlaneTokens();
        tokens.declare(atom);
        new Emitter(tokens).scan(atom, null);
        return tokens;
    }

    String structure() {
        return String.join(" ", structure);
    }

    String logic() {
        return String.join(" ", logic);
    }

    List<String> logicTokens() {
        return logic;
    }

    /** The atom's own name and the names it declares, numbered in order of declaration. */
    private void declare(Tree atom) {
        if (atom instanceof ClassTree type) placeholder(type.getSimpleName().toString());
        if (atom instanceof MethodTree method) placeholder(method.getName().toString());
        new TreeScanner<Void, Void>() {
            @Override public Void visitVariable(VariableTree node, Void unused) {
                placeholder(node.getName().toString());
                return super.visitVariable(node, unused);
            }

            @Override public Void visitTypeParameter(TypeParameterTree node, Void unused) {
                placeholder(node.getName().toString());
                return super.visitTypeParameter(node, unused);
            }
        }.scan(atom, null);
    }

    private void placeholder(String name) {
        declared.putIfAbsent(name, "$" + declared.size());
    }

    private void node(Tree node, String detail) {
        structure.add(node.getKind().name());
        logic.add(detail == null ? node.getKind().name() : node.getKind().name() + ":" + detail);
    }

    private String name(CharSequence name) {
        return declared.getOrDefault(name.toString(), name.toString());
    }

    /** Emits one token per node; names and literals go into the logic stream only. */
    private static final class Emitter extends TreeScanner<Void, Void> {
        private final PlaneTokens tokens;

        Emitter(PlaneTokens tokens) {
            this.tokens = tokens;
        }

        @Override public Void scan(Tree node, Void unused) {
            if (node == null) return null;
            tokens.node(node, detail(node));
            return super.scan(node, unused);
        }

        private String detail(Tree node) {
            if (node instanceof IdentifierTree id) return tokens.name(id.getName());
            if (node instanceof MemberSelectTree select) return tokens.name(select.getIdentifier());
            if (node instanceof VariableTree variable) return tokens.name(variable.getName());
            if (node instanceof MethodTree method) return tokens.name(method.getName());
            if (node instanceof ClassTree type) return tokens.name(type.getSimpleName());
            if (node instanceof TypeParameterTree parameter) return tokens.name(parameter.getName());
            if (node instanceof LiteralTree literal) return String.valueOf(literal.getValue());
            return null;
        }
    }
}
