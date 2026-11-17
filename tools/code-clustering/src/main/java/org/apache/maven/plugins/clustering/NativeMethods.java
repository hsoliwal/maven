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
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * The methods declared {@code native} in one class that javac has attributed: the facts a JNI linker needs from the
 * Java side. Attribution resolves every type exactly, so the class is its binary name in internal form
 * ({@code p/Outer$Inner}, also for local and anonymous classes) and the arguments are the JVM descriptor of the
 * erased parameter types ({@code I[Ljava/lang/String;}). A type javac could not resolve is {@value #UNKNOWN}.
 * {@code symbol} is the method's atom symbol in {@code ATOM_HASHES.tsv}.
 */
final class NativeMethods extends TreePathScanner<Void, Void> {
    static final String UNKNOWN = "L?;";
    static final String HEADER = "path\tsymbol\tclass_name\tmethod\targuments";
    private static final Map<TypeKind, String> PRIMITIVES = Map.of(TypeKind.BOOLEAN, "Z", TypeKind.BYTE, "B",
            TypeKind.CHAR, "C", TypeKind.SHORT, "S", TypeKind.INT, "I", TypeKind.LONG, "J", TypeKind.FLOAT, "F",
            TypeKind.DOUBLE, "D");

    /** One native method, a row of {@code JNI.tsv}. */
    record NativeMethod(String path, String symbol, String className, String method, String arguments) {
        String row() {
            return String.join("\t", path, symbol, className, method, arguments);
        }
    }

    private final Trees trees;
    private final Elements elements;
    private final Types types;
    private final String path;
    private final List<NativeMethod> found = new ArrayList<>();

    private NativeMethods(Trees trees, Elements elements, Types types, String path) {
        this.trees = trees;
        this.elements = elements;
        this.types = types;
        this.path = path;
    }

    /** The native methods of the class at {@code type} and of the classes inside it, in source order. */
    static List<NativeMethod> of(Trees trees, Elements elements, Types types, TreePath type, String path) {
        NativeMethods scanner = new NativeMethods(trees, elements, types, path);
        scanner.scan(type, null);
        return scanner.found;
    }

    @Override
    public Void visitMethod(MethodTree node, Void unused) {
        if (node.getModifiers().getFlags().contains(Modifier.NATIVE)) record(node);
        return super.visitMethod(node, unused);
    }

    private void record(MethodTree node) {
        Element element = trees.getElement(getCurrentPath());
        if (!(element instanceof ExecutableElement method) || !(method.getEnclosingElement() instanceof TypeElement owner)) {
            return;
        }
        String params = node.getParameters().stream().map(p -> p.getType().toString()).collect(Collectors.joining(","));
        String arguments = method.getParameters().stream().map(VariableElement::asType).map(this::descriptor)
                .collect(Collectors.joining());
        found.add(new NativeMethod(path, owners() + "." + node.getName() + "(" + params + ")",
                elements.getBinaryName(owner).toString().replace('.', '/'), node.getName().toString(), arguments));
    }

    /** The enclosing classes' simple names from the outermost, as the atom symbol has them (anonymous: empty). */
    private String owners() {
        List<String> names = new ArrayList<>();
        for (TreePath at = getCurrentPath().getParentPath(); at != null; at = at.getParentPath()) {
            if (at.getLeaf() instanceof ClassTree type) names.add(0, type.getSimpleName().toString());
        }
        return String.join(".", names);
    }

    private String descriptor(TypeMirror type) {
        TypeMirror erased = types.erasure(type);
        if (erased.getKind().isPrimitive()) return PRIMITIVES.get(erased.getKind());
        return switch (erased.getKind()) {
            case ARRAY -> "[" + descriptor(((ArrayType) erased).getComponentType());
            case DECLARED -> "L" + elements.getBinaryName((TypeElement) ((DeclaredType) erased).asElement()).toString()
                    .replace('.', '/') + ";";
            default -> UNKNOWN;
        };
    }
}
