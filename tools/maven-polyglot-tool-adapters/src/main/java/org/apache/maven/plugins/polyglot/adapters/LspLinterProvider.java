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
 * [server-arg=<arg>]... [settle=<ms>] <file>...}. Each file is opened once at version 1. The run is COMPLETE when
 * every file was answered for that version, no work-done progress is open and nothing was published for
 * {@code settle} ms (default {@value #DEFAULT_SETTLE}), all before the deadline; otherwise PARTIAL (exit 4) or, when
 * the server could not start, exited or did not answer initialize, FAILED (exit 2). The task fails either way and
 * both reports are kept. After the session became quiet the collector watches one more {@code settle} period: a
 * publish then makes the run PARTIAL. A server whose next stage comes later than twice {@code settle} after its last
 * publish, without work-done progress, is not seen; give such a server a larger {@code settle}. A command interpreter
 * (cmd.exe, PowerShell, a POSIX shell, a script host) is never accepted as the server; a .cmd/.bat server is refused
 * when it or a {@code server-arg} holds a cmd.exe metacharacter ({@link CmdLauncher}). A {@code server-arg} may be
 * {@code --}. The server parses on its own: one-AST status {@link M3AstStatus#LANGUAGE_SERVER}.
 */
public final class LspLinterProvider implements M3ToolProvider {
    static final String MAIN = "org.apache.maven.plugins.polyglot.adapters.LspDiagnosticsMain";
    static final String DEFAULT_SETTLE = "2000";
    static final String USAGE = "<out diagnostics.jsonl> <out findings.sarif> <deadline-ms> jar=<lsp4j jar>..."
            + " server=<executable> [server-arg=<arg>]... [settle=<ms>] <file>...";
    /** Task language to LSP language identifier: clangd for C, C++ and Objective-C. */
    static final Map<String, String> LANGUAGE_IDS = Map.of("c", "c", "cpp", "cpp", "objc", "objective-c");
    private static final List<String> OPTIONS = List.of("jar", "server", "server-arg", "settle");
    private static final System.Logger LOG = System.getLogger(LspLinterProvider.class.getName());

    @Override public String id() {
        return "lsp";
    }

    @Override public Set<M3ToolCapability> capabilities() {
        return ToolArgs.kinds(() -> Set.of(M3ToolCapability.LINTER));
    }

    @Override public Set<String> languages() {
        return LANGUAGE_IDS.keySet();
    }

    @Override public List<String> command(M3ToolCapability capability, String language, TaskManifest.Task task,
            Path workspace) throws IOException {
        List<String> all = ToolArgs.count(task, 6, 1024, USAGE);
        List<String> args = ToolArgs.positional(all, OPTIONS);
        if (args.size() < 4) throw new IOException("usage: " + USAGE);
        long deadline = millis(args.get(2), "deadline", 1, 600_000);
        long settle = millis(ToolArgs.option(all, "settle").orElse(DEFAULT_SETTLE), "settle", 0, deadline);
        List<String> argv = new ArrayList<>(List.of(ToolArgs.java(), "-cp", classpath(all, task, workspace), MAIN,
                workspace.resolve(task.workingDir()).toAbsolutePath().normalize().toString(),
                ToolArgs.absolute(workspace, task, args.get(0)), ToolArgs.absolute(workspace, task, args.get(1)),
                Long.toString(deadline), Long.toString(settle), LANGUAGE_IDS.get(language)));
        return ToolArgs.launch(logged(files(argv, server(all), args.subList(3, args.size()), task, workspace)));
    }

    /** The planned head, the server command, "--" and the files as absolute paths. */
    private static List<String> files(List<String> argv, List<String> server, List<String> files, TaskManifest.Task task,
            Path workspace) {
        argv.addAll(server);
        argv.add("--");
        files.forEach(file -> argv.add(ToolArgs.absolute(workspace, task, file)));
        return argv;
    }

    /** The server command, checked as the launcher it is: the collector starts it, the engine never sees it. */
    private static List<String> server(List<String> all) throws IOException {
        String server = ToolArgs.option(all, "server").orElseThrow(() -> new IOException("usage: " + USAGE));
        return ToolArgs.nested(Stream.concat(Stream.of(server), all.stream().filter(a -> a.startsWith("server-arg="))
                .map(a -> a.substring("server-arg=".length()))).toList());
    }

    private static long millis(String text, String what, long min, long max) throws IOException {
        try {
            long value = Long.parseLong(text);
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException notANumber) {
            // refused below with the accepted range
        }
        throw new IOException("the " + what + " must be milliseconds (" + min + ".." + max + "): " + text);
    }

    private static List<String> logged(List<String> argv) {
        LOG.log(System.Logger.Level.DEBUG, "planned LSP linter: {0}", argv);
        return argv;
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
