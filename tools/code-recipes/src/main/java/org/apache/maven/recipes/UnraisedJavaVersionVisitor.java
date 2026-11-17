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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.openrewrite.ExecutionContext;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.yaml.YamlIsoVisitor;
import org.openrewrite.yaml.tree.Yaml;

/**
 * Marks the {@code actions/setup-java} Java versions below a minimum in the shapes that
 * {@link SetupJavaVersionVisitor} does not raise: a {@code with}, a matrix or a matrix {@code include} or
 * {@code exclude} object written as a nested flow mapping, a multi-line {@code java-version}, a {@code java-version}
 * of a composite action ({@code runs.steps}), and an {@code exclude} value that matches no value of its axis list
 * (left when the raise merged that value into another).
 * A {@code ${{ matrix.<axis> }}} reference in a flow mapping or a multi-line value is marked too, since the axis it
 * reads is not raised either. It adds search markers only.
 */
final class UnraisedJavaVersionVisitor extends YamlIsoVisitor<ExecutionContext> {
    static final String FLOW_INPUT = "java-version in a flow mapping is not raised";
    static final String FLOW_MATRIX = "matrix in a flow mapping is not raised";
    static final String MULTI_LINE = "multi-line java-version is not raised";
    static final String COMPOSITE = "java-version of a composite action is not raised";
    private static final String AXES = "setupJavaMatrixAxes";
    private static final String MATRIX_REFERENCE = "matrix.";

    private final int minimum;

    UnraisedJavaVersionVisitor(int minimum) {
        this.minimum = minimum;
    }

    @Override
    public Yaml.Mapping.Entry visitMappingEntry(Yaml.Mapping.Entry entry, ExecutionContext ctx) {
        List<String> keyPath = YamlBlocks.keyPath(getCursor());
        if (WorkflowLayout.isJob(keyPath)) {
            getCursor().putMessage(AXES, WorkflowLayout.setupJavaAxes(entry.getValue()));
        }
        Yaml.Mapping.Entry visited = super.visitMappingEntry(entry, ctx);
        return visited.getValue() instanceof Yaml.Scalar scalar
                ? problem(keyPath, scalar).map(found -> visited.withValue(SearchResult.found(scalar, found)))
                        .orElse(visited)
                : visited;
    }

    private Optional<String> problem(List<String> keyPath, Yaml.Scalar scalar) {
        return flowInput(keyPath, scalar)
                .or(() -> javaVersion(keyPath, scalar))
                .or(() -> flowMatrix(keyPath, scalar))
                .or(() -> staleExclude(keyPath, scalar));
    }

    private Optional<String> flowInput(List<String> keyPath, Yaml.Scalar with) {
        boolean found = WorkflowLayout.isStepWith(keyPath)
                && FlowText.isFlowMapping(with)
                && WorkflowLayout.isInSetupJavaStep(getCursor(), 0)
                && FlowText.values(with.getValue(), "java-version").stream().anyMatch(this::unraised);
        return found ? Optional.of(FLOW_INPUT) : Optional.empty();
    }

    private Optional<String> javaVersion(List<String> keyPath, Yaml.Scalar javaVersion) {
        boolean workflow = WorkflowLayout.isStepJavaVersion(keyPath);
        if (!(workflow || WorkflowLayout.isActionStepJavaVersion(keyPath))
                || !WorkflowLayout.isInSetupJavaStep(getCursor(), 1)) {
            return Optional.empty();
        }
        List<String> lines = VersionScalar.lines(javaVersion);
        if (lines.stream().noneMatch(this::unraised)) {
            return Optional.empty();
        }
        return lines.size() > 1 ? Optional.of(MULTI_LINE) : Optional.of(COMPOSITE).filter(found -> !workflow);
    }

    /** An {@code include} or {@code exclude} object written as a nested flow mapping, {@code [{java: 17}]}. */
    @Override
    public Yaml.Sequence.Entry visitSequenceEntry(Yaml.Sequence.Entry entry, ExecutionContext ctx) {
        boolean matrixObject = WorkflowLayout.isMatrixObject(YamlBlocks.keyPath(getCursor()));
        Yaml.Sequence.Entry visited = super.visitSequenceEntry(entry, ctx);
        return matrixObject && visited.getBlock() instanceof Yaml.Scalar object && isFlowMatrixBelow(object)
                ? visited.withBlock(SearchResult.found(object, FLOW_MATRIX))
                : visited;
    }

    private Optional<String> flowMatrix(List<String> keyPath, Yaml.Scalar matrix) {
        boolean found = WorkflowLayout.isMatrix(keyPath) && isFlowMatrixBelow(matrix);
        return found ? Optional.of(FLOW_MATRIX) : Optional.empty();
    }

    /** A flow mapping text with a value below the minimum for an axis a setup-java step of the job reads. */
    private boolean isFlowMatrixBelow(Yaml.Scalar flow) {
        Set<String> axes = getCursor().getNearestMessage(AXES, Set.of());
        return FlowText.isFlowMapping(flow)
                && axes.stream()
                        .flatMap(axis -> FlowText.values(flow.getValue(), axis).stream())
                        .anyMatch(value -> JavaVersions.below(value, minimum));
    }

    private Optional<String> staleExclude(List<String> keyPath, Yaml.Scalar value) {
        String axis = keyPath.get(keyPath.size() - 1);
        String version = VersionScalar.content(value);
        boolean readBySetupJava = getCursor().<Set<String>>getNearestMessage(AXES, Set.of()).contains(axis);
        if (!WorkflowLayout.isExcludeValue(keyPath) || !readBySetupJava || !JavaVersions.below(version, minimum)) {
            return Optional.empty();
        }
        return axisValues(axis)
                .filter(values -> !values.contains(version))
                .map(values -> "Java " + version + " matches no value of matrix axis " + axis);
    }

    /** The values of the axis list of the matrix that holds the cursor, if the matrix has one. */
    private Optional<Set<String>> axisValues(String axis) {
        Yaml.Mapping.Entry matrix = getCursor()
                .dropParentUntil(tree -> tree instanceof Yaml.Mapping.Entry entry
                        && "matrix".equals(entry.getKey().getValue()))
                .getValue();
        return YamlBlocks.child(matrix.getValue(), axis)
                .filter(Yaml.Sequence.class::isInstance)
                .map(list -> MatrixAxis.values((Yaml.Sequence) list).collect(Collectors.toUnmodifiableSet()));
    }

    private boolean unraised(String version) {
        return JavaVersions.below(version, minimum) || version.contains(MATRIX_REFERENCE);
    }
}
