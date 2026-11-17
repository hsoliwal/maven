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

import java.util.Optional;

import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.TypeSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.comp.Attr;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Enter;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCArrayTypeTree;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCImport;
import com.sun.tools.javac.tree.JCTree.JCPrimitiveTypeTree;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

/**
 * The Attr of the {@link ShadowContext}: rewrite's Javadoc visitor calls only {@link #attribType(JCTree, TypeSymbol)},
 * on the reference trees of {@code @link}/{@code @see} tags, and this resolves them by pure lookups, never by javac's
 * Attr, Resolve or Check: member types of classes javac completed, the unit's single-type imports, the same package,
 * star imports and {@code java.lang} through the symbol table's maps, primitives and arrays. Classes are given raw, as
 * javac's Attr gives doc references. It writes symbols and types only into the reference trees, which
 * {@link FreshDocs} parsed new for the conversion, and completes nothing (the {@link CompletionRefusal} is in place).
 * A name it cannot resolve this way stays without a type, as a stale link does under javac's Attr.
 */
final class PureDocAttr extends Attr {
    private static final String JAVA_LANG = "java.lang.";

    private final Symtab symtab;
    private final Names names;
    private final Enter enter;

    PureDocAttr(Context shadow, Context build) {
        super(shadow);
        this.symtab = Symtab.instance(build);
        this.names = Names.instance(build);
        this.enter = Enter.instance(build);
    }

    @Override
    public Type attribType(JCTree tree, TypeSymbol site) {
        return type(tree, site).orElse(null);
    }

    private Optional<Type> type(JCTree tree, TypeSymbol site) {
        if (tree instanceof JCPrimitiveTypeTree primitive) {
            return Optional.of(symtab.typeOfTag[primitive.typetag.ordinal()]);
        }
        if (tree instanceof JCArrayTypeTree array) {
            return type(array.elemtype, site).map(element -> new ArrayType(element, symtab.arrayClass));
        }
        Optional<Symbol> symbol = tree instanceof JCIdent id
                ? simple(id.name, site)
                : tree instanceof JCFieldAccess select ? qualified(select, site) : Optional.empty();
        return symbol.map(found -> attributed(tree, found));
    }

    /** Writes the symbol and its raw type into the doc reference tree (a tree only the conversion holds). */
    private static Type attributed(JCTree tree, Symbol symbol) {
        Type type = raw(symbol);
        if (tree instanceof JCIdent id) {
            id.sym = symbol;
        } else if (tree instanceof JCFieldAccess select) {
            select.sym = symbol;
        }
        tree.type = type;
        return type;
    }

    /** A generic class as a new raw class type; no symbol or type of javac's is changed. */
    private static Type raw(Symbol symbol) {
        if (symbol.type instanceof ClassType type && type.typarams_field != null && !type.typarams_field.isEmpty()) {
            return new ClassType(type.getEnclosingType(), List.nil(), (ClassSymbol) symbol);
        }
        return symbol.type;
    }

    private Optional<Symbol> qualified(JCFieldAccess select, TypeSymbol site) {
        Optional<Symbol> byName = byFlatName(TreeInfo.fullName(select).toString());
        return byName.isPresent()
                ? byName
                : type(select.selected, site).flatMap(outer -> memberType(outer.tsym, select.name));
    }

    /** A simple name: the enclosing classes and their member types, then the unit's imports and package. */
    private Optional<Symbol> simple(Name name, TypeSymbol site) {
        for (Symbol c = site; c != null && c.kind == Kind.TYP; c = c.owner) {
            if (c.name == name) {
                return Optional.of(c);
            }
            Optional<Symbol> member = memberType((TypeSymbol) c, name);
            if (member.isPresent()) {
                return member;
            }
        }
        Env<AttrContext> env = site == null ? null : enter.getEnv(site.outermostClass());
        return env == null ? Optional.empty() : fromUnit(env.toplevel, name);
    }

    private Optional<Symbol> fromUnit(JCCompilationUnit unit, Name name) {
        return singleTypeImport(unit, name)
                .or(() -> byFlatName(packagePrefix(unit) + name))
                .or(() -> starImport(unit, name))
                .or(() -> byFlatName(JAVA_LANG + name));
    }

    private Optional<Symbol> singleTypeImport(JCCompilationUnit unit, Name name) {
        for (JCImport imported : unit.getImports()) {
            if (!imported.staticImport && imported.qualid.name == name) {
                return byFlatName(TreeInfo.fullName(imported.qualid).toString());
            }
        }
        return Optional.empty();
    }

    private Optional<Symbol> starImport(JCCompilationUnit unit, Name name) {
        for (JCImport imported : unit.getImports()) {
            if (!imported.staticImport && imported.qualid.name == names.asterisk) {
                Optional<Symbol> found = byFlatName(TreeInfo.fullName(imported.qualid.selected) + "." + name);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static String packagePrefix(JCCompilationUnit unit) {
        return unit.packge == null || unit.packge.fullname.isEmpty() ? "" : unit.packge.fullname + ".";
    }

    /** A member type of a class javac has completed; an uncompleted class has no members to look at. */
    private static Optional<Symbol> memberType(TypeSymbol owner, Name name) {
        if (!(owner instanceof ClassSymbol c) || !c.completer.isTerminal() || c.members_field == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(c.members_field.findFirst(name, s -> s.kind == Kind.TYP));
    }

    /** A class javac's symbol table already holds under a flat name; no class is read or entered. */
    private Optional<Symbol> byFlatName(String flatName) {
        for (ClassSymbol c : symtab.getClassesForName(names.fromString(flatName))) {
            return Optional.of(c);
        }
        return Optional.empty();
    }
}
