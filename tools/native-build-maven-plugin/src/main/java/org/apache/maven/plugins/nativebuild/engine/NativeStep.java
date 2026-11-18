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

import java.util.List;
import java.util.Map;

/**
 * One planned tool invocation: an executable and its argument list (never a shell command line), the
 * project-relative working directory, and the declared inputs and outputs that polyglot's opt-in content stamps
 * hash. A step without both inputs and outputs always runs and leaves up-to-date checks to the native tool.
 *
 * @param phase the step kind, which also names the polyglot task
 * @param tool the executable: an absolute path when resolved, else a bare name looked up on PATH
 * @param workingDir project-relative working directory
 * @param arguments the argument list
 * @param inputs project-relative declared inputs
 * @param outputs project-relative declared outputs
 * @param environment variables added to the inherited environment for this step only (native dependency paths
 *     for Make), part of the step's content stamp
 */
public record NativeStep(
        NativePhase phase,
        String tool,
        String workingDir,
        List<String> arguments,
        List<String> inputs,
        List<String> outputs,
        Map<String, String> environment) {
    /** Copies the lists. */
    public NativeStep {
        arguments = List.copyOf(arguments);
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        environment = Map.copyOf(environment);
    }

    /** A step without environment additions. */
    public NativeStep(NativePhase phase, String tool, String workingDir, List<String> arguments, List<String> inputs,
            List<String> outputs) {
        this(phase, tool, workingDir, arguments, inputs, outputs, Map.of());
    }

    /** The polyglot task name. */
    public String name() {
        return phase.task();
    }

    /** {@code tool arg1 arg2 ...} for logs only; arguments are never joined for execution. */
    public String display() {
        return tool + (arguments.isEmpty() ? "" : " " + String.join(" ", arguments));
    }
}
