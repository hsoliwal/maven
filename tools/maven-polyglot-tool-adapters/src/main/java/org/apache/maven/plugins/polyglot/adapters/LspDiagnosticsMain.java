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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

/**
 * The linter process: starts the language server (no shell, its stderr discarded), opens every file once at version
 * 1, keeps the diagnostics the server publishes for that version and writes them as SARIF 2.1.0 and build-events
 * lines. Usage: {@code LspDiagnosticsMain <root> <out.jsonl> <out.sarif> <deadline-ms> <language-id> <server>
 * [server-arg]... -- <file>...}. Exit 0 when every file was answered before the deadline, 4 otherwise (the reports
 * then name the files without an answer and mark the invocation unsuccessful).
 * <p>Placed here until code-clustering-lsp's session keeps diagnostics (its client discards them); the argv contract
 * of {@link LspLinterProvider} stays when the collector moves there.
 */
public final class LspDiagnosticsMain {
    static final int VERSION = 1;
    static final int PARTIAL_EXIT = 4;
    private static final Duration SHUTDOWN = Duration.ofSeconds(5);
    private static final System.Logger LOG = System.getLogger(LspDiagnosticsMain.class.getName());

    private LspDiagnosticsMain() { }

    public static void main(String[] args) throws IOException, InterruptedException {
        System.exit(run(List.of(args)));
    }

    static int run(List<String> args) throws IOException, InterruptedException {
        int split = args.indexOf("--");
        if (split < 6 || split == args.size() - 1) {
            throw new IllegalArgumentException("usage: LspDiagnosticsMain <root> <out.jsonl> <out.sarif> <deadline-ms>"
                    + " <language-id> <server> [server-arg]... -- <file>...");
        }
        Path root = Path.of(args.get(0)).toAbsolutePath().normalize();
        List<Path> files = args.subList(split + 1, args.size()).stream()
                .map(f -> Path.of(f).toAbsolutePath().normalize()).toList();
        Collected run = collect(args.subList(5, split), root, args.get(4), files, Duration.ofMillis(Long.parseLong(args.get(3))));
        FindingReports.write(Path.of(args.get(1)), Path.of(args.get(2)), run.tool(), root, run.findings(root), run.missing());
        LOG.log(System.Logger.Level.INFO, "LSP linter: files={0}, answered={1}", files.size(), run.answers().size());
        return run.missing().isEmpty() ? 0 : PARTIAL_EXIT;
    }

    /** What the server said: its name and version, and the diagnostics per answered file. */
    record Collected(FindingReports.Tool tool, Map<Path, List<Diagnostic>> answers, List<Path> files) {
        List<String> missing() {
            return files.stream().filter(f -> !answers.containsKey(f))
                    .map(f -> "no diagnostics for version " + VERSION + " before the deadline: " + f).toList();
        }

        List<Finding> findings(Path root) {
            return answers.entrySet().stream().flatMap(e -> e.getValue().stream().map(d -> finding(root, e.getKey(), d)))
                    .toList();
        }
    }

    static Collected collect(List<String> server, Path root, String languageId, List<Path> files, Duration deadline)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(server).directory(root.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        ExecutorService executor = Executors.newCachedThreadPool(LspDiagnosticsMain::daemon);
        try {
            DiagnosticsClient client = new DiagnosticsClient(files);
            Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(client, process.getInputStream(),
                    process.getOutputStream(), executor, null);
            launcher.startListening();
            return session(launcher.getRemoteProxy(), client, new Opened(root, languageId, files, server), deadline);
        } finally {
            executor.shutdownNow();
            stop(process);
        }
    }

    /** One session's fixed inputs. */
    private record Opened(Path root, String languageId, List<Path> files, List<String> server) { }

    private static Collected session(LanguageServer server, DiagnosticsClient client, Opened opened, Duration deadline)
            throws IOException, InterruptedException {
        long end = System.nanoTime() + deadline.toNanos();
        InitializeResult init = await(server.initialize(initializeParams(opened.root())), end);
        server.initialized(new InitializedParams());
        opened.files().forEach(file -> open(server, opened.languageId(), file));
        client.await(end);
        Collected collected = new Collected(tool(init, opened.server()), client.snapshot(), opened.files());
        shutdown(server);
        return collected;
    }

    private static void open(LanguageServer server, String languageId, Path file) {
        try {
            server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(
                    file.toUri().toString(), languageId, VERSION, Files.readString(file, StandardCharsets.UTF_8))));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** Workspace folder and root URI, diagnostics with document versions, and an empty workspace object. */
    @SuppressWarnings("deprecation")
    static InitializeParams initializeParams(Path root) {
        InitializeParams params = new InitializeParams();
        params.setProcessId((int) ProcessHandle.current().pid());
        params.setRootUri(root.toUri().toString());
        params.setWorkspaceFolders(List.of(new WorkspaceFolder(root.toUri().toString(), String.valueOf(root.getFileName()))));
        PublishDiagnosticsCapabilities diagnostics = new PublishDiagnosticsCapabilities();
        diagnostics.setVersionSupport(true);
        TextDocumentClientCapabilities text = new TextDocumentClientCapabilities();
        text.setPublishDiagnostics(diagnostics);
        ClientCapabilities capabilities = new ClientCapabilities();
        capabilities.setTextDocument(text);
        capabilities.setWorkspace(new WorkspaceClientCapabilities());
        params.setCapabilities(capabilities);
        return params;
    }

