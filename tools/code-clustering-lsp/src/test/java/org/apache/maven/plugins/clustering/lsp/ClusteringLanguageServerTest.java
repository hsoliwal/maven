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
package org.apache.maven.plugins.clustering.lsp;

import com.google.gson.JsonParser;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeWatchedFilesCapabilities;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageServer;

/**
 * The code-clustering language server over a loopback socket, driven by a {@link RecordingClient}. The workspace has
 * two Java files (an exact copy, renamed copies) and a JavaScript file (a renamed copy). The test checks the advertised
 * capabilities, the file-watcher registration, the published codes, lines and messages, then edits files and checks
 * that {@code didSave} and {@code didChangeWatchedFiles} update the diagnostics and clear those of a file without
 * duplicates; finally that {@code shutdown} and {@code exit} give exit code 0 and close the connection. Every wait is
 * bounded, and all threads are daemons. Argument: a scratch directory.
 */
public final class ClusteringLanguageServerTest {
    private static final String SUM = String.join("\n", "    int sum(int[] values) {", "        int total = 0;",
            "        for (int v : values) {", "            total += v;", "        }", "        return total;", "    }");
    private static final String A = String.join("\n", "package p;", "", "class A {", SUM, "",
            "    int max(int[] values) {", "        int best = Integer.MIN_VALUE;", "        for (int v : values) {",
            "            if (v > best) best = v;", "        }", "        return best;", "    }", "}", "");
    private static final String B = String.join("\n", "package q;", "", "class B {", SUM, "",
            "    int largest(int[] xs) {", "        int top = Integer.MIN_VALUE;", "        for (int x : xs) {",
            "            if (x > top) top = x;", "        }", "        return top;", "    }", "",
            "    int sumAll(int[] ns) {", "        int acc = 0;", "        for (int n : ns) {", "            acc += n;",
            "        }", "        return acc;", "    }", "}", "");
    private static final String A_EDITED = String.join("\n", "package p;", "", "class A {",
            "    int product(int[] values) {", "        int total = 1;", "        for (int v : values) {",
            "            total *= v;", "        }", "        return total;", "    }", "}", "");
    private static final String JS = String.join("\n", "function sum(values) {", "  let total = 0;",
            "  for (const v of values) { total += v; }", "  return total;", "}", "function add(xs) {", "  let acc = 0;",
            "  for (const x of xs) { acc += x; }", "  return acc;", "}", "");
    private static final String JS_EDITED = JS.replace("let acc = 0", "let acc = 1").replace("acc += x", "acc *= x");
    private static final Duration WAIT = Duration.ofSeconds(60);
    private static int failures;

    private ClusteringLanguageServerTest() { }

