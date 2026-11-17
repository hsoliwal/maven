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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolCapabilities;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

/**
 * One connection to a language server: {@code initialize} with hierarchical document symbols, then per file
 * {@code didOpen}, {@code documentSymbol}, {@code didClose}; {@link #close()} sends {@code shutdown} and
 * {@code exit} and ends the server process. Every request waits at most the timeout.
 */
final class LspSession implements AutoCloseable {
    private final Process process;
    private final LanguageServer server;
    private final Future<Void> listening;
    private final Duration timeout;
    /** Daemon threads: a session never keeps the JVM (or Maven) alive. */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "lsp-session");
        thread.setDaemon(true);
        return thread;
    });

    private LspSession(Process process, InputStream in, OutputStream out, Path root, Duration timeout) throws IOException {
        this.process = process;
        this.timeout = timeout;
        Launcher<LanguageServer> launcher = new LSPLauncher.Builder<LanguageServer>().setLocalService(new QuietClient())
                .setRemoteInterface(LanguageServer.class).setInput(in).setOutput(out).setExecutorService(executor).create();
        this.server = launcher.getRemoteProxy();
        this.listening = launcher.startListening();
        InitializeParams params = initializeParams(root);
        DocumentSymbolCapabilities symbols = new DocumentSymbolCapabilities();
        symbols.setHierarchicalDocumentSymbolSupport(true);
        TextDocumentClientCapabilities text = new TextDocumentClientCapabilities();
        text.setDocumentSymbol(symbols);
        ClientCapabilities capabilities = new ClientCapabilities();
        capabilities.setTextDocument(text);
        // An empty workspace object, not none: LemMinX 0.31 fails every request when it is missing.
        capabilities.setWorkspace(new WorkspaceClientCapabilities());
        params.setCapabilities(capabilities);
        await(server.initialize(params));
        server.initialized(new InitializedParams());
    }

    /** Workspace folders, plus the root URI (deprecated in LSP 3.16) for servers that predate workspace folders. */
    @SuppressWarnings("deprecation")
    private static InitializeParams initializeParams(Path root) {
        InitializeParams params = new InitializeParams();
        params.setProcessId((int) ProcessHandle.current().pid());
        String uri = root.toUri().toString();
        params.setRootUri(uri);
        params.setWorkspaceFolders(List.of(new WorkspaceFolder(uri, String.valueOf(root.getFileName()))));
        return params;
    }

    /** Starts the server process (no shell) in the root directory; its stderr is discarded. */
    static LspSession start(List<String> command, Path root, Duration timeout) throws IOException {
        Process process = new ProcessBuilder(command).directory(root.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            return new LspSession(process, process.getInputStream(), process.getOutputStream(), root, timeout);
        } catch (IOException | RuntimeException e) {
            process.destroyForcibly();
            throw e;
        }
    }

    /** Connects to a server over the given streams, for example a socket. */
    static LspSession connect(InputStream in, OutputStream out, Path root, Duration timeout) throws IOException {
        return new LspSession(null, in, out, root, timeout);
    }

    List<Either<SymbolInformation, DocumentSymbol>> symbols(Path file, String text, String languageId) throws IOException {
        String uri = file.toUri().toString();
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, languageId, 1, text)));
        try {
            List<Either<SymbolInformation, DocumentSymbol>> found = await(server.getTextDocumentService()
                    .documentSymbol(new DocumentSymbolParams(new TextDocumentIdentifier(uri))));
            return found == null ? List.of() : found;
        } finally {
            server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));
        }
    }

    private <T> T await(CompletableFuture<T> future) throws IOException {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for the language server", e);
        } catch (ExecutionException | TimeoutException e) {
            future.cancel(true);
            throw new IOException("language server request failed: " + e, e);
        }
    }

    @Override public void close() {
        try {
            await(server.shutdown());
            server.exit();
        } catch (IOException | RuntimeException ignored) {
            // the process is ended below either way
        }
        listening.cancel(true);
        executor.shutdownNow();
        if (process != null) {
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    /**
     * Eclipse JDT LS's own notifications, accepted and ignored so they are not logged as unsupported. Public, so the
     * JSON-RPC layer may call it reflectively.
     */
    public interface JdtNotifications {
        @JsonNotification("language/status")
        default void status(Object params) { }

        @JsonNotification("language/eventNotification")
        default void event(Object params) { }

        @JsonNotification("language/progressReport")
        default void progress(Object params) { }

        @JsonNotification("language/actionableNotification")
        default void actionable(Object params) { }
    }

    /** A client that answers server notifications and requests without user interaction. */
    private static final class QuietClient implements LanguageClient, JdtNotifications {
        /** No settings: one {@code null} per item asked for, as the protocol allows. */
        @Override public CompletableFuture<List<Object>> configuration(ConfigurationParams params) {
            return CompletableFuture.completedFuture(new ArrayList<>(Collections.nCopies(params.getItems().size(), null)));
        }

        @Override public void telemetryEvent(Object object) { }

        @Override public void publishDiagnostics(PublishDiagnosticsParams diagnostics) { }

        @Override public void showMessage(MessageParams message) { }

        @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams request) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public void logMessage(MessageParams message) { }
    }
}
