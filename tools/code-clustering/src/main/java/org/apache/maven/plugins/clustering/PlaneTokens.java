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
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * The javac walk of one atom into {@link AtomBuilder}: node kinds in pre-order. The names the atom declares (its own
 * name, parameters, locals, type parameters) are declared first, in tree order, so a consistent rename leaves the
 * LOGIC plane unchanged.
 */
final class PlaneTokens {
    private PlaneTokens() { }

    static Atom atom(Tree node, String path, String kind, String symbol, String source) {
        AtomBuilder builder = AtomBuilder.atom(path, kind, symbol, source);
        if (node instanceof ClassTree type) builder.declare(type.getSimpleName().toString());
        if (node instanceof MethodTree method) builder.declare(method.getName().toString());
        new TreeScanner<Void, Void>() {
            @Override public Void visitVariable(VariableTree variable, Void unused) {
                builder.declare(variable.getName().toString());
                return super.visitVariable(variable, unused);
            }

            @Override public Void visitTypeParameter(TypeParameterTree parameter, Void unused) {
                builder.declare(parameter.getName().toString());
                return super.visitTypeParameter(parameter, unused);
            }
        }.scan(node, null);
        new Emitter(builder).scan(node, null);
        return builder.build();
    }

    /** One builder call per node: names and literals carry their text, every other node its kind only. */
    private static final class Emitter extends TreeScanner<Void, Void> {
        private final AtomBuilder builder;

        Emitter(AtomBuilder builder) {
            this.builder = builder;
        }

        @Override public Void scan(Tree node, Void unused) {
            if (node == null) return null;
            emit(node, node.getKind().name());
            return super.scan(node, unused);
        }

        private void emit(Tree node, String kind) {
            if (node instanceof IdentifierTree id) builder.name(kind, id.getName().toString());
            else if (node instanceof MemberSelectTree select) builder.name(kind, select.getIdentifier().toString());
            else if (node instanceof VariableTree variable) builder.name(kind, variable.getName().toString());
            else if (node instanceof MethodTree method) builder.name(kind, method.getName().toString());
            else if (node instanceof ClassTree type) builder.name(kind, type.getSimpleName().toString());
            else if (node instanceof TypeParameterTree parameter) builder.name(kind, parameter.getName().toString());
            else if (node instanceof LiteralTree literal) builder.literal(kind, String.valueOf(literal.getValue()));
            else builder.node(kind);
        }
    }
}
