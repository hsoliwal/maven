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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * Builds one atom's four planes from a pre-order walk of any syntax tree or token stream. Every front end uses it:
 * javac, the lexical front end, and any {@link AtomFrontEnd} plugged in, for example an adapter over an Eclipse
 * language core (JDT, CDT) or another parser. The walk calls:
 * <ol>
 *   <li>{@link #declare} for each name the atom declares, before or during the walk: its own name, parameters,
 *       locals, type parameters. A declared name is replaced in the LOGIC plane by its declaration index;</li>
 *   <li>{@link #node}, {@link #name} or {@link #literal} once per node, in pre-order.</li>
 * </ol>
 * STRUCTURE gets the node kind only. LOGIC gets the kind, plus the name (declared names numbered) or the literal
 * value. SIMHASH is computed over the LOGIC tokens and EXACT over the source text, with CRLF read as LF. Kinds are
 * the front end's own vocabulary. Planes from different front ends are comparable only if they use the same kinds.
 * {@link #lines} records where the atom is in its file, so that reports and editors can point at it.
 */
public final class AtomBuilder {
    private final String path;
    private final String kind;
    private final String symbol;
    private final String source;
    private final Map<String, String> declared = new HashMap<>();
    private final List<String> structure = new ArrayList<>();
    private final List<String> logic = new ArrayList<>();
    private int line;
    private int endLine;

    private AtomBuilder(String path, String kind, String symbol, String source) {
        this.path = path;
        this.kind = kind;
        this.symbol = symbol;
        this.source = source;
    }

    /**
     * @param path   the file path relative to the source root, with {@code /} separators
     * @param kind   {@code TYPE} or {@code METHOD} (clusters and joins compare atoms of the same kind only)
     * @param symbol the atom's name within the file, for example {@code Owner.method(int[])}
     * @param source the atom's source text
     */
    public static AtomBuilder atom(String path, String kind, String symbol, String source) {
        return new AtomBuilder(path, kind, symbol, source);
    }

    /** A name the atom declares; the first declaration fixes its index. */
    public AtomBuilder declare(String name) {
        declared.putIfAbsent(name, "$" + declared.size());
        return this;
    }

    public boolean isDeclared(String name) {
        return declared.containsKey(name);
    }

    /** A node without a name or value, for example a block, an operator or a keyword. */
    public AtomBuilder node(String nodeKind) {
        structure.add(nodeKind);
        logic.add(nodeKind);
        return this;
    }

    /** A node that carries a name: declared names are numbered, others kept. */
    public AtomBuilder name(String nodeKind, String name) {
        structure.add(nodeKind);
        logic.add(nodeKind + ":" + declared.getOrDefault(name, name));
        return this;
    }

    /** A node that carries a literal value, kept as written. */
    public AtomBuilder literal(String nodeKind, String value) {
        structure.add(nodeKind);
        logic.add(nodeKind + ":" + value);
        return this;
    }

    /**
     * The atom's first and last source lines, 1-based. Lines are not part of any plane. Without this call both are 0
     * (unknown).
     */
    public AtomBuilder lines(int line, int endLine) {
        this.line = line;
        this.endLine = endLine;
        return this;
    }

    public Atom build() {
        return new Atom(path, kind, symbol, logic.size(), PlaneHash.sha256(source.replace("\r\n", "\n")),
                PlaneHash.sha256(String.join(" ", logic)), PlaneHash.sha256(String.join(" ", structure)),
                PlaneHash.simhash(logic), line, endLine);
    }
}
