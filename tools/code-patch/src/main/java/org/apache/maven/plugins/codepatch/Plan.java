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
package org.apache.maven.plugins.codepatch;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.maven.plugins.codepatch.FilePatch.Operation;

/**
 * A computed plan: its id, its rows sorted by path, and the file contents in memory: {@code before} holds the current
 * content of every file a row reads (a RENAME's under its old path), {@code after} the after-image of every file a row
 * writes. The id is the SHA-256 over every patch's length and bytes, in order, followed by the rows: it changes when a
 * patch changes or when a target file no longer has the content the plan was computed from.
 */
public record Plan(String id, List<PlanRow> rows, Map<String, byte[]> before, Map<String, byte[]> after) {
    public Plan {
        rows = List.copyOf(rows);
        before = Map.copyOf(before);
        after = Map.copyOf(after);
    }

    /** PLAN.tsv: a header and one row per file. */
    public String tsv() {
        return PlanRow.HEADER + "\n" + rows.stream().map(r -> r.tsv() + "\n").collect(Collectors.joining());
    }

    public String summary() {
        Map<Operation, Integer> counts = new EnumMap<>(Operation.class);
        rows.forEach(r -> counts.merge(r.operation(), 1, Integer::sum));
        return "plan " + id + ": " + rows.size() + " files" + counts.entrySet().stream()
                .map(e -> " " + e.getKey() + " " + e.getValue()).collect(Collectors.joining());
    }
}
