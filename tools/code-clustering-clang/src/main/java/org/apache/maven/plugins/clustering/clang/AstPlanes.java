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

import java.util.Set;
import org.apache.maven.plugins.clustering.AtomBuilder;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * The planes of one atom from clang's tree, through the plugin's {@link AtomBuilder}. The walk is pre-order over
 * the declaration's subtree, implicit nodes left out (the members clang adds to a class or a lambda, {@code self}
 * and {@code _cmd} in Objective-C methods). A node's kind is clang's kind, with the operator for operators
 * ({@code BinaryOperator+}), {@code _post} for postfix increments and decrements, and the cast kind for casts
 * ({@code ImplicitCastExpr<LValueToRValue>}). Declarations carry their name (declared names are numbered in LOGIC)
 * followed by a {@code type} node with the type as written; references carry the referenced name; member
 * accesses, literals and Objective-C selectors carry their text, kept as written in LOGIC. Literal values are
 * clang's: {@code 0x10} and {@code 16} are the same {@code 16}.
 */
final class AstPlanes {
    static final Set<String> DECLARING = Set.of("FunctionDecl", "CXXMethodDecl", "CXXConstructorDecl",
            "CXXDestructorDecl", "CXXConversionDecl", "ObjCMethodDecl", "ParmVarDecl", "VarDecl", "BindingDecl",
            "LabelStmt", "CXXRecordDecl", "RecordDecl", "ClassTemplateSpecializationDecl", "EnumDecl", "FieldDecl",
            "EnumConstantDecl", "TypedefDecl", "TypeAliasDecl", "TemplateTypeParmDecl", "NonTypeTemplateParmDecl",
            "ObjCInterfaceDecl", "ObjCImplementationDecl", "ObjCProtocolDecl", "ObjCCategoryDecl",
            "ObjCCategoryImplDecl", "ObjCIvarDecl", "ObjCPropertyDecl");

    private AstPlanes() { }

    /** Completes the builder of a declaration's atom: declared names first, then the walk. */
    static Atom atom(AtomBuilder builder, AstNode declaration) {
        declare(declaration, builder);
        walk(declaration, builder);
        return builder.build();
    }

    private static void declare(AstNode n, AtomBuilder builder) {
        if (n.flag("isImplicit")) return;
        if (DECLARING.contains(n.kind) && !n.name.isEmpty()) builder.declare(n.name);
        n.children.forEach(c -> declare(c, builder));
    }

    private static void walk(AstNode n, AtomBuilder builder) {
        if (n.flag("isImplicit")) return;
        emit(n, builder);
        n.children.forEach(c -> walk(c, builder));
    }

    private static void emit(AstNode n, AtomBuilder builder) {
        String kind = kind(n);
        String ref = n.attr("ref");
        if (!ref.isEmpty()) builder.name(kind, ref);
        else if (!n.name.isEmpty() && DECLARING.contains(n.kind)) builder.name(kind, n.name);
        else if (!n.name.isEmpty()) builder.literal(kind, n.name);
        else if (n.attrs.containsKey("value")) builder.literal(kind, n.attr("value"));
        else if (n.attrs.containsKey("selector")) builder.literal(kind, n.attr("selector"));
        else builder.node(kind);
        if (n.kind.endsWith("Decl") && n.attrs.containsKey("type")) builder.literal("type", n.attr("type"));
    }

    /** The node's kind in the planes: clang's kind with its operator, postfix mark or cast kind. */
    static String kind(AstNode n) {
        String cast = n.attrs.containsKey("castKind") ? "<" + n.attr("castKind") + ">" : "";
        return n.kind + n.attr("opcode") + (n.flag("isPostfix") ? "_post" : "") + cast;
    }
}