    public static void main(String[] args) throws Exception {
        Path ws = Path.of(args.length > 0 ? args[0] : "target/language-server-test").toAbsolutePath().normalize()
                .resolve("workspace");
        write(ws, "p/A.java", A);
        write(ws, "q/B.java", B);
        write(ws, "web/c.js", JS);
        options();
        ExecutorService threads = ClusteringServerMain.daemonThreads();
        ClusteringLanguageServer server = new ClusteringLanguageServer();
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            threads.submit(() -> {
                ClusteringServerMain.accept(socket, server, threads);
                return null;
            });
            try (Socket connection = new Socket(InetAddress.getLoopbackAddress(), socket.getLocalPort())) {
                RecordingClient client = new RecordingClient();
                Launcher<LanguageServer> launcher = new LSPLauncher.Builder<LanguageServer>().setLocalService(client)
                        .setRemoteInterface(LanguageServer.class).setInput(connection.getInputStream())
                        .setOutput(connection.getOutputStream()).setExecutorService(threads).create();
                Future<Void> listening = launcher.startListening();
                session(launcher.getRemoteProxy(), client, ws);
                server.exited().get(WAIT.toSeconds(), TimeUnit.SECONDS);
                check("exit after shutdown: code " + server.exitCode(), server.exitCode() == 0);
                listening.get(WAIT.toSeconds(), TimeUnit.SECONDS);
                check("the server closed the connection after exit", listening.isDone());
            }
        } finally {
            threads.shutdownNow();
        }
        if (failures > 0) throw new AssertionError(failures + " language server checks failed");
        System.err.println("language server: all checks passed");
    }

    private static void session(LanguageServer remote, RecordingClient client, Path ws) throws Exception {
        InitializeResult init = remote.initialize(initializeParams(ws)).get(WAIT.toSeconds(), TimeUnit.SECONDS);
        capabilities(init.getCapabilities());
        remote.initialized(new InitializedParams());
        check("file watcher registered", client.await(c -> c.registered().contains("workspace/didChangeWatchedFiles"), WAIT));
        String a = uri(ws, "p/A.java");
        String b = uri(ws, "q/B.java");
        String js = uri(ws, "web/c.js");
        expect(client, "initial scan", Map.of(
                a, "EXACT 3-9 Same text as q/B.java#B.sum(int[])"
                        + " | LOGIC 11-17 Same logic as q/B.java#B.largest(int[])",
                b, "EXACT 3-9 Same text as p/A.java#A.sum(int[])"
                        + " | LOGIC 11-17 Same logic as p/A.java#A.max(int[])"
                        + " | LOGIC 19-25 Same logic as p/A.java#A.sum(int[]) and 1 more",
                js, "LOGIC 0-4 Same logic as web/c.js#add(1) | LOGIC 5-9 Same logic as web/c.js#sum(1)"));
        details(client.latest(a));
        write(ws, "p/A.java", A_EDITED);
        remote.getTextDocumentService().didSave(new DidSaveTextDocumentParams(new TextDocumentIdentifier(a)));
        expect(client, "after didSave: A.java cleared, B.java updated", Map.of(a, "",
                b, "LOGIC 3-9 Same logic as q/B.java#B.sumAll(int[]) | LOGIC 19-25 Same logic as q/B.java#B.sum(int[])"));
        write(ws, "web/c.js", JS_EDITED);
        remote.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(
                List.of(new FileEvent(js, FileChangeType.Changed))));
        expect(client, "after didChangeWatchedFiles: c.js cleared", Map.of(js, ""));
        remote.shutdown().get(WAIT.toSeconds(), TimeUnit.SECONDS);
        remote.exit();
    }

    private static InitializeParams initializeParams(Path ws) {
        InitializeParams params = new InitializeParams();
        params.setWorkspaceFolders(List.of(new WorkspaceFolder(ws.toUri().toString(), "workspace")));
        params.setInitializationOptions(Map.of("minTokens", 12));
        WorkspaceClientCapabilities workspace = new WorkspaceClientCapabilities();
        workspace.setDidChangeWatchedFiles(new DidChangeWatchedFilesCapabilities(true));
        ClientCapabilities capabilities = new ClientCapabilities();
        capabilities.setWorkspace(workspace);
        params.setCapabilities(capabilities);
        return params;
    }

    /** Document sync for open, close and save only, and none of the features the server does not have. */
    private static void capabilities(ServerCapabilities capabilities) {
        TextDocumentSyncOptions sync = capabilities.getTextDocumentSync().getRight();
        check("sync: open/close, no changes, save", sync != null && Boolean.TRUE.equals(sync.getOpenClose())
                && sync.getChange() == TextDocumentSyncKind.None && sync.getSave() != null);
        check("no other features advertised", capabilities.getHoverProvider() == null
                && capabilities.getCompletionProvider() == null && capabilities.getDefinitionProvider() == null
                && capabilities.getDocumentSymbolProvider() == null && capabilities.getCodeActionProvider() == null
                && capabilities.getDiagnosticProvider() == null && capabilities.getWorkspace() == null);
    }

    /** Severity, source and the character range of A.sum: from its first non-blank character to the end of "    }". */
    private static void details(List<Diagnostic> diagnostics) {
        boolean info = diagnostics != null && diagnostics.stream().allMatch(d -> d.getSeverity() == DiagnosticSeverity.Information
                && "code-clustering".equals(d.getSource()));
        check("severity Information, source code-clustering", info);
        Diagnostic sum = diagnostics == null ? null : diagnostics.stream().filter(d -> d.getRange().getStart().getLine() == 3)
                .findFirst().orElse(null);
        check("range characters 4..5", sum != null && sum.getRange().getStart().getCharacter() == 4
                && sum.getRange().getEnd().getCharacter() == 5);
    }

    /** Waits until each URI's diagnostics read as expected ("" = an empty list was published). */
    private static void expect(RecordingClient client, String name, Map<String, String> expected) throws InterruptedException {
        boolean ok = client.await(c -> expected.entrySet().stream()
                .allMatch(e -> e.getValue().equals(summary(c.latest(e.getKey())))), WAIT);
        check(name, ok);
        if (!ok) expected.keySet().forEach(uri -> System.err.println("  " + uri + ": " + summary(client.latest(uri))));
    }

    private static String summary(List<Diagnostic> diagnostics) {
        if (diagnostics == null) return "(nothing published)";
        return diagnostics.stream().sorted(Comparator.comparingInt(d -> d.getRange().getStart().getLine()))
                .map(d -> d.getCode().getLeft() + " " + d.getRange().getStart().getLine() + "-"
                        + d.getRange().getEnd().getLine() + " " + d.getMessage())
                .collect(Collectors.joining(" | "));
    }

    /** minTokens from the initialization options, the system property and the default; the --port argument. */
    private static void options() {
        check("minTokens from options", ClusteringLanguageServer.minTokens(JsonParser.parseString("{\"minTokens\": 40}")) == 40);
        check("minTokens default", ClusteringLanguageServer.minTokens(null) == ClusteringLanguageServer.DEFAULT_MIN_TOKENS);
        System.setProperty("clustering.minTokens", "20");
        check("minTokens from the system property", ClusteringLanguageServer.minTokens(null) == 20);
        System.clearProperty("clustering.minTokens");
        check("--port argument", ClusteringServerMain.port(new String[] {"--port", "5007"}) == 5007
                && ClusteringServerMain.port(new String[] {"--port=5008"}) == 5008
                && ClusteringServerMain.port(new String[] {"--stdio"}) == -1);
    }

    private static String uri(Path ws, String path) {
        return ws.resolve(path).toUri().toString();
    }

    private static void write(Path ws, String path, String text) throws Exception {
        Path file = ws.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.err.println((ok ? "PASS " : "FAIL ") + name);
    }
}
