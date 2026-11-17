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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.openrewrite.ExecutionContext;
import org.openrewrite.yaml.YamlIsoVisitor;
import org.openrewrite.yaml.tree.Yaml;

/**
 * Adds a Java major version to the matrix axis lists that the job's {@code actions/setup-java} steps read through
 * {@code java-version: ${{ matrix.<axis> }}}, when a list lacks it (see {@link MatrixAxisInsert}). At each job it
 * records those axes on the cursor, so the matrix under the job can find them; {@code include} and {@code exclude}
 * are never changed. When a new entry goes last in a block list, the first line of the next node's prefix (the
 * comment of the old last entry) is cut on the next visit and put in front of the new entry's prefix once the
 * document has been visited.
 */
final class MatrixVersionInsertVisitor extends YamlIsoVisitor<ExecutionContext> {
    private static final String AXES = "setupJavaMatrixAxes";
    private static final String TAKES_NEXT_FIRST_LINE = "matrixVersionTakesNextFirstLine";
    private static final String MOVED_FIRST_LINES = "matrixVersionMovedFirstLines";

    private final int version;

    MatrixVersionInsertVisitor(int version) {
        this.version = version;
    }

    @Override
    public Yaml preVisit(Yaml tree, ExecutionContext ctx) {
        UUID taker = getCursor().getRoot().pollMessage(TAKES_NEXT_FIRST_LINE);
        if (taker == null) {
            return tree;
        }
        String prefix = tree.getPrefix();
        int lineBreak = prefix.indexOf('\n');
        String firstLine = lineBreak < 0 ? prefix : prefix.substring(0, lineBreak);
        getCursor().getRoot().<Map<UUID, String>>computeMessageIfAbsent(MOVED_FIRST_LINES, key -> new HashMap<>())
                .put(taker, firstLine);
        return tree.withPrefix(RaisedDuplicates.withFirstLine(prefix, ""));
    }

    @Override
    public Yaml.Document visitDocument(Yaml.Document document, ExecutionContext ctx) {
        Yaml.Document visited = super.visitDocument(document, ctx);
        Map<UUID, String> moved = getCursor().getRoot().pollMessage(MOVED_FIRST_LINES);
        return moved == null
                ? visited
                : (Yaml.Document) new MovedFirstLines(moved).visitNonNull(visited, ctx, getCursor().getParentOrThrow());
    }

    @Override
    public Yaml.Mapping.Entry visitMappingEntry(Yaml.Mapping.Entry entry, ExecutionContext ctx) {
        List<String> keyPath = YamlBlocks.keyPath(getCursor());
        if (WorkflowLayout.isJob(keyPath)) {
            getCursor().putMessage(AXES, WorkflowLayout.setupJavaAxes(entry.getValue()));
        }
        Yaml.Mapping.Entry visited = super.visitMappingEntry(entry, ctx);
        if (!WorkflowLayout.isMatrixAxis(keyPath)
                || !(visited.getValue() instanceof Yaml.Sequence axis)
                || !isReadBySetupJava(visited.getKey().getValue())) {
            return visited;
        }
        return MatrixAxisInsert.insert(axis, version)
                .map(inserted -> withInserted(visited, inserted))
                .orElse(visited);
    }

    private Yaml.Mapping.Entry withInserted(Yaml.Mapping.Entry axis, SequenceInsert.Result inserted) {
        inserted.takesNextFirstLine().ifPresent(id -> getCursor().getRoot().putMessage(TAKES_NEXT_FIRST_LINE, id));
        return axis.withValue(inserted.sequence());
    }

    private boolean isReadBySetupJava(String key) {
        return !WorkflowLayout.INCLUDE.equals(key)
                && !WorkflowLayout.EXCLUDE.equals(key)
                && getCursor().<Set<String>>getNearestMessage(AXES, Set.of()).contains(key);
    }
}
