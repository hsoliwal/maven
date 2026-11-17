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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;
import org.apache.maven.plugins.codepatch.FilePatch.Operation;

/**
 * The plan rows whose file gives no atoms before and none after (text, XML, properties, a language the code
 * clustering plugin does not read, or a path it skips): verify cannot compare them, so they become COMPARE.tsv rows
 * with the state {@code NO_ATOMS}, kind {@code FILE}, the new path in {@code path} and the old one in
 * {@code baseline_path} (empty where there is no such file).
 */
final class NoAtomRows {
    private NoAtomRows() { }

    static List<String> rows(Plan plan, List<Atom> before, List<Atom> after) {
        Set<String> was = before.stream().map(Atom::path).collect(Collectors.toSet());
        Set<String> now = after.stream().map(Atom::path).collect(Collectors.toSet());
        List<String> rows = new ArrayList<>();
        for (PlanRow row : plan.rows()) {
            String old = row.operation() == Operation.ADD ? "" : row.operation() == Operation.RENAME ? row.fromPath() : row.path();
            String path = row.operation() == Operation.DELETE ? "" : row.path();
            if (!was.contains(old) && !now.contains(path)) rows.add(String.join("\t", FailOn.NO_ATOMS, path, "FILE", "", old, ""));
        }
        return rows;
    }

    /** The file a row names: its path, or its baseline path when the file is gone. */
    static String file(String row) {
        String[] columns = row.split("\t", -1);
        return columns.length > 4 && columns[1].isEmpty() ? columns[4] : columns.length > 1 ? columns[1] : row;
    }
}
