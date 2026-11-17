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

import java.util.List;

import org.openrewrite.ExecutionContext;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

/** Marks the constructor calls of artifact filters that {@link ArtifactFilterRequests} maps. */
final class ArtifactFilterMarkers extends JavaIsoVisitor<ExecutionContext> {
    @Override
    public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
        J.NewClass visited = super.visitNewClass(newClass, ctx);
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(visited.getType());
        if (type == null) {
            return visited;
        }
        List<ArtifactFilterRequests.Argument> arguments = visited.getArguments().stream()
                .filter(argument -> !(argument instanceof J.Empty))
                .map(this::argument)
                .toList();
        return ArtifactFilterRequests.marker(type.getFullyQualifiedName(), arguments)
                .map(message -> SearchResult.found(visited, message))
                .orElse(visited);
    }

    private ArtifactFilterRequests.Argument argument(Expression expression) {
        String source = expression.printTrimmed(getCursor());
        return expression instanceof J.Literal literal
                ? new ArtifactFilterRequests.Argument(source, true, literal.getValue())
                : new ArtifactFilterRequests.Argument(source, false, null);
    }
}
