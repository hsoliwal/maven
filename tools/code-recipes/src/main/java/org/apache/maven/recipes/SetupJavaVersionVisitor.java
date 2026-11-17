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
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import org.openrewrite.ExecutionContext;
import org.openrewrite.yaml.YamlIsoVisitor;
import org.openrewrite.yaml.tree.Yaml;

/**
 * Raises the {@code java-version} of {@code actions/setup-java} steps to a minimum: a scalar value directly, and a
 * {@code ${{ matrix.<axis> }}} reference through the job's matrix: the axis list, the axis values of the
 * {@code include} objects, and those of the {@code exclude} objects whose value the axis raise renames (an exclude
 * of a value merged into another one is kept, since it then matches no job). At each job it records the axes its
 * setup-java steps read on the cursor, so the matrix under the job can find them. Values that are not scalars
 * (aliases, sequences) are left alone. When the last entries of a block list are dropped, the first line of the
 * next node's prefix (which holds the comment of the last dropped entry) is replaced on the next visit.
 */
final class SetupJavaVersionVisitor extends YamlIsoVisitor<ExecutionContext> {
    private static final String AXES = "setupJavaMatrixAxes";
    private static final String NEXT_FIRST_LINE = "setupJavaNextFirstLine";

    private final int minimum;

    SetupJavaVersionVisitor(int minimum) {
        this.minimum = minimum;
    }

    @Override
    public Yaml preVisit(Yaml tree, ExecutionContext ctx) {
        String firstLine = getCursor().getRoot().pollMessage(NEXT_FIRST_LINE);
        return firstLine == null ? tree : tree.withPrefix(RaisedDuplicates.withFirstLine(tree.getPrefix(), firstLine));
    }

    @Override
    public Yaml.Mapping.Entry visitMappingEntry(Yaml.Mapping.Entry entry, ExecutionContext ctx) {
        List<String> keyPath = YamlBlocks.keyPath(getCursor());
        if (WorkflowLayout.isJob(keyPath)) {
            getCursor().putMessage(AXES, WorkflowLayout.setupJavaAxes(entry.getValue()));
        }
        Yaml.Mapping.Entry visited = super.visitMappingEntry(entry, ctx);
        if (WorkflowLayout.isStepJavaVersion(keyPath) && WorkflowLayout.isInSetupJavaStep(getCursor(), 1)) {
            return raiseScalar(visited);
        }
        return WorkflowLayout.isMatrixAxis(keyPath) && visited.getValue() instanceof Yaml.Sequence
                ? raiseList(visited)
                : visited;
    }

    private Yaml.Mapping.Entry raiseScalar(Yaml.Mapping.Entry javaVersion) {
        return javaVersion.getValue() instanceof Yaml.Scalar scalar
                ? javaVersion.withValue(VersionScalar.raise(scalar, minimum))
                : javaVersion;
    }

    private Yaml.Mapping.Entry raiseList(Yaml.Mapping.Entry list) {
        RaisedDuplicates.Result raised = raise(list.getKey().getValue(), (Yaml.Sequence) list.getValue());
        raised.nextFirstLine().ifPresent(line -> getCursor().getRoot().putMessage(NEXT_FIRST_LINE, line));
        return list.withValue(raised.sequence());
    }

    private RaisedDuplicates.Result raise(String key, Yaml.Sequence list) {
        Set<String> axes = getCursor().getNearestMessage(AXES, Set.of());
        return switch (key) {
            case WorkflowLayout.INCLUDE -> MatrixEntries.raise(list, (axis, value) -> axes.contains(axis), minimum);
            case WorkflowLayout.EXCLUDE -> MatrixEntries.raise(list, renamedValues(axes), minimum);
            default -> axes.contains(key) ? MatrixAxis.raise(list, minimum) : RaisedDuplicates.unchanged(list);
        };
    }

    /** Accepts the exclude values that the raise of their axis list renames; the cursor is on the exclude entry. */
    private BiPredicate<String, String> renamedValues(Set<String> axes) {
        Yaml.Mapping matrix = getCursor().getParentTreeCursor().getValue();
        Map<String, Set<String>> renamed = axes.stream()
                .collect(Collectors.toMap(axis -> axis, axis -> MatrixAxis.renamedValues(matrix, axis, minimum)));
        return (axis, value) -> renamed.getOrDefault(axis, Set.of()).contains(value);
    }
}
