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
import java.util.List;

import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.MethodCall;
import org.openrewrite.java.tree.TypedTree;

/**
 * Every node of a tree in visit order with its node class and the types OpenRewrite attached to it: the expression or
 * declaration type, the method type of calls and declarations, and the variable type of variables and identifiers.
 */
final class TypeTrail {
    private TypeTrail() {}

    static List<String> of(J.CompilationUnit lst) {
        List<String> trail = new ArrayList<>();
        new JavaIsoVisitor<List<String>>() {
            @Override
            public J preVisit(J tree, List<String> out) {
                out.add(describe(tree));
                return tree;
            }
        }.visit(lst, trail);
        return trail;
    }

    private static String describe(J tree) {
        StringBuilder line = new StringBuilder(tree.getClass().getSimpleName());
        if (tree instanceof TypedTree typed) {
            line.append(" type=").append(typed.getType());
        }
        if (tree instanceof MethodCall call) {
            line.append(" method=").append(call.getMethodType());
        }
        return describeDeclaration(tree, line);
    }

    private static String describeDeclaration(J tree, StringBuilder line) {
        if (tree instanceof J.MethodDeclaration declaration) {
            line.append(" method=").append(declaration.getMethodType());
        }
        if (tree instanceof J.Identifier identifier) {
            line.append(" field=").append(identifier.getFieldType());
        }
        if (tree instanceof J.VariableDeclarations.NamedVariable variable) {
            line.append(" variable=").append(variable.getVariableType());
        }
        return line.toString();
    }
}
