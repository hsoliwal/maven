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

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeScanner;

/**
 * An identity digest of a unit's attribution: every node's type and symbol object, every annotation's attribute and
 * the unit's doc comment table. Equal before and after a conversion means the conversion wrote nothing into javac's
 * trees. It is the tripwire of the {@code check=true} option, which the tests turn on.
 */
final class TreeDigest {
    private static final long PRIME = 31;
    private static final long SYMBOL_WEIGHT = 7;

    private TreeDigest() {}

    static long of(JCCompilationUnit unit) {
        long[] hash = {PRIME};
        new TreeScanner() {
            @Override
            public void scan(JCTree tree) {
                if (tree != null) {
                    hash[0] = hash[0] * PRIME + node(tree);
                }
                super.scan(tree);
            }
        }.scan(unit);
        return hash[0] * PRIME + System.identityHashCode(unit.docComments);
    }

    private static long node(JCTree tree) {
        long hash =
                System.identityHashCode(tree.type) + SYMBOL_WEIGHT * System.identityHashCode(TreeInfo.symbolFor(tree));
        return tree instanceof JCAnnotation annotation
                ? hash * PRIME + System.identityHashCode(annotation.attribute)
                : hash;
    }
}
