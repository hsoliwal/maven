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
package org.apache.maven.recipes;

import java.util.Set;
import java.util.TreeSet;

import org.openrewrite.ExecutionContext;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

/** Marks {@code filterResources} calls of sources that use the Maven 4 API; see {@link UseCoreResourceFilter}. */
final class ResourceFilterCallVisitor extends JavaIsoVisitor<ExecutionContext> {

    static final String FILTER_RESOURCES = "org.apache.maven.shared.filtering.MavenResourcesFiltering "
            + "filterResources(org.apache.maven.shared.filtering.MavenResourcesExecution)";

    private static final String MAVEN_4_API = "org.apache.maven.api.";

    private static final MethodMatcher MATCHER = new MethodMatcher(FILTER_RESOURCES, true);

    @Override
    public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
        return usesMaven4Api(cu) ? super.visitCompilationUnit(cu, ctx) : cu;
    }

    @Override
    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
        J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
        if (!MATCHER.matches(m)) {
            return m;
        }
        return SearchResult.found(m, ResourceFilterOptions.describe(setters(m)));
    }

    /** The setters called in the enclosing method on the variable passed to {@code filterResources}. */
    private Set<String> setters(J.MethodInvocation call) {
        J.MethodDeclaration enclosing = getCursor().firstEnclosing(J.MethodDeclaration.class);
        if (enclosing == null || !(call.getArguments().get(0) instanceof J.Identifier execution)) {
            return Set.of();
        }
        return new SetterCollector(execution.getSimpleName()).reduce(enclosing, new TreeSet<>());
    }

    /** Collects the names of the methods called on one variable. */
    private static final class SetterCollector extends JavaIsoVisitor<Set<String>> {
        private final String variable;

        SetterCollector(String variable) {
            this.variable = variable;
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation m, Set<String> found) {
            if (m.getSelect() instanceof J.Identifier select && select.getSimpleName().equals(variable)) {
                found.add(m.getSimpleName());
            }
            return super.visitMethodInvocation(m, found);
        }
    }

    private static boolean usesMaven4Api(J.CompilationUnit cu) {
        boolean imported = cu.getImports().stream().anyMatch(i -> i.getTypeName().startsWith(MAVEN_4_API));
        return imported
                || cu.getTypesInUse().getTypesInUse().stream()
                        .map(TypeUtils::asFullyQualified)
                        .anyMatch(type -> type != null && inMaven4Api(type));
    }

    private static boolean inMaven4Api(JavaType.FullyQualified type) {
        return type.getFullyQualifiedName().startsWith(MAVEN_4_API);
    }
}
