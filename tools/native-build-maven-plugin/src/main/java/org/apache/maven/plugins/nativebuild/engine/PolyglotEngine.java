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
package org.apache.maven.plugins.nativebuild.engine;

import io.synexia.maven.polyglot.TaskManifest;
import io.synexia.maven.polyglot.TaskRunner;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * The only class that refers to the polyglot task engine: its manifest loader (validation, dependency order) and its
 * runner (argument-list process start in the declared working directory, per-task timeout, declared output checks,
 * opt-in content stamps in {@code <project>/.m3/polyglot-stamps}). The engine is used unchanged.
 */
public final class PolyglotEngine {
    /** The manifest format line, taken from the engine. */
    public static final String MAGIC = TaskManifest.MAGIC;

    /**
     * A loaded task, as the engine validated it.
     *
     * @param name task name
     * @param tool executable, or {@code -} for an aggregation target
     * @param workingDir project-relative working directory
     * @param arguments argument list
     * @param prerequisites tasks that run first
     * @param inputs declared inputs
     * @param outputs declared outputs
     */
    public record TaskView(String name, String tool, String workingDir, List<String> arguments,
            List<String> prerequisites, List<String> inputs, List<String> outputs) {}

    private PolyglotEngine() {}

    /** Loads and validates a manifest, in the engine's dependency order. */
    public static List<TaskView> load(Path manifest) throws IOException {
        return TaskManifest.load(manifest).orderAll().stream().map(t -> new TaskView(t.name(), t.tool(),
                t.workingDir(), t.arguments(), t.prerequisites(), t.inputs(), t.outputs())).toList();
    }

    /**
     * Runs {@code target} and its prerequisites. Returns the tasks performed or found up to date. A tool that exits
     * non-zero, times out or leaves a declared output missing fails with an {@link IOException}.
     */
    public static List<String> run(Path manifest, Path workspace, String target, boolean incremental,
            Duration timeout, Consumer<String> log) throws IOException, InterruptedException {
        return TaskRunner.run(TaskManifest.load(manifest), workspace, target, false, timeout, false, incremental, log);
    }
}
