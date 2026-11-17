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

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Runs the {@link ClusteringLanguageServer}: on standard input and output by default ({@code --stdio} is accepted and
 * ignored), or with {@code --port N} on a loopback socket that takes one client. In stdio mode standard output carries
 * the protocol only; anything else printed to {@code System.out} goes to standard error. The process exits when the
 * client sends {@code exit} or closes the connection.
 */
public final class ClusteringServerMain {
    private static final long CLOSE_WAIT_SECONDS = 5;

    private ClusteringServerMain() { }

    public static void main(String[] args) throws IOException {
        int port = port(args);
        ClusteringLanguageServer server = new ClusteringLanguageServer();
        ExecutorService threads = daemonThreads();
        if (port < 0) {
            OutputStream protocol = new FileOutputStream(FileDescriptor.out);
            System.setOut(System.err);
            serve(server, System.in, protocol, threads);
        } else {
            try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                accept(socket, server, threads);
            }
        }
        System.exit(server.exitCode());
    }

    /** The {@code --port N} or {@code --port=N} argument, or -1 for stdio. */
    static int port(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) return Integer.parseInt(args[i + 1]);
            if (args[i].startsWith("--port=")) return Integer.parseInt(args[i].substring("--port=".length()));
        }
        return -1;
    }

    /**
     * Takes one client on the socket and serves it until it exits or disconnects. After {@code exit} the server ends
     * its output first and gives the client a moment to close its side, so neither side reads from a closed socket.
     */
    static void accept(ServerSocket socket, ClusteringLanguageServer server, ExecutorService threads) throws IOException {
        try (Socket connection = socket.accept()) {
            Future<Void> listening = serve(server, connection.getInputStream(), connection.getOutputStream(), threads);
            if (!listening.isDone()) {
                connection.shutdownOutput();
                waitFor(listening, CLOSE_WAIT_SECONDS);
            }
        }
    }

    /**
     * Connects the server to a client over the streams and returns when the client exits or the input ends. Returns
     * the listener, which is done once the input has ended.
     */
    static Future<Void> serve(ClusteringLanguageServer server, InputStream in, OutputStream out, ExecutorService threads) {
        Launcher<LanguageClient> launcher = new LSPLauncher.Builder<LanguageClient>().setLocalService(server)
                .setRemoteInterface(LanguageClient.class).setInput(in).setOutput(out).setExecutorService(threads).create();
        server.connect(launcher.getRemoteProxy());
        Future<Void> listening = launcher.startListening();
        CompletableFuture<Void> closed = CompletableFuture.runAsync(() -> waitFor(listening, 0), threads);
        CompletableFuture.anyOf(server.exited(), closed).join();
        return listening;
    }

    /** Waits for the listener to end, at most the given seconds (0: without a limit). */
    private static void waitFor(Future<Void> listening, long seconds) {
        try {
            if (seconds > 0) listening.get(seconds, TimeUnit.SECONDS);
            else listening.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | CancellationException | TimeoutException ended) {
            // the input stream ended or failed, or the client did not close in time: the connection is closed next
        }
    }

    /** Daemon threads, so that the server never keeps a JVM alive on its own. */
    static ExecutorService daemonThreads() {
        return Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "code-clustering-lsp");
            thread.setDaemon(true);
            return thread;
        });
    }
}
