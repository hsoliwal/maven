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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * A test client that keeps the latest published diagnostics per URI and the registered capability methods, and lets
 * the test wait, with a timeout, until they reach an expected state. Server log messages go to standard error.
 */
final class RecordingClient implements LanguageClient {
    private final Map<String, List<Diagnostic>> latest = new HashMap<>();
    private final List<String> registered = new ArrayList<>();

    @Override public synchronized void publishDiagnostics(PublishDiagnosticsParams params) {
        latest.put(params.getUri(), List.copyOf(params.getDiagnostics()));
        notifyAll();
    }

    @Override public synchronized CompletableFuture<Void> registerCapability(RegistrationParams params) {
        params.getRegistrations().forEach(r -> registered.add(r.getMethod()));
        notifyAll();
        return CompletableFuture.completedFuture(null);
    }

    @Override public void telemetryEvent(Object object) { }

    @Override public void showMessage(MessageParams message) {
        System.err.println("server message: " + message.getMessage());
    }

    @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams request) {
        return CompletableFuture.completedFuture(null);
    }

    @Override public void logMessage(MessageParams message) {
        System.err.println("server log: " + message.getMessage());
    }

    /** The latest diagnostics of a URI, or null if none were published for it. */
    synchronized List<Diagnostic> latest(String uri) {
        return latest.get(uri);
    }

    synchronized List<String> registered() {
        return List.copyOf(registered);
    }

    /** Waits until the condition holds, at most the timeout; returns whether it held. */
    synchronized boolean await(Predicate<RecordingClient> condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.test(this)) {
            long left = deadline - System.nanoTime();
            if (left <= 0) return false;
            TimeUnit.NANOSECONDS.timedWait(this, left);
        }
        return true;
    }
}
