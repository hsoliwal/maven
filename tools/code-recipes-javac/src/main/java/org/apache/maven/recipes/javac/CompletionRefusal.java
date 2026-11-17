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
package org.apache.maven.recipes.javac;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Scope.WriteableScope;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ClassType;

/**
 * For the length of one conversion, every class javac has not completed stays uncompleted: rewrite's type mapping
 * calls {@code complete()} on any class whose completer is not terminal, which would read class files (or parse
 * {@code -sourcepath} files) javac never read, print new {@code -verbose} lines and report new classfile warnings.
 * Each such class gets this completer. A call records a one-time snapshot of the fields javac's lazy accessors fill in
 * on uncompleted classes ("FIXME: shouldn't be null" in {@code ClassSymbol.getSuperclass} and
 * {@code getInterfaces}; {@code ClassReader} later keeps a non-null value) and puts this completer back, so the class
 * stays exactly as it was. {@link #restore()} puts the snapshots and the original completers back by array index and
 * allocates nothing, so it also runs under an OutOfMemoryError.
 */
final class CompletionRefusal implements Symbol.Completer {
    private final Symtab symtab;
    private final ClassSymbol[] refused;
    private final Symbol.Completer[] originals;
    private final List<Snapshot> snapshots = new ArrayList<>();
    private final Set<ClassSymbol> touched = Collections.newSetFromMap(new IdentityHashMap<>());
    private final int classes;
    private int refusals;
    private int stray;

    /** Finds the classes to refuse; changes nothing until {@link #install()}. */
    CompletionRefusal(Symtab symtab) {
        this.symtab = symtab;
        int all = 0;
        int open = 0;
        for (ClassSymbol c : symtab.getAllClasses()) {
            all++;
            open += c.completer.isTerminal() ? 0 : 1;
        }
        this.classes = all;
        this.refused = new ClassSymbol[open];
        this.originals = new Symbol.Completer[open];
        collect();
    }

    private void collect() {
        int i = 0;
        for (ClassSymbol c : symtab.getAllClasses()) {
            if (!c.completer.isTerminal() && i < refused.length) {
                refused[i] = c;
                originals[i++] = c.completer;
            }
        }
    }

    /** Puts this completer on every class found; allocates nothing. */
    void install() {
        for (int i = 0; i < refused.length; i++) {
            if (refused[i] != null) {
                refused[i].completer = this;
            }
        }
    }

    @Override
    public void complete(Symbol symbol) {
        symbol.completer = this;
        refusals++;
        if (symbol instanceof ClassSymbol c && touched.add(c)) {
            snapshots.add(new Snapshot(c));
        }
    }

    /** Puts back every snapshot, then every original completer; allocates nothing. */
    void restore() {
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            snapshots.get(i).restore();
        }
        for (int i = 0; i < refused.length; i++) {
            restore(i);
        }
    }

    private void restore(int i) {
        ClassSymbol c = refused[i];
        if (c == null) {
            return;
        }
        if (c.completer == this || c.completer == Symbol.Completer.NULL_COMPLETER) {
            c.completer = originals[i];
        } else if (c.completer != originals[i]) {
            stray++;
        }
    }

    /** How many classes javac's symbol table held when the refusal was set up. */
    int classes() {
        return classes;
    }

    /** How many classes javac's symbol table holds now. */
    int classesNow() {
        int n = 0;
        for (ClassSymbol ignored : symtab.getAllClasses()) {
            n++;
        }
        return n;
    }

    /** Classes whose completer someone else replaced during the conversion. */
    int stray() {
        return stray;
    }

    /** How many completions were refused. */
    int refusals() {
        return refusals;
    }

    /** The fields of one uncompleted class that javac's lazy accessors fill in. */
    private static final class Snapshot {
        private final ClassSymbol symbol;
        private final long flags;
        private final WriteableScope members;
        private final Type type;
        private final Kind kind;
        private final ClassTypeFields classType;

        Snapshot(ClassSymbol symbol) {
            this.symbol = symbol;
            this.flags = symbol.flags_field;
            this.members = symbol.members_field;
            this.type = symbol.type;
            this.kind = symbol.kind;
            this.classType = symbol.type instanceof ClassType ct ? new ClassTypeFields(ct) : null;
        }

        void restore() {
            symbol.flags_field = flags;
            symbol.members_field = members;
            symbol.type = type;
            symbol.kind = kind;
            if (classType != null) {
                classType.restore();
            }
        }
    }

    /** The lazily filled fields of a class type. */
    private static final class ClassTypeFields {
        private final ClassType type;
        private final Type supertype;
        private final com.sun.tools.javac.util.List<Type> interfaces;
        private final com.sun.tools.javac.util.List<Type> typarams;
        private final com.sun.tools.javac.util.List<Type> allparams;
        private final com.sun.tools.javac.util.List<Type> allInterfaces;

        ClassTypeFields(ClassType type) {
            this.type = type;
            this.supertype = type.supertype_field;
            this.interfaces = type.interfaces_field;
            this.typarams = type.typarams_field;
            this.allparams = type.allparams_field;
            this.allInterfaces = type.all_interfaces_field;
        }

        void restore() {
            type.supertype_field = supertype;
            type.interfaces_field = interfaces;
            type.typarams_field = typarams;
            type.allparams_field = allparams;
            type.all_interfaces_field = allInterfaces;
        }
    }
}
