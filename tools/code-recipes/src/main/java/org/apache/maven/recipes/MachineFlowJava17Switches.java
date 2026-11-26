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

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.J;

/**
 * Two strictly scoped Java 21 pattern-switch expressions in MachineFlowAnalysis
 * are mechanically lowered to Java 17 pattern-instanceof conditional expressions.
 * Only the exact original expressions are replaced; other switches are untouched.
 * The initial requireNonNull retains the original switch-on-null failure contract.
 */
public final class MachineFlowJava17Switches extends Recipe {
    private static final String EXECUTABLE = "switch(atom.getLeaf()){"
            + "caseMethodTreemethod->method.getBody();"
            + "caseLambdaExpressionTreelambda->lambda.getBody();"
            + "caseBlockTreeblock->block;default->null;}";
    private static final String PARAMETERS = "switch(atom.getLeaf()){"
            + "caseMethodTreemethod->method.getParameters();"
            + "caseLambdaExpressionTreelambda->lambda.getParameters();"
            + "default->List.of();}";

    private static final String EXECUTABLE_17 =
            "java.util.Objects.requireNonNull(atom.getLeaf()) instanceof MethodTree method "
                    + "? method.getBody() : atom.getLeaf() instanceof LambdaExpressionTree lambda "
                    + "? lambda.getBody() : atom.getLeaf() instanceof BlockTree block ? block : null";
    private static final String PARAMETERS_17 =
            "java.util.Objects.requireNonNull(atom.getLeaf()) instanceof MethodTree method "
                    + "? method.getParameters() : atom.getLeaf() instanceof LambdaExpressionTree lambda "
                    + "? lambda.getParameters() : java.util.List.of()";

    @Override
    public String getDisplayName() {
        return "Lower M3 compiler may-flow pattern switches to Java 17";
    }

    @Override
    public String getDescription() {
        return "Mechanically replaces only the two exact Java 21 pattern-switch "
                + "expressions in MachineFlowAnalysis with equivalent Java 17 pattern-instanceof "
                + "conditionals, preserving null failures and all non-target switches. "
                + "Idempotent, source-scoped, without changing compiler flow facts.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitSwitchExpression(J.SwitchExpression original, ExecutionContext ctx) {
                J visited = super.visitSwitchExpression(original, ctx);
                if (!(visited instanceof J.SwitchExpression current)) {
                    return visited;
                }
                J.CompilationUnit unit = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                if (!"MachineFlowAnalysis.java".equals(unit.getSourcePath().getFileName().toString())) {
                    return current;
                }
                String compact = current.printTrimmed(getCursor()).replaceAll("\\s+", "");
                String replacement;
                if (EXECUTABLE.equals(compact)) {
                    replacement = EXECUTABLE_17;
                } else if (PARAMETERS.equals(compact)) {
                    replacement = PARAMETERS_17;
                } else {
                    return current;
                }
                return JavaTemplate.builder(replacement)
                        .contextSensitive()
                        .imports("com.sun.source.tree.MethodTree",
                                "com.sun.source.tree.LambdaExpressionTree",
                                "com.sun.source.tree.BlockTree",
                                "java.util.List",
                                "java.util.Objects")
                        .build()
                        .apply(getCursor(), current.getCoordinates().replace());
            }
        };
    }
}
