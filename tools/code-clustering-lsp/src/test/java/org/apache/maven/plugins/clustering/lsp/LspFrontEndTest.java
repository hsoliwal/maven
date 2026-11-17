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
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomFrontEnd;
import org.apache.maven.plugins.clustering.AtomPlanes;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * The LSP front end against an in-process server over a loopback socket. It checks the initialize capabilities,
 * the symbols that become atoms (TYPE, METHOD, a qualified method name), the logic plane of a renamed copy, the
 * fallback to the lexical front end when the server cannot start, service discovery, and the configuration parser.
 * If clangd is on the PATH, it also atomizes a C++ file through it. Argument: a scratch directory.
 */
public final class LspFrontEndTest {
    private static final String JS = String.join("\n", "class Bag {", "  size(items) {", "    return items.length;", "  }",
            "}", "function sum(values) {", "  let total = 0;", "  for (const v of values) { total += v; }",
            "  return total;", "}", "function add(xs) {", "  let acc = 0;", "  for (const x of xs) { acc += x; }",
            "  return acc;", "}", "");
    private static final String CPP = String.join("\n", "struct Point {", "  double x, y;",
            "  double norm() const { return x * x + y * y; }", "};", "int sum(const int* v, int n) {",
            "  int total = 0;", "  for (int i = 0; i < n; i++) { total += v[i]; }", "  return total;", "}",
            "int add(const int* w, int m) {", "  int acc = 0;", "  for (int j = 0; j < m; j++) { acc += w[j]; }",
            "  return acc;", "}", "");
    private static int failures;

    private LspFrontEndTest() { }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "target/lsp-test").toAbsolutePath();
        Path src = dir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("a.js"), JS, StandardCharsets.UTF_8);
        FakeLanguageServer fake = new FakeLanguageServer();
        ExecutorService serverThreads = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-server");
            t.setDaemon(true);
            return t;
        });
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            serverThreads.submit(() -> serve(socket, fake, serverThreads));
            LspFrontEnd lsp = new LspFrontEnd(Map.of("js", List.of("fake")), Duration.ofSeconds(20), (cmd, root, timeout) -> {
                Socket client = new Socket(InetAddress.getLoopbackAddress(), socket.getLocalPort());
                return LspSession.connect(client.getInputStream(), client.getOutputStream(), root, timeout);
            });
            List<Atom> atoms = AtomPlanes.scan(List.of(src), List.of(lsp));
            String names = atoms.stream().map(a -> a.kind() + "#" + a.symbol()).sorted().collect(Collectors.joining(" "));
            check("hierarchical symbols requested", fake.initialized && fake.symbolRequests == 1);
            check("atoms " + names, names.equals("METHOD#Bag.size(1) METHOD#add(1) METHOD#sum(1) TYPE#Bag"));
            Atom sum = find(atoms, "sum(1)");
            Atom add = find(atoms, "add(1)");
            check("logic: renamed copy through the server", sum.logic().equals(add.logic()) && !sum.exact().equals(add.exact()));
        }
        serverThreads.shutdownNow();
        LspFrontEnd broken = new LspFrontEnd(Map.of("js", List.of("no-such-language-server-binary")), Duration.ofSeconds(5),
                LspSession::start);
        List<Atom> fallback = AtomPlanes.scan(List.of(src), List.of(broken));
        check("fallback to lexical when the server cannot start: " + fallback.size(), fallback.size() == 4);
        check("parse configuration", LspFrontEnd.parse("c,H=clangd;py=pyright-langserver --stdio;bad").equals(Map.of(
                "c", List.of("clangd"), "h", List.of("clangd"), "py", List.of("pyright-langserver", "--stdio"))));
        System.setProperty("clustering.lsp.servers", "js=x");
        check("service discovery", AtomFrontEnd.all().stream().anyMatch(f -> f.name().equals("lsp")));
        System.clearProperty("clustering.lsp.servers");
        clangd(dir);
        if (failures > 0) throw new AssertionError(failures + " LSP front end checks failed");
        System.err.println("lsp front end: all checks passed");
    }

    private static void serve(ServerSocket socket, FakeLanguageServer fake, ExecutorService threads) {
        try {
            Socket connection = socket.accept();
            Launcher<LanguageClient> launcher = new LSPLauncher.Builder<LanguageClient>().setLocalService(fake)
                    .setRemoteInterface(LanguageClient.class).setInput(connection.getInputStream())
                    .setOutput(connection.getOutputStream()).setExecutorService(threads).create();
            launcher.startListening();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A real language core: clangd (Eclipse CDT's LSP server), when installed. */
    private static void clangd(Path dir) throws Exception {
        Path src = dir.resolve("cpp");
        Files.createDirectories(src);
        try {
            LspSession.start(List.of("clangd"), src, Duration.ofSeconds(60)).close();
            System.err.println("clangd answers initialize");
        } catch (IOException e) {
            System.err.println("SKIP clangd cannot start here: " + e.getMessage());
            return;
        }
        Files.writeString(src.resolve("p.cpp"), CPP, StandardCharsets.UTF_8);
        int[] sessions = {0};
        LspFrontEnd lsp = new LspFrontEnd(Map.of("cpp", List.of("clangd")), Duration.ofSeconds(60), (cmd, root, timeout) -> {
            LspSession session = LspSession.start(cmd, root, timeout);
            sessions[0]++;
            return session;
        });
        List<Atom> atoms = AtomPlanes.scan(List.of(src), List.of(lsp));
        String names = atoms.stream().map(a -> a.kind() + "#" + a.symbol()).sorted().collect(Collectors.joining(" "));
        check("clangd session ran", sessions[0] == 1);
        check("clangd atoms " + names, names.contains("TYPE#Point") && names.contains("METHOD#Point.norm(0)")
                && names.contains("METHOD#sum(2)") && names.contains("METHOD#add(2)"));
        Atom sum = find(atoms, "sum(2)");
        Atom add = find(atoms, "add(2)");
        check("clangd logic: renamed copy", sum != null && add != null && sum.logic().equals(add.logic()));
    }

    private static Atom find(List<Atom> atoms, String symbol) {
        return atoms.stream().filter(a -> a.symbol().equals(symbol)).findFirst().orElse(null);
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.err.println((ok ? "PASS " : "FAIL ") + name);
    }
}
