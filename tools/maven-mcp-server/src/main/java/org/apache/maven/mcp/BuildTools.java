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
import org.apache.maven.agent.events.MavenCommands;

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
        boolean cmd = context.launcher().locate().cmd();
        List<String> goals = ArgumentGuard.goals(args.strings("goals"));
        List<String> mavenArgs = new ArrayList<>(ServerArguments.projects(args.strings("projects"),
                args.flag("alsoMake", true)));
        mavenArgs.addAll(ArgumentGuard.properties(args.properties("properties"), cmd));
        mavenArgs.addAll(goals);
        return execute("build", args, mavenArgs);
    }

    /**
     * test(selection, project, it, offline, timeoutSeconds): one test class or method, in one module or all. The
     * arguments come from the build events extension's {@code MavenCommands}, the one owner of their spelling:
     * Surefire with {@code -Dtest}, or Failsafe with {@code -Dit.test} and no unit test.
     */
    JsonNode test(ToolArgs args) throws IOException {
        String selection = ArgumentGuard.selection(args.required("selection"), context.launcher().locate().cmd());
        List<String> project = args.string("project").map(List::of).orElse(List.of());
        List<String> mavenArgs = new ArrayList<>(ServerArguments.projects(project, true));
        mavenArgs.addAll(args.flag("it", false) ? MavenCommands.integrationTestArgs(selection)
                : MavenCommands.testArgs(selection));
        return execute("test", args, mavenArgs).put("selection", selection);
    }

    private ObjectNode execute(String tool, ToolArgs args, List<String> goalArgs) throws IOException {
        int timeout = args.number("timeoutSeconds", context.settings().timeoutSeconds(), 10, MAX_TIMEOUT);
        boolean offline = args.flag("offline", false);
        return context.exclusive(() -> {
            Path dir = context.store().allocate();
            List<String> all = new ArrayList<>(ServerArguments.base(dir, context.settings(), offline));
            all.addAll(ServerArguments.agent(dir));
            all.addAll(goalArgs);
            MavenLauncher.MavenRun run = context.launcher().run(dir, all, timeout);
            RunStore.record(dir, record(tool, run));
            return RunResults.summary(dir, run).put("command", MavenCommands.line("mvn", goalArgs));
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
