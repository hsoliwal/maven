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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plan contexts over scratch projects with fixed tool paths, for exact argument-list checks. */
final class PlanFixtures {
    static final Platform LINUX = Platform.of("Linux", "amd64");
    static final Platform WINDOWS = Platform.of("Windows 11", "amd64");
    static final String BUILD = "target/native/test-classifier";
    static final String STAGE = "target/native/stage/test-classifier";

    private PlanFixtures() {}

    static PlanContext context(Path base, BuildSystem system, String descriptor, Platform platform,
            NativeOptions options, Map<String, String> env, ToolSet.Tool... tools) {
        Layout layout = Layout.of(base, base, base.resolve(BUILD), base.resolve(STAGE),
                base.resolve("target/native-reports"));
        return new PlanContext(new Detection(system, descriptor, "test"), layout, options, ToolSet.of(List.of(tools)),
                platform, env, true);
    }

    static ToolSet.Tool path(String name, String path) {
        return new ToolSet.Tool(name, path, ToolSet.Origin.PATH);
    }

    static ToolSet.Tool configured(String name, String path) {
        return new ToolSet.Tool(name, path, ToolSet.Origin.TOOLS);
    }

    static NativeOptions.Builder options() {
        return NativeOptions.builder().jobs(4);
    }

    /** Tool then arguments, as the process would see them. */
    static List<String> argv(NativeStep step) {
        return Steps.list(step.tool(), step.arguments());
    }

    static List<String> names(List<NativeStep> steps) {
        return steps.stream().map(NativeStep::name).toList();
    }

    static List<String> mavenPhases(List<NativeStep> steps) {
        return steps.stream().map(s -> s.phase().mavenPhase()).toList();
    }

    /** True when no step starts a shell to interpret a command string. */
    static boolean noShellCommand(List<NativeStep> steps) {
        Set<String> shells = Set.of("sh", "bash", "dash", "zsh", "cmd", "powershell", "pwsh");
        return steps.stream().noneMatch(s -> shells.contains(WindowsPaths.stem(s.tool()).toLowerCase(java.util.Locale.ROOT))
                && (s.arguments().isEmpty() || s.arguments().get(0).matches("(?i)-c|/c|/k|-command")));
    }
}
