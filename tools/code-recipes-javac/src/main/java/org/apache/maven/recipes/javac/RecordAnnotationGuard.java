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

import com.sun.tools.javac.code.Symbol.RecordComponent;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.TreeScanner;

/**
 * Undoes the one write rewrite's parser visitor makes into javac's trees: {@code extractRecordComponentAnnotations}
 * (rewrite-java-21 8.89.0, ReloadableJava21ParserVisitor lines 607-613) sets the type of the annotation trees in
 * {@code RecordComponent.getOriginalAnnos()}, indexed by {@code getAnnotationMirrors()}, and the two lists are not
 * aligned when an annotation does not target record components or is repeatable. The types of those trees are
 * recorded before the conversion and put back after it, without allocating.
 */
final class RecordAnnotationGuard {
    private final List<JCAnnotation> annotations = new ArrayList<>();
    private final List<Type> types = new ArrayList<>();

    /** Records the annotation types of every record component in the unit; changes nothing. */
    RecordAnnotationGuard(JCCompilationUnit unit) {
        new TreeScanner() {
            @Override
            public void visitClassDef(JCClassDecl tree) {
                if (tree.sym != null && tree.sym.isRecord()) {
                    tree.sym.getRecordComponents().forEach(RecordAnnotationGuard.this::record);
                }
                super.visitClassDef(tree);
            }
        }.scan(unit);
    }

    private void record(RecordComponent component) {
        for (JCAnnotation annotation : component.getOriginalAnnos()) {
            annotations.add(annotation);
            types.add(annotation.annotationType.type);
        }
    }

    /** How many recorded annotation trees now carry another type. */
    int changed() {
        int changed = 0;
        for (int i = 0; i < annotations.size(); i++) {
            changed += annotations.get(i).annotationType.type == types.get(i) ? 0 : 1;
        }
        return changed;
    }

    /** Puts every recorded type back; allocates nothing. */
    void restore() {
        for (int i = 0; i < annotations.size(); i++) {
            annotations.get(i).annotationType.type = types.get(i);
        }
    }
}
