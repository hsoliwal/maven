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
package org.apache.maven.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Maven arguments the server adds around a client's goals: batch mode, no transfer progress, no colour, the log
 * in the run directory, the build-events extension when one was configured, and the agent properties that tell the
 * extension where to write the event stream and the failure digest (formats: README, "Files the server reads").
 */
final class MavenCommands {

    static final String LOG = "build.log";
    static final String EVENTS = "build-events.jsonl";
    static final String FAILURE = "failure.json";
    static final String FAILURE_MD = "failure.md";
    static final String SUMMARY = "summary.txt";

    static final String HELP_PLUGIN = "org.apache.maven.plugins:maven-help-plugin:3.5.1";
    static final String DEPENDENCY_PLUGIN = "org.apache.maven.plugins:maven-dependency-plugin:3.11.0";

    private MavenCommands() {}

    /** Arguments every run starts with; the log goes to {@code dir/build.log}. */
    static List<String> base(Path dir, ServerSettings settings, boolean offline) {
        List<String> args = new ArrayList<>(List.of("-B", "-V", "-ntp", "-Dstyle.color=never"));
        args.add("-l");
        args.add(ArgumentGuard.path(dir.resolve(LOG)));
        settings.extension().ifPresent(jar -> args.add("-Dmaven.ext.class.path=" + ArgumentGuard.path(jar)));
        if (offline) {
            args.add("-o");
        }
        return args;
    }

    /** The properties that switch the build-events extension on and point it at the run directory. */
    static List<String> agent(Path dir) {
        return List.of(
                "-Dmaven.agent=true",
                "-Dmaven.agent.dir=" + ArgumentGuard.path(dir),
                "-Dmaven.events.file=" + ArgumentGuard.path(dir.resolve(EVENTS)),
                "-Dmaven.events.log=" + ArgumentGuard.path(dir.resolve(LOG)));
    }

    /** {@code -pl} with the checked projects, plus {@code -am} when asked; nothing when no project is named. */
    static List<String> projects(List<String> projects, boolean alsoMake) {
        if (projects.isEmpty()) {
            return List.of();
        }
        List<String> args = new ArrayList<>(List.of("-pl", String.join(",", ArgumentGuard.projects(projects))));
        if (alsoMake) {
            args.add("-am");
        }
        return args;
    }

    /** One project with {@code -pl}, or the root project alone with {@code -N} when none is named. */
    static List<String> oneProject(String project) {
        return project.isEmpty() ? List.of("-N") : List.of("-pl", ArgumentGuard.project(project));
    }

    /**
     * One project and the reactor modules it depends on ({@code -pl -am}), so their POMs resolve from the reactor
     * rather than the local repository; Maven runs the project last. The root project alone when none is named.
     */
    static List<String> projectWithUpstream(String project) {
        return project.isEmpty() ? List.of("-N") : List.of("-pl", ArgumentGuard.project(project), "-am");
    }
}
