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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The tools that describe the project: list_modules (from the POMs, no Maven run), effective_pom and
 * dependency_tree (maven-help-plugin and maven-dependency-plugin writing to a file in the query directory), and
 * describe_goal (the plugin descriptor in the local repository, else help:describe).
 */
final class ProjectTools {

    static final int TEXT_CHARS = 4000;

    private static final Set<String> SCOPES = Set.of("compile", "runtime", "test", "provided", "system");

    private final ServerContext context;

    ProjectTools(ServerContext context) {
        this.context = context;
    }

    JsonNode listModules(ToolArgs args) throws IOException {
        return ModuleScan.list(context.settings().root());
    }

    JsonNode effectivePom(ToolArgs args) throws IOException {
        List<String> goalArgs = MavenCommands.oneProject(args.string("project").orElse(""));
        return query(args, "effective-pom.xml", output -> plus(goalArgs,
                        MavenCommands.HELP_PLUGIN + ":effective-pom", "-Doutput=" + output),
                EffectivePom::summarize);
    }

    JsonNode dependencyTree(ToolArgs args) throws IOException {
        List<String> goalArgs = new ArrayList<>(MavenCommands.projectWithUpstream(args.string("project").orElse("")));
        Optional<String> scope = args.string("scope");
        scope.filter(value -> !SCOPES.contains(value)).ifPresent(value -> {
            throw new IllegalArgumentException("scope must be one of " + SCOPES);
        });
        scope.ifPresent(value -> goalArgs.add("-Dscope=" + value));
        int maxNodes = args.number("maxNodes", 200, 1, 5000);
        return query(args, "dependency-tree.json", output -> plus(goalArgs, MavenCommands.DEPENDENCY_PLUGIN + ":tree",
                "-DoutputType=json", "-DoutputFile=" + output), file -> DependencyTree.summarize(file, maxNodes));
    }

    JsonNode describeGoal(ToolArgs args) throws IOException {
        String spec = args.required("goal");
        Optional<PluginDescriptors.Resolved> resolved =
                PluginDescriptors.resolve(spec, context.settings().localRepo(), context.settings().root());
        if (resolved.isPresent()) {
            return PluginDescriptors.describe(resolved.get())
                    .put("source", "plugin.xml")
                    .put("jar", resolved.get().jar().toString());
        }
        String goal = spec.substring(spec.lastIndexOf(':') + 1);
        return query(args, "describe.txt", output -> List.of("-N", MavenCommands.HELP_PLUGIN + ":describe",
                "-Dplugin=" + PluginDescriptors.plugin(spec), "-Dgoal=" + goal, "-Ddetail=true", "-Doutput=" + output),
                file -> describeText(spec, file));
    }

    /** The goal arguments of a query, given the checked path of its output file. */
    @FunctionalInterface
    private interface QueryArgs {
        List<String> of(String output);
    }

    /** Reads a query's output file into the tool answer. */
    @FunctionalInterface
    private interface QueryReader {
        ObjectNode read(Path file) throws IOException;
    }

    /** Runs one Maven query that writes {@code fileName} in the query directory, then reads that file. */
    private ObjectNode query(ToolArgs args, String fileName, QueryArgs goalArgs, QueryReader reader)
            throws IOException {
        int timeout = args.number("timeoutSeconds", context.settings().timeoutSeconds(), 10, BuildTools.MAX_TIMEOUT);
        boolean offline = args.flag("offline", false);
        return context.exclusive(() -> {
            Path dir = context.store().query();
            Path output = dir.resolve(fileName);
            List<String> all = new ArrayList<>(MavenCommands.base(dir, context.settings(), offline));
            all.addAll(goalArgs.of(ArgumentGuard.path(output)));
            MavenLauncher.MavenRun run = context.launcher().run(dir, all, timeout);
            if (run.exitCode() != 0 || !Files.isRegularFile(output)) {
                throw new IllegalStateException(failure(run, dir));
            }
            return reader.read(output);
        });
    }

    private static String failure(MavenLauncher.MavenRun run, Path dir) throws IOException {
        String errors = StreamSupport.stream(RunResults.logErrors(dir).spliterator(), false)
                .map(JsonNode::asText)
                .collect(Collectors.joining(" | "));
        return "Maven " + RunResults.outcome(run) + " (exit " + run.exitCode() + "), log " + dir.resolve(
                MavenCommands.LOG) + (errors.isEmpty() ? "" : ": " + errors);
    }

    private static ObjectNode describeText(String spec, Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8).strip();
        ObjectNode out = CompactJson.object()
                .put("goal", spec)
                .put("source", "help:describe")
                .put("text", CompactJson.cap(text, TEXT_CHARS));
        return text.length() > TEXT_CHARS ? out.put("textOmittedChars", text.length() - TEXT_CHARS) : out;
    }

    private static List<String> plus(List<String> head, String... tail) {
        List<String> all = new ArrayList<>(head);
        all.addAll(List.of(tail));
        return all;
    }
}
