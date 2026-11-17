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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WindowClientCapabilities;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageServer;

/**
 * One language-server session of the linter process, bounded by the deadline end to end (graceful shutdown only in
 * the time left, then the process tree is ended). It never throws for a tool failure: a server that cannot start,
 * exits, refuses or does not answer {@code initialize}, or a launcher refused by {@link CmdLauncher}, gives a failed
 * outcome; files that are missing or not UTF-8, files without an answer, a server still busy at the deadline and a
 * publish after the snapshot give a partial one. Every outcome carries what was collected.
 */
final class LspSession {
    private static final long SHUTDOWN_MILLIS = 2000;
    private final LspDiagnosticsMain.Request request;
    private final List<String> notes = new ArrayList<>();
    private final long end;
    private FindingReports.Tool tool;

    /** Tool name and version, the findings, the notes of what was not analysed, and whether the invocation failed. */
    record Outcome(FindingReports.Tool tool, List<Finding> findings, List<String> notes, boolean failed) { }

    private LspSession(LspDiagnosticsMain.Request request) {
        this.request = request;
        this.end = System.nanoTime() + request.deadline().toNanos();
        String launcher = request.server().get(0);
        String name = launcher.substring(Math.max(launcher.lastIndexOf('/'), launcher.lastIndexOf('\\')) + 1)
                .replaceFirst("(?i)\\.exe$", "");
        this.tool = new FindingReports.Tool(name, "unknown", "", "linter");
    }

    static Outcome collect(LspDiagnosticsMain.Request request) throws InterruptedException {
        LspSession session = new LspSession(request);
        Map<Path, String> texts = session.readable();
        try {
            CmdLauncher.shellSafe(request.server());
            return texts.isEmpty() ? session.outcome(Map.of(), false) : session.start(texts);
        } catch (IOException | RuntimeException failure) {
            session.notes.add("the language server session failed: " + failure.getMessage());
            return session.outcome(Map.of(), true);
        }
    }

    /** Every file that can be read as UTF-8; the others are named as not analysed. */
    private Map<Path, String> readable() {
        Map<Path, String> texts = new LinkedHashMap<>();
        request.files().forEach(file -> read(file).ifPresentOrElse(text -> texts.put(file, text),
                () -> notes.add("not analysed (missing, unreadable or not UTF-8): " + file)));
        return texts;
    }

    private static Optional<String> read(Path file) {
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    private Outcome start(Map<Path, String> texts) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(request.server()).directory(request.root().toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        ExecutorService executor = Executors.newCachedThreadPool(LspSession::daemon);
        LspDiagnosticsClient client = new LspDiagnosticsClient(texts.keySet());
        try {
            Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(client, process.getInputStream(),
                    process.getOutputStream(), executor, null);
            launcher.startListening();
            return converse(launcher.getRemoteProxy(), client, process, texts);
        } finally {
            executor.shutdownNow();
            stop(process);
        }
    }

    private Outcome converse(LanguageServer server, LspDiagnosticsClient client, Process process, Map<Path, String> texts)
            throws InterruptedException {
        try {
            tool(await(server.initialize(initializeParams(request.root())), process, "initialize"));
            server.initialized(new InitializedParams());
            texts.forEach((file, text) -> server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                    new TextDocumentItem(file.toUri().toString(), request.languageId(), LspDiagnosticsMain.VERSION, text))));
            return finish(server, client, process, client.awaitQuiet(end, request.settle(), process));
        } catch (IOException | RuntimeException failure) {
            notes.add("the language server session failed: " + failure.getMessage());
            return outcome(client.snapshot(), true);
        }
    }

    /** Takes the snapshot, shuts the server down in the time left, and notes what makes the result partial. */
    private Outcome finish(LanguageServer server, LspDiagnosticsClient client, Process process,
            LspDiagnosticsClient.Quiet quiet) throws InterruptedException {
        Map<Path, List<Diagnostic>> snapshot = client.snapshot();
        int published = client.published();
        if (quiet == LspDiagnosticsClient.Quiet.EXITED) {
            notes.add("the language server exited (code " + process.exitValue() + ") before the run completed");
            return outcome(snapshot, true);
        }
        notes.addAll(quiet == LspDiagnosticsClient.Quiet.DEADLINE ? client.unfinished() : List.of());
        shutdown(server, process);
        if (client.published() > published) {
            notes.add("diagnostics were published after the snapshot; the reports may miss them");
        }
        return outcome(snapshot, false);
    }

    private Outcome outcome(Map<Path, List<Diagnostic>> answers, boolean failed) {
        List<Finding> findings = answers.entrySet().stream().flatMap(e -> e.getValue().stream()
                .map(d -> LspDiagnosticsMain.finding(request.root(), e.getKey(), d))).toList();
        return new Outcome(tool, findings, List.copyOf(notes), failed);
    }

    private void tool(InitializeResult init) {
        Optional<ServerInfo> info = Optional.ofNullable(init).map(InitializeResult::getServerInfo);
        tool = new FindingReports.Tool(info.map(ServerInfo::getName).orElse(tool.name()),
                info.map(ServerInfo::getVersion).orElse("unknown"), "", "linter");
    }

    /** The answer, unless the deadline passes or the server exits first. */
    private <T> T await(CompletableFuture<T> future, Process process, String what) throws IOException, InterruptedException {
        try {
            CompletableFuture.anyOf(future, process.onExit()).get(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (!future.isDone()) {
                throw new IOException("the language server exited (code " + process.exitValue() + ") during " + what);
            }
            return future.get();
        } catch (TimeoutException late) {
            future.cancel(true);
            throw new IOException("the language server did not answer " + what + " before the deadline", late);
        } catch (ExecutionException failure) {
            throw new IOException("the language server failed " + what + ": " + failure.getCause(), failure);
        }
    }

    /** Workspace folder and root URI, versioned diagnostics, work-done progress, an empty workspace object. */
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
        WindowClientCapabilities window = new WindowClientCapabilities();
        window.setWorkDoneProgress(true);
        params.setCapabilities(new ClientCapabilities(new WorkspaceClientCapabilities(), text, window, null));
        return params;
    }

    /** shutdown and exit only in the time the deadline leaves; {@link #stop} ends the process either way. */
    private void shutdown(LanguageServer server, Process process) throws InterruptedException {
        long left = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
        if (left <= 0 || !process.isAlive()) {
            return;
        }
        try {
            server.shutdown().get(Math.min(left, SHUTDOWN_MILLIS), TimeUnit.MILLISECONDS);
            server.exit();
        } catch (ExecutionException | TimeoutException | RuntimeException ignored) {
            // The process tree is ended by stop(); the diagnostics are already kept.
        }
    }

    private void stop(Process process) throws InterruptedException {
        long left = Math.max(0, Math.min(TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime()), SHUTDOWN_MILLIS));
        if (!process.waitFor(left, TimeUnit.MILLISECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
        }
    }

    private static Thread daemon(Runnable task) {
        Thread thread = new Thread(task, "lsp-diagnostics");
        thread.setDaemon(true);
        return thread;
    }
}
