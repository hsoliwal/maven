/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.recipes;

import static org.openrewrite.java.Assertions.java;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

/** Exact original expression cases and untargeted-switch contract. */
class MachineFlowJava17SwitchesTest implements RewriteTest {
    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("org.apache.maven.recipes.M3MachineFlowJava17");
    }

    @Test
    void lowersOnlyTheTwoExactCompilerSwitches() {
        rewriteRun(java(
                """
                package io.synexia.maven.smart;
                import com.sun.source.tree.*;
                import com.sun.source.util.TreePath;
                import java.util.List;
                class MachineFlowAnalysis {
                    static Tree run(TreePath atom) {
                        Tree executable = switch (atom.getLeaf()) {
                            case MethodTree method -> method.getBody();
                            case LambdaExpressionTree lambda -> lambda.getBody();
                            case BlockTree block -> block;
                            default -> null;
                        };
                        List<? extends VariableTree> parameters = switch (atom.getLeaf()) {
                            case MethodTree method -> method.getParameters();
                            case LambdaExpressionTree lambda -> lambda.getParameters();
                            default -> List.of();
                        };
                        return executable;
                    }
                    static int untouched(int n) {
                        return switch (n) {
                            case 1 -> 2;
                            default -> 3;
                        };
                    }
                }
                """,
                """
                package io.synexia.maven.smart;
                import com.sun.source.tree.*;
                import com.sun.source.util.TreePath;
                import java.util.List;
                class MachineFlowAnalysis {
                    static Tree run(TreePath atom) {
                        Tree executable = java.util.Objects.requireNonNull(atom.getLeaf()) instanceof MethodTree method ? method.getBody() : atom.getLeaf() instanceof LambdaExpressionTree lambda ? lambda.getBody() : atom.getLeaf() instanceof BlockTree block ? block : null;
                        List<? extends VariableTree> parameters = java.util.Objects.requireNonNull(atom.getLeaf()) instanceof MethodTree method ? method.getParameters() : atom.getLeaf() instanceof LambdaExpressionTree lambda ? lambda.getParameters() : java.util.List.of();
                        return executable;
                    }
                    static int untouched(int n) {
                        return switch (n) {
                            case 1 -> 2;
                            default -> 3;
                        };
                    }
                }
                """,
                spec -> spec.path("src/main/java/io/synexia/maven/smart/MachineFlowAnalysis.java")));
    }

    @Test
    void doesNotModifyOtherJavaFiles() {
        rewriteRun(java(
                """
                package example;
                import com.sun.source.tree.*;
                import com.sun.source.util.TreePath;
                class DifferentClass {
                    Tree calculate(TreePath atom) {
                        return switch (atom.getLeaf()) {
                            case MethodTree method -> method.getBody();
                            case BlockTree block -> block;
                            default -> null;
                        };
                    }
                }
                """,
                spec -> spec.path("src/main/java/example/DifferentClass.java")));
    }
}