    private static FindingReports.Tool tool(InitializeResult init, List<String> server) {
        String fallback = Path.of(server.get(0)).getFileName().toString().replaceFirst("(?i)\\.exe$", "");
        Optional<org.eclipse.lsp4j.ServerInfo> info = Optional.ofNullable(init).map(InitializeResult::getServerInfo);
        return new FindingReports.Tool(info.map(org.eclipse.lsp4j.ServerInfo::getName).orElse(fallback),
                info.map(org.eclipse.lsp4j.ServerInfo::getVersion).orElse("unknown"), "", "linter");
    }

    /** 1-based line and column (UTF-16 code units, as SARIF counts by default); rule = code, else source. */
    static Finding finding(Path root, Path file, Diagnostic d) {
        Optional<Position> start = Optional.ofNullable(d.getRange()).map(r -> r.getStart());
        String rule = d.getCode() != null ? (d.getCode().isLeft() ? d.getCode().getLeft() : String.valueOf(d.getCode().getRight()))
                : Optional.ofNullable(d.getSource()).orElse("lsp");
        return new Finding(FindingReports.relative(root, file), start.map(p -> p.getLine() + 1L).orElse(0L),
                start.map(p -> p.getCharacter() + 1L).orElse(0L), level(d.getSeverity()), rule, d.getMessage());
    }

    static Finding.Level level(DiagnosticSeverity severity) {
        return severity == DiagnosticSeverity.Error ? Finding.Level.ERROR
                : severity == DiagnosticSeverity.Warning || severity == null ? Finding.Level.WARNING : Finding.Level.NOTE;
    }

    private static <T> T await(CompletableFuture<T> future, long end) throws IOException, InterruptedException {
        try {
            return future.get(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (ExecutionException | TimeoutException e) {
            future.cancel(true);
            throw new IOException("language server request failed: " + e, e);
        }
    }

    private static void shutdown(LanguageServer server) throws InterruptedException {
        try {
            server.shutdown().get(SHUTDOWN.toMillis(), TimeUnit.MILLISECONDS);
            server.exit();
        } catch (ExecutionException | TimeoutException | RuntimeException ignored) {
            // The process is ended by the caller either way; the diagnostics are already kept.
        }
    }

    private static void stop(Process process) throws InterruptedException {
        if (!process.waitFor(SHUTDOWN.toMillis(), TimeUnit.MILLISECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private static Thread daemon(Runnable task) {
        Thread thread = new Thread(task, "lsp-diagnostics");
        thread.setDaemon(true);
        return thread;
    }

    /** Keeps the latest diagnostics per opened file for version 1 (or unversioned); counts each file once. */
    static final class DiagnosticsClient implements LanguageClient {
        private final Map<Path, List<Diagnostic>> answers = new ConcurrentHashMap<>();
        private final Set<Path> expected;
        private final CountDownLatch pending;

        DiagnosticsClient(List<Path> files) {
            expected = Set.copyOf(files);
            pending = new CountDownLatch(expected.size());
        }

        @Override public void publishDiagnostics(PublishDiagnosticsParams params) {
            boolean current = params.getVersion() == null || params.getVersion() == VERSION;
            Optional<Path> file = path(params.getUri()).filter(expected::contains).filter(f -> current);
            file.ifPresent(f -> {
                if (answers.put(f, List.copyOf(params.getDiagnostics())) == null) pending.countDown();
            });
        }

        void await(long end) throws InterruptedException {
            pending.await(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS);
        }

        Map<Path, List<Diagnostic>> snapshot() {
            return Map.copyOf(answers);
        }

        private static Optional<Path> path(String uri) {
            try {
                return Optional.of(Path.of(new URI(uri)).toAbsolutePath().normalize());
            } catch (java.net.URISyntaxException | RuntimeException notAFile) {
                return Optional.empty();
            }
        }

        /** No settings: one {@code null} per item asked for, as the protocol allows. */
        @Override public CompletableFuture<List<Object>> configuration(ConfigurationParams params) {
            return CompletableFuture.completedFuture(new ArrayList<>(Collections.nCopies(params.getItems().size(), null)));
        }

        @Override public void telemetryEvent(Object object) { }

        @Override public void showMessage(MessageParams message) { }

        @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams request) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public void logMessage(MessageParams message) { }
    }
}
