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

import java.util.Map;
import java.util.UUID;

import org.openrewrite.ExecutionContext;
import org.openrewrite.yaml.YamlIsoVisitor;
import org.openrewrite.yaml.tree.Yaml;

/**
 * Puts moved first lines in front of the prefixes of the sequence entries they were moved to, by entry id: the
 * same-line comment of a block entry that has a new entry inserted after it, taken from the prefix of the node after
 * the sequence (see {@link SequenceInsert}).
 */
final class MovedFirstLines extends YamlIsoVisitor<ExecutionContext> {
    private final Map<UUID, String> firstLines;

    MovedFirstLines(Map<UUID, String> firstLines) {
        this.firstLines = Map.copyOf(firstLines);
    }

    @Override
    public Yaml.Sequence.Entry visitSequenceEntry(Yaml.Sequence.Entry entry, ExecutionContext ctx) {
        Yaml.Sequence.Entry visited = super.visitSequenceEntry(entry, ctx);
        String firstLine = firstLines.get(visited.getId());
        return firstLine == null ? visited : visited.withPrefix(firstLine + visited.getPrefix());
    }
}
