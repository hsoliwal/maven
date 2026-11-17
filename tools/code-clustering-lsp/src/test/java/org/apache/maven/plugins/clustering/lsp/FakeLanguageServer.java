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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * A minimal language server for the tests. It reports {@code class X {...}} and {@code function f(...) {...}}
 * blocks as hierarchical document symbols, and {@code m(...) {...}} blocks inside a class as methods, each with
 * its full range. The symbol names are qualified the way some servers qualify them ({@code Bag::size}).
 */
final class FakeLanguageServer implements LanguageServer, TextDocumentService, WorkspaceService {
    private static final Pattern BLOCK = Pattern.compile("(class|function)\\s+(\\w+)|^\\s+(\\w+)\\s*\\(", Pattern.MULTILINE);
    private final Map<String, String> open = new ConcurrentHashMap<>();
    volatile boolean initialized;
    volatile int symbolRequests;

    @Override public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        initialized = params.getCapabilities().getTextDocument().getDocumentSymbol().getHierarchicalDocumentSymbolSupport();
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setDocumentSymbolProvider(true);
        return CompletableFuture.completedFuture(new InitializeResult(capabilities));
    }

    @Override public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override public void exit() { }

    @Override public TextDocumentService getTextDocumentService() {
        return this;
    }

    @Override public WorkspaceService getWorkspaceService() {
        return this;
    }

    @Override public void didOpen(DidOpenTextDocumentParams params) {
        open.put(params.getTextDocument().getUri(), params.getTextDocument().getText());
    }

    @Override public void didChange(DidChangeTextDocumentParams params) { }

    @Override public void didClose(DidCloseTextDocumentParams params) {
        open.remove(params.getTextDocument().getUri());
    }

    @Override public void didSave(DidSaveTextDocumentParams params) { }

    @Override public void didChangeConfiguration(DidChangeConfigurationParams params) { }

    @Override public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) { }

    @Override public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        symbolRequests++;
        String text = open.getOrDefault(params.getTextDocument().getUri(), "");
        List<Either<SymbolInformation, DocumentSymbol>> top = new ArrayList<>();
        DocumentSymbol type = null;
        int typeEnd = -1;
        Matcher m = BLOCK.matcher(text);
        while (m.find()) {
            int start = m.start(1) >= 0 ? m.start(1) : m.start(3);
            int end = end(text, text.indexOf('{', start));
            if (m.group(1) != null && m.group(1).equals("class")) {
                type = symbol(text, m.group(2), SymbolKind.Class, start, end);
                typeEnd = end;
                top.add(Either.forRight(type));
            } else if (m.group(1) != null) {
                top.add(Either.forRight(symbol(text, m.group(2), SymbolKind.Function, start, end)));
            } else if (type != null && start < typeEnd) {
                type.getChildren().add(symbol(text, type.getName() + "::" + m.group(3), SymbolKind.Method, start, end));
            }
        }
        return CompletableFuture.completedFuture(top);
    }

    private static DocumentSymbol symbol(String text, String name, SymbolKind kind, int start, int end) {
        Range range = new Range(position(text, start), position(text, end));
        DocumentSymbol symbol = new DocumentSymbol(name, kind, range, range);
        symbol.setChildren(new ArrayList<>());
        return symbol;
    }

    private static int end(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            if (text.charAt(i) == '{') depth++;
            if (text.charAt(i) == '}' && --depth == 0) return i + 1;
        }
        return text.length();
    }

    private static Position position(String text, int offset) {
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new Position(line, offset - lineStart);
    }
}
