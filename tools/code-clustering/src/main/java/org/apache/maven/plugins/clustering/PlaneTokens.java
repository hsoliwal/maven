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
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The javac walk of atoms into {@link AtomBuilder}: node kinds in pre-order. The names an atom declares (its own name,
 * parameters, locals, type parameters) are declared first, in tree order, so a consistent rename leaves the LOGIC
 * plane unchanged. The caller adds the source lines and builds the atom.
 * <p>
 * An instance walks each tree once. It records every node with its LOGIC token (names and literals carry their text,
 * every other node its kind only) and the node range of every type and method in the tree, so an atom nested in one
 * already walked (a method of a type, a nested type) replays its range instead of walking its subtree again. The
 * builder calls are the same as for a walk of the atom alone. Collect the atoms of one compilation unit with one
 * instance, outer atoms first: {@link AtomPlanes} does.
 */
final class PlaneTokens {
    private final Map<Tree, int[]> spans = new IdentityHashMap<>();
    private final List<String> kinds = new ArrayList<>();
    private final List<String> texts = new ArrayList<>();
    private final List<String> tokens = new ArrayList<>();
    private final BitSet names = new BitSet();
    private final BitSet declared = new BitSet();

    /** The atom of one node, walked alone. */
    static AtomBuilder atom(Tree node, String path, String kind, String symbol, String source) {
        return new PlaneTokens().of(node, path, kind, symbol, source);
    }

    /** The atom of a type or method node, replayed from the walk that holds it (walked now if none does). */
    AtomBuilder of(Tree node, String path, String kind, String symbol, String source) {
        int[] span = spans.containsKey(node) ? spans.get(node) : walk(node);
        AtomBuilder builder = AtomBuilder.atom(path, kind, symbol, source);
        if (node instanceof ClassTree type) builder.declare(type.getSimpleName().toString());
        if (node instanceof MethodTree method) builder.declare(method.getName().toString());
        for (int i = declared.nextSetBit(span[0]); i >= 0 && i < span[1]; i = declared.nextSetBit(i + 1)) {
            builder.declare(texts.get(i));
        }
        for (int i = span[0]; i < span[1]; i++) {
            if (names.get(i) && builder.isDeclared(texts.get(i))) builder.name(kinds.get(i), texts.get(i));
            else builder.token(kinds.get(i), tokens.get(i));
        }
        return builder;
    }

    private int[] walk(Tree root) {
        new Recorder().scan(root, null);
        return spans.computeIfAbsent(root, r -> new int[] {kinds.size(), kinds.size()});
    }

    /**
     * Records one node. A member-select identifier ({@code this.total}, {@code list.add}) names a field, method or type
     * of another owner, never a local the atom declares, so it is kept as written even when a local has the same
     * name: {@code this.password = password} and {@code this.passphrase = passphrase} write different fields.
     */
    private void record(Tree node, String kind) {
        if (node instanceof IdentifierTree id) name(kind, id.getName().toString(), false);
        else if (node instanceof MemberSelectTree select) text(kind, select.getIdentifier().toString());
        else if (node instanceof VariableTree variable) name(kind, variable.getName().toString(), true);
        else if (node instanceof MethodTree method) name(kind, method.getName().toString(), false);
        else if (node instanceof ClassTree type) name(kind, type.getSimpleName().toString(), false);
        else if (node instanceof TypeParameterTree parameter) name(kind, parameter.getName().toString(), true);
        else if (node instanceof LiteralTree literal) text(kind, String.valueOf(literal.getValue()));
        else add(kind, null, kind);
    }

    /** A name: numbered in LOGIC when the atom declares it; {@code declares}: this node declares it. */
    private void name(String kind, String name, boolean declares) {
        names.set(kinds.size());
        declared.set(kinds.size(), declares);
        add(kind, name, kind + ":" + name);
    }

    /** A literal value or another owner's member, kept as written. */
    private void text(String kind, String text) {
        add(kind, text, kind + ":" + text);
    }

    private void add(String kind, String text, String token) {
        kinds.add(kind);
        texts.add(text);
        tokens.add(token);
    }

    /** One record per node in pre-order; the range of every type and method. */
    private final class Recorder extends TreeScanner<Void, Void> {
        @Override public Void scan(Tree node, Void unused) {
            if (node == null) return null;
            int start = kinds.size();
            record(node, node.getKind().name());
            super.scan(node, unused);
            if (node instanceof ClassTree || node instanceof MethodTree) spans.put(node, new int[] {start, kinds.size()});
            return null;
        }
    }
}
