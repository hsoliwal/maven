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
package org.apache.maven.plugins.agent;

import java.util.List;
import java.util.stream.Collectors;
import org.apache.maven.agent.events.JsonText;

/**
 * A build as an agent needs it, the content of {@code project.json}: modules in reactor order with their module
 * dependencies, root-relative source and test roots and bound goals, and the commands to build and test. No
 * timestamps or absolute paths, so the same build gives the same bytes.
 *
 * @param name the top-level project's name
 * @param description the top-level project's description, "" when it has none (not written to project.json)
 * @param modules the modules in reactor order
 * @param commands build, agent, module, test and integration-test command lines
 */
record ProjectDescription(String name, String description, List<Module> modules, Commands commands) {

    /** One goal bound to a module's lifecycle up to the described phase. */
    record Goal(String phase, String plugin, String goal, String execution) {
        JsonText.Obj json() {
            return new JsonText.Obj().put("phase", JsonText.orNull(phase)).put("plugin", plugin).put("goal", goal)
                    .put("execution", execution);
        }

        /** {@code compiler:compile}: the plugin prefix by Maven's naming convention, and the goal. */
        String shortName() {
            String artifact = plugin.split(":")[1];
            String prefix = artifact.startsWith("maven-") && artifact.endsWith("-plugin")
                    ? artifact.substring(6, artifact.length() - 7)
                    : artifact.endsWith("-maven-plugin") ? artifact.substring(0, artifact.length() - 13) : artifact;
            return prefix + ":" + goal;
        }
    }

    /** One module. */
    record Module(int index, String id, String path, String packaging, String version, List<String> dependsOn,
            List<String> sourceRoots, List<String> testRoots, List<Goal> goals) {
        JsonText.Obj json() {
            return new JsonText.Obj().put("index", index).put("id", id).put("path", path).put("packaging", packaging)
                    .put("version", version).put("dependsOn", dependsOn).put("sourceRoots", sourceRoots)
                    .put("testRoots", testRoots)
                    .put("goals", goals.stream().map(Goal::json).collect(Collectors.toList()));
        }
    }

    /**
     * Command lines with {@code <path>} and {@code <Class>#<method>} placeholders, started by {@code launcher}
     * ({@code mvn} or {@code ./mvnw}, not written to project.json: llms.txt gives its spelling per shell).
     */
    record Commands(String launcher, String build, String agent, String module, String test, String integrationTest) {
        JsonText.Obj json() {
            return new JsonText.Obj().put("build", build).put("agent", agent).put("module", module).put("test", test)
                    .put("integrationTest", integrationTest);
        }
    }

    /** {@code project.json}. */
    String json() {
        return new JsonText.Obj().put("v", 1).put("name", name)
                .put("modules", modules.stream().map(Module::json).collect(Collectors.toList()))
                .put("commands", commands.json()).text() + "\n";
    }
}
