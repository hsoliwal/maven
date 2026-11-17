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

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.maven.plugins.clustering.AtomFrontEnd;
import org.apache.maven.plugins.clustering.AtomPlanes;
import org.apache.maven.plugins.clustering.lsp.DuplicateDiagnostics.Located;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.FileSystemWatcher;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.SaveOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.SetTraceParams;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * A language server that shows the plugin's duplicates in any LSP client (Eclipse LSP4E in the JDT, CDT, PyDev, WTP
 * and Rust editors, VS Code, Neovim, Emacs and others). It scans the workspace folders (or the root URI) with
 * {@link AtomPlanes#scan} and every discovered front end, after {@code initialized}, after each {@code didSave}, and
 * after a watched source file changes; then it publishes {@link DuplicateDiagnostics} per file, and an empty list for
 * a file that no longer has duplicates. Scans run one at a time on one thread; requests that arrive while a scan is
 * queued share it. It advertises only open/close/save document sync (no content changes: it reads files from disk).
 * {@code minTokens} comes from the {@code initializationOptions} ({@code {"minTokens": 20}}), else the system property
 * {@code clustering.minTokens}, else 12.
 */
public final class ClusteringLanguageServer implements LanguageServer, TextDocumentService, WorkspaceService,
        LanguageClientAware {
    static final int DEFAULT_MIN_TOKENS = 12;
    private static final System.Logger LOG = System.getLogger(ClusteringLanguageServer.class.getName());
    private static final String WATCHED_FILES = "workspace/didChangeWatchedFiles";

    private final Supplier<List<AtomFrontEnd>> frontEnds;
    private final ExecutorService scans = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "code-clustering-scan");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean queued = new AtomicBoolean();
    private final CompletableFuture<Void> exited = new CompletableFuture<>();
    /** The diagnostics last published per URI; used on the scan thread only. */
    private final Map<String, List<Diagnostic>> published = new HashMap<>();
    private volatile List<Path> roots = List.of();
    private volatile int minTokens = DEFAULT_MIN_TOKENS;
    private volatile boolean watchFiles;
    private volatile boolean shutdown;
    private volatile LanguageClient client;

    /** A server with the discovered front ends, then javac, then lexical. */
    public ClusteringLanguageServer() {
        this(AtomFrontEnd::all);
    }

    ClusteringLanguageServer(Supplier<List<AtomFrontEnd>> frontEnds) {
        this.frontEnds = frontEnds;
    }

    @Override public void connect(LanguageClient languageClient) {
        this.client = languageClient;
    }

    @Override public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        roots = roots(params);
        minTokens = minTokens(params.getInitializationOptions());
        watchFiles = canWatchFiles(params.getCapabilities());
        return CompletableFuture.completedFuture(new InitializeResult(capabilities(), new ServerInfo("code-clustering")));
    }

    /** Open, close and save notifications; no content changes, and no other feature. */
    static ServerCapabilities capabilities() {
        TextDocumentSyncOptions sync = new TextDocumentSyncOptions();
        sync.setOpenClose(true);
        sync.setChange(TextDocumentSyncKind.None);
        sync.setSave(new SaveOptions(false));
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(sync);
        return capabilities;
    }

    @Override public void initialized(InitializedParams params) {
        LanguageClient to = client;
        if (watchFiles && to != null) {
            FileSystemWatcher all = new FileSystemWatcher(Either.forLeft("**/*"));
            Registration watch = new Registration("code-clustering-watch", WATCHED_FILES,
                    new DidChangeWatchedFilesRegistrationOptions(List.of(all)));
            to.registerCapability(new RegistrationParams(List.of(watch))).exceptionally(failure -> {
                log(MessageType.Warning, "file watching not registered: " + failure);
                return null;
            });
        }
        requestScan();
    }

    @Override public CompletableFuture<Object> shutdown() {
        shutdown = true;
        scans.shutdown();
        return CompletableFuture.completedFuture(null);
    }

    @Override public void exit() {
        scans.shutdownNow();
        exited.complete(null);
    }

    /** Completes when the client sends {@code exit}. */
    CompletableFuture<Void> exited() {
        return exited;
    }

    /** 0 after {@code shutdown}, else 1, as the protocol asks of the process exit code. */
    int exitCode() {
        return shutdown ? 0 : 1;
    }

    @Override public void setTrace(SetTraceParams params) { }

    @Override public TextDocumentService getTextDocumentService() {
        return this;
    }

    @Override public WorkspaceService getWorkspaceService() {
        return this;
    }

    @Override public void didOpen(DidOpenTextDocumentParams params) { }

    @Override public void didChange(DidChangeTextDocumentParams params) { }

    @Override public void didClose(DidCloseTextDocumentParams params) { }

    @Override public void didSave(DidSaveTextDocumentParams params) {
        requestScan();
    }

    @Override public void didChangeConfiguration(DidChangeConfigurationParams params) { }

    /** Rescans when a changed file is one that a front end atomizes. */
    @Override public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        List<AtomFrontEnd> ends = frontEnds.get();
        if (params.getChanges().stream().map(change -> path(change.getUri())).flatMap(Optional::stream)
                .anyMatch(file -> ends.stream().anyMatch(f -> f.accepts(file)))) requestScan();
    }

    /** Queues a scan unless one is already queued; a running scan is followed by the queued one. */
    private void requestScan() {
        if (shutdown || !queued.compareAndSet(false, true)) return;
        try {
            scans.execute(() -> {
                queued.set(false);
                scan();
            });
        } catch (RejectedExecutionException closed) {
            queued.set(false);
        }
    }

    private void scan() {
        try {
            List<AtomFrontEnd> ends = frontEnds.get();
            List<Located> atoms = new ArrayList<>();
            for (Path root : roots) AtomPlanes.scan(List.of(root), ends).forEach(a -> atoms.add(new Located(root, a)));
            publish(DuplicateDiagnostics.of(atoms, minTokens));
        } catch (IOException | RuntimeException failure) {
            log(MessageType.Error, "code clustering scan failed: " + failure);
        }
    }

    /** Publishes the files whose diagnostics changed, including an empty list for each file that has none left. */
    private void publish(Map<String, List<Diagnostic>> now) {
        LanguageClient to = client;
        if (to == null) return;
        Set<String> uris = new TreeSet<>(published.keySet());
        uris.addAll(now.keySet());
        for (String uri : uris) {
            List<Diagnostic> diagnostics = now.getOrDefault(uri, List.of());
            if (!diagnostics.equals(published.get(uri))) to.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
        }
        published.clear();
        published.putAll(now);
    }

    private void log(MessageType type, String message) {
        LOG.log(type == MessageType.Error ? System.Logger.Level.ERROR : System.Logger.Level.WARNING, message);
        try {
            LanguageClient to = client;
            if (to != null) to.logMessage(new MessageParams(type, message));
        } catch (RuntimeException closed) {
            // the connection is gone; the message is in the server's own log
        }
    }

    /** The workspace folders, else the root URI or root path (deprecated in LSP 3.16, still sent by older clients). */
    @SuppressWarnings("deprecation")
    static List<Path> roots(InitializeParams params) {
        List<Path> roots = new ArrayList<>();
        if (params.getWorkspaceFolders() != null) params.getWorkspaceFolders().forEach(f -> path(f.getUri()).ifPresent(roots::add));
        if (roots.isEmpty() && params.getRootUri() != null) path(params.getRootUri()).ifPresent(roots::add);
        if (roots.isEmpty() && params.getRootPath() != null) roots.add(Path.of(params.getRootPath()));
        return roots.stream().map(p -> p.toAbsolutePath().normalize()).distinct().toList();
    }

    /** The local path of a {@code file:} URI. */
    static Optional<Path> path(String uri) {
        try {
            URI parsed = URI.create(uri);
            return "file".equalsIgnoreCase(parsed.getScheme()) ? Optional.of(Path.of(parsed)) : Optional.empty();
        } catch (IllegalArgumentException | FileSystemNotFoundException notLocal) {
            return Optional.empty();
        }
    }

    /** {@code minTokens} from the initialization options, else the system property, else the default. */
    static int minTokens(Object options) {
        String given = null;
        if (options instanceof JsonObject json && json.get("minTokens") instanceof JsonPrimitive value) given = value.getAsString();
        if (options instanceof Map<?, ?> map && map.get("minTokens") != null) given = String.valueOf(map.get("minTokens"));
        if (given == null) given = System.getProperty("clustering.minTokens");
        try {
            return given == null ? DEFAULT_MIN_TOKENS : (int) Double.parseDouble(given);
        } catch (NumberFormatException notANumber) {
            return DEFAULT_MIN_TOKENS;
        }
    }

    private static boolean canWatchFiles(ClientCapabilities capabilities) {
        return capabilities != null && capabilities.getWorkspace() != null
                && capabilities.getWorkspace().getDidChangeWatchedFiles() != null
                && Boolean.TRUE.equals(capabilities.getWorkspace().getDidChangeWatchedFiles().getDynamicRegistration());
    }
}
