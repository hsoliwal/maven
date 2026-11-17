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
package org.apache.maven.plugins.polyglot.adapters;

import io.synexia.maven.polyglot.M3AstStatus;
import io.synexia.maven.polyglot.M3Report;
import io.synexia.maven.polyglot.M3ReportFormat;
import io.synexia.maven.polyglot.M3ToolCapability;
import io.synexia.maven.polyglot.M3ToolProvider;
import io.synexia.maven.polyglot.TaskManifest;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@code @m3:lsp:linter:<language>}: diagnostics a language server publishes for the given files, collected by
 * {@code LspDiagnosticsMain} in its own JVM (Eclipse LSP4J). Arguments:
 * {@code <out diagnostics.jsonl> <out findings.sarif> <deadline-ms> jar=<lsp4j jar>... server=<executable>
 * [server-arg=<arg>]... <file>...}. Each file is opened once at version 1; the run is COMPLETE when every file got a
 * diagnostics notification for that version before the deadline, otherwise PARTIAL (exit 4, task fails, reports kept).
 * The server parses on its own: one-AST status {@link M3AstStatus#LANGUAGE_SERVER}.
 */
public final class LspLinterProvider implements M3ToolProvider {
    static final String MAIN = "org.apache.maven.plugins.polyglot.adapters.LspDiagnosticsMain";
    static final String USAGE = "<out diagnostics.jsonl> <out findings.sarif> <deadline-ms> jar=<lsp4j jar>..."
            + " server=<executable> [server-arg=<arg>]... <file>...";
    /** Task language to LSP language identifier: clangd for C, C++ and Objective-C. */
    static final Map<String, String> LANGUAGE_IDS = Map.of("c", "c", "cpp", "cpp", "objc", "objective-c");
    private static final List<String> OPTIONS = List.of("jar", "server", "server-arg");
    private static final System.Logger LOG = System.getLogger(LspLinterProvider.class.getName());

    @Override public String id() {
        return "lsp";
    }

    @Override public Set<M3ToolCapability> capabilities() {
        return Set.of(M3ToolCapability.LINTER);
    }

    @Override public Set<String> languages() {
        return LANGUAGE_IDS.keySet();
    }

    @Override public List<String> command(M3ToolCapability capability, String language, TaskManifest.Task task,
            Path workspace) throws IOException {
        List<String> all = ToolArgs.count(task, 6, 1024, USAGE);
        List<String> args = ToolArgs.positional(all, OPTIONS);
        String server = ToolArgs.option(all, "server").orElseThrow(() -> new IOException("usage: " + USAGE));
        if (args.size() < 4 || deadline(args.get(2)) < 1) throw new IOException("usage: " + USAGE);
        List<String> argv = new ArrayList<>(List.of(ToolArgs.java(), "-cp", classpath(all, task, workspace), MAIN,
                workspace.resolve(task.workingDir()).toAbsolutePath().normalize().toString(),
                ToolArgs.absolute(workspace, task, args.get(0)), ToolArgs.absolute(workspace, task, args.get(1)),
                args.get(2), LANGUAGE_IDS.get(language), server));
        all.stream().filter(a -> a.startsWith("server-arg=")).map(a -> a.substring("server-arg=".length()))
                .forEach(argv::add);
        argv.add("--");
        args.subList(3, args.size()).forEach(file -> argv.add(ToolArgs.absolute(workspace, task, file)));
        LOG.log(System.Logger.Level.DEBUG, "planned LSP linter: {0}", argv);
        return ToolArgs.launch(argv);
    }

    private static long deadline(String text) throws IOException {
        try {
            long value = Long.parseLong(text);
            return value <= 600_000 ? value : -1;
        } catch (NumberFormatException e) {
            throw new IOException("the deadline must be milliseconds (1..600000): " + text, e);
        }
    }

    /** The adapter's own class path entry first, then the LSP4J jars named by the task. */
    private static String classpath(List<String> all, TaskManifest.Task task, Path workspace) throws IOException {
        List<String> jars = all.stream().filter(a -> a.startsWith("jar=")).map(a -> a.substring(4))
                .map(jar -> ToolArgs.absolute(workspace, task, jar)).toList();
        if (jars.isEmpty()) throw new IOException("the LSP4J jars are required (jar=...): " + USAGE);
        return String.join(File.pathSeparator,
                Stream.concat(Stream.of(ToolArgs.home(LspLinterProvider.class)), jars.stream()).toList());
    }

    @Override public List<M3Report> reports(M3ToolCapability capability, String language, TaskManifest.Task task) {
        List<String> args = ToolArgs.positional(task.arguments(), OPTIONS);
        return List.of(new M3Report(ToolArgs.declared(task, args.get(0)), M3ReportFormat.BUILD_EVENTS_JSONL_V1),
                new M3Report(ToolArgs.declared(task, args.get(1)), M3ReportFormat.SARIF_2_1_0));
    }

    @Override public M3AstStatus astStatus(M3ToolCapability capability, String language) {
        return M3AstStatus.LANGUAGE_SERVER;
    }
}
