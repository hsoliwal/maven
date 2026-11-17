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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The build and test tools. Each call gets a numbered run directory; all client values are checked before the
 * directory is created, so a refused call leaves nothing behind.
 */
final class BuildTools {

    static final int MAX_TIMEOUT = 86_400;

    private final ServerContext context;

    BuildTools(ServerContext context) {
        this.context = context;
    }

    /** build(goals, projects, alsoMake, properties, offline, timeoutSeconds). */
    JsonNode build(ToolArgs args) throws IOException {
        List<String> goals = ArgumentGuard.goals(args.strings("goals"));
        List<String> mavenArgs = new ArrayList<>(MavenCommands.projects(args.strings("projects"),
                args.flag("alsoMake", true)));
        mavenArgs.addAll(ArgumentGuard.properties(args.properties("properties")));
        mavenArgs.addAll(goals);
        return execute("build", args, mavenArgs);
    }

    /** test(selection, project, it, offline, timeoutSeconds): one test class or method, in one module or all. */
    JsonNode test(ToolArgs args) throws IOException {
        String selection = ArgumentGuard.selection(args.required("selection"));
        List<String> project = args.string("project").map(List::of).orElse(List.of());
        List<String> mavenArgs = new ArrayList<>(MavenCommands.projects(project, true));
        mavenArgs.addAll(args.flag("it", false) ? integrationTest(selection) : unitTest(selection));
        return execute("test", args, mavenArgs).put("selection", selection);
    }

    private static List<String> unitTest(String selection) {
        return List.of("-Dtest=" + selection, "-Dsurefire.failIfNoSpecifiedTests=false", "test");
    }

    /** Failsafe runs the selection; Surefire is given a selection that matches no class, so unit tests are skipped. */
    private static List<String> integrationTest(String selection) {
        return List.of("-Dit.test=" + selection, "-Dfailsafe.failIfNoSpecifiedTests=false",
                "-Dtest=NoUnitTestSelected", "-Dsurefire.failIfNoSpecifiedTests=false", "verify");
    }

    private ObjectNode execute(String tool, ToolArgs args, List<String> goalArgs) throws IOException {
        int timeout = args.number("timeoutSeconds", context.settings().timeoutSeconds(), 10, MAX_TIMEOUT);
        boolean offline = args.flag("offline", false);
        return context.exclusive(() -> {
            Path dir = context.store().allocate();
            List<String> all = new ArrayList<>(MavenCommands.base(dir, context.settings(), offline));
            all.addAll(MavenCommands.agent(dir));
            all.addAll(goalArgs);
            MavenLauncher.MavenRun run = context.launcher().run(dir, all, timeout);
            RunStore.record(dir, record(tool, run));
            return RunResults.summary(dir, run).put("command", "mvn " + String.join(" ", goalArgs));
        });
    }

    private static ObjectNode record(String tool, MavenLauncher.MavenRun run) {
        ObjectNode record = CompactJson.object()
                .put("tool", tool)
                .put("outcome", RunResults.outcome(run))
                .put("exitCode", run.exitCode())
                .put("durationMs", run.durationMs())
                .put("launcher", run.launcher().script().toString())
                .put("javaHome", run.javaHome().toString());
        run.args().forEach(record.putArray("args")::add);
        return record;
    }
}
