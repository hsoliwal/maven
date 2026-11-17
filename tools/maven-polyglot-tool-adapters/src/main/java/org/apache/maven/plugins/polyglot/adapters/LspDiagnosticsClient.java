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

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressKind;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * The client side of one linter session: keeps the latest diagnostics per opened file for version 1 (or unversioned)
 * and tracks open work-done progress. Servers publish in stages (an early, often empty publish, then the result of a
 * slower check: rust-analyzer's flycheck, JDT LS, ESLint, pyright), so the first publish is not the answer. The
 * session is quiet when every file has been answered, no work-done progress is open (the server-specific "busy"
 * signal) and nothing was published or reported for the settle period; it is waited for up to the deadline.
 */
final class LspDiagnosticsClient implements LanguageClient {
    /** How a wait for quiescence ended. */
    enum Quiet { QUIET, DEADLINE, EXITED }

    private static final long POLL_MILLIS = 25;
    private final Map<Path, List<Diagnostic>> answers = new ConcurrentHashMap<>();
    private final Set<Path> expected;
    private final Set<String> progress = ConcurrentHashMap.newKeySet();
    private final AtomicInteger published = new AtomicInteger();
    private final AtomicLong lastActivity = new AtomicLong(System.nanoTime());

    LspDiagnosticsClient(Collection<Path> files) {
        expected = Set.copyOf(files);
    }

    @Override public void publishDiagnostics(PublishDiagnosticsParams params) {
        boolean current = params.getVersion() == null || params.getVersion() == LspDiagnosticsMain.VERSION;
        path(params.getUri()).filter(expected::contains).filter(file -> current).ifPresent(file -> {
            answers.put(file, List.copyOf(Objects.requireNonNullElse(params.getDiagnostics(), List.of())));
            published.incrementAndGet();
            lastActivity.set(System.nanoTime());
        });
    }

    /** Work-done progress: a token is busy from {@code begin} to {@code end}. */
    @Override public void notifyProgress(ProgressParams params) {
        String token = String.valueOf(params.getToken() == null ? null : params.getToken().get());
        Optional<WorkDoneProgressKind> kind = Optional.ofNullable(params.getValue()).filter(v -> v.isLeft())
                .map(v -> v.getLeft().getKind());
        kind.filter(WorkDoneProgressKind.begin::equals).ifPresent(k -> progress.add(token));
        kind.filter(WorkDoneProgressKind.end::equals).ifPresent(k -> progress.remove(token));
        lastActivity.set(System.nanoTime());
    }

    @Override public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
        return CompletableFuture.completedFuture(null);
    }

    /** Waits until the session is quiet, the deadline ({@link System#nanoTime} value) passes or the server exits. */
    Quiet awaitQuiet(long end, Duration settle, Process process) {
        return Stream.generate(() -> poll(end, settle.toNanos(), process)).flatMap(Optional::stream).findFirst()
                .orElse(Quiet.DEADLINE);
    }

    private Optional<Quiet> poll(long end, long settle, Process process) {
        long now = System.nanoTime();
        if (!process.isAlive()) {
            return Optional.of(Quiet.EXITED);
        }
        if (answers.keySet().containsAll(expected) && progress.isEmpty() && now - lastActivity.get() >= settle) {
            return Optional.of(Quiet.QUIET);
        }
        return now >= end || !pause(Math.min(TimeUnit.NANOSECONDS.toMillis(end - now) + 1, POLL_MILLIS))
                ? Optional.of(Quiet.DEADLINE) : Optional.empty();
    }

    private static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    Map<Path, List<Diagnostic>> snapshot() {
        return Map.copyOf(answers);
    }

    /** How many accepted publishes there have been, to notice one that arrives after the snapshot. */
    int published() {
        return published.get();
    }

    /** Whether more than {@code count} publishes are accepted before {@code until} ({@link System#nanoTime}). */
    boolean publishedBy(int count, long until, Process process) {
        return Stream.generate(() -> later(count, until, process)).flatMap(Optional::stream).findFirst().orElse(false);
    }

    private Optional<Boolean> later(int count, long until, Process process) {
        if (published.get() > count) {
            return Optional.of(true);
        }
        long now = System.nanoTime();
        return now >= until || !process.isAlive() || !pause(Math.min(TimeUnit.NANOSECONDS.toMillis(until - now) + 1,
                POLL_MILLIS)) ? Optional.of(published.get() > count) : Optional.empty();
    }

    /** What is missing or still running, for the notes of a run that did not become quiet. */
    List<String> unfinished() {
        List<String> notes = new ArrayList<>(expected.stream().filter(f -> !answers.containsKey(f)).sorted()
                .map(f -> "no diagnostics for version " + LspDiagnosticsMain.VERSION + " before the deadline: " + f)
                .toList());
        if (notes.isEmpty()) {
            notes.add("the server was still busy at the deadline (work-done progress open: " + progress.size()
                    + ", or publishing within the settle period); later diagnostics may be missing");
        }
        return notes;
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
