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
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Read-only view of the verified polyglot task receipts of one module, for a project database to ingest. It reads
 * through the one module-state owner, from the backend of the last clean session only; no state is published,
 * revoked or rotated. A module whose state is held by a running build fails with an
 * {@link IOException}; a backend whose engine this process lacks (for example a JDBC driver) reads as empty.
 */
public final class ModuleStateView {
    /** One receipt: backend, task name, input and output digests, and the module-relative state location. */
    public record TaskState(String backend, String task, String input, String output, String source) {}

    private ModuleStateView() {}

    /** The receipts of the module's active backend, sorted by task name. */
    public static List<TaskState> read(Path module) throws IOException {
        M3DbModuleStore.Snapshot snapshot;
        try {
            snapshot = M3DbModuleStore.snapshot(module);
        } catch (M3DbModuleStore.BackendUnavailableException absentEngine) {
            return List.of();
        }
        String location = location(snapshot.backend());
        return snapshot.receipts().stream().map(receipt -> new TaskState(snapshot.backend(), receipt.task(),
                receipt.input(), receipt.output(), location)).toList();
    }

    /** Where a backend keeps its state, relative to the module; providers own theirs, so the marker stands in. */
    static String location(String backend) {
        return switch (backend) {
            case "embedded" -> M3DbModuleStore.FILE;
            case "file" -> M3DbModuleStore.FILE_BACKEND;
            case "m3db" -> M3DbModuleStore.NATIVE_DIRECTORY;
            case "sqlite" -> M3ModuleJdbcStorage.SQLITE_FILE;
            case "h2" -> M3ModuleJdbcStorage.H2_FILE;
            case "hsqldb" -> M3ModuleJdbcStorage.HSQLDB_FILE;
            default -> M3DbModuleStore.MARKER;
        };
    }
}
