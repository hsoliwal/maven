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
package org.apache.maven.agent.events;

import java.io.IOException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;

/**
 * The state of one recorded build: turns lifecycle events into {@code session-start}, {@code project-*} and
 * {@code mojo-*} events, mojo failure messages into diagnostics, test runs into test events, and notes each failed
 * project for the digest that {@link SessionReport} writes at the end. Callers serialize access.
 */
final class BuildRecorder {
    /** The first failure of a project: kind and mojo of the failed step (empty when no mojo failed) and the cause. */
    record FailureNote(String project, String kind, String mojo, String phase, Throwable cause) {}

    private final AgentSettings settings;
    private final EventWriter writer;
    private final MavenExecutionRequest request;
    private final MojoTimeline timeline = new MojoTimeline();
    private final DiagnosticSink diagnostics;
    private final TestSink tests;
    private final Map<String, FailureNote> failures = new LinkedHashMap<>();
    private final Map<String, String> outcomes = new LinkedHashMap<>();
    private final Map<ExecutionEvent.Type, Consumer<ExecutionEvent>> handlers;
    private final long startNanos = System.nanoTime();
    private ReactorIndex index = ReactorIndex.empty();
    private Optional<MavenSession> session = Optional.empty();
    private boolean finished;

    BuildRecorder(AgentSettings settings, EventWriter writer, MavenExecutionRequest request) {
        this.settings = settings;
        this.writer = writer;
        this.request = request;
        this.diagnostics = new DiagnosticSink(settings, writer);
        this.tests = new TestSink(settings, writer);
        this.handlers = handlers();
    }

    private Map<ExecutionEvent.Type, Consumer<ExecutionEvent>> handlers() {
        Map<ExecutionEvent.Type, Consumer<ExecutionEvent>> handlers = new EnumMap<>(ExecutionEvent.Type.class);
        handlers.put(ExecutionEvent.Type.SessionStarted, e -> sessionStarted(e.getSession()));
        handlers.put(ExecutionEvent.Type.ProjectStarted, e -> projectStarted(e.getProject()));
        handlers.put(ExecutionEvent.Type.ProjectSucceeded, e -> projectEnded(e.getProject(), "success", null));
        handlers.put(ExecutionEvent.Type.ProjectFailed, e -> projectEnded(e.getProject(), "failure", e.getException()));
        handlers.put(ExecutionEvent.Type.ProjectSkipped, e -> projectEnded(e.getProject(), "skipped", null));
        handlers.put(ExecutionEvent.Type.MojoStarted, e -> mojoStarted(e.getProject(), e.getMojoExecution()));
        handlers.put(ExecutionEvent.Type.MojoSucceeded, e -> mojoEnded(e, "success"));
        handlers.put(ExecutionEvent.Type.MojoFailed, e -> mojoEnded(e, "failure"));
        handlers.put(ExecutionEvent.Type.MojoSkipped, e -> mojoEnded(e, "skipped"));
        return handlers;
    }

    /** Records one lifecycle event; forked executions and discovery events are not recorded. */
    void handle(ExecutionEvent event) {
        if (!finished) {
            Optional.ofNullable(handlers.get(event.getType())).ifPresent(handler -> handler.accept(event));
        }
    }

    private void sessionStarted(MavenSession started) {
        session = Optional.of(started);
        index = ReactorIndex.of(started.getProjects(), settings);
        writer.emit("session-start", new JsonText.Obj()
                .put("maven", started.getSystemProperties().getProperty("maven.version", "unknown"))
                .put("java", System.getProperty("java.version"))
                .put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch")).put("root", ".")
                .put("goals", request.getGoals()).put("profiles", request.getActiveProfiles())
                .put("threads", request.getDegreeOfConcurrency()).put("offline", request.isOffline())
                .put("userPropertyKeys", new TreeSet<>(request.getUserProperties().stringPropertyNames()))
                .put("modules", index.modules().stream().map(ReactorIndex.Module::json).collect(Collectors.toList())));
    }

    private void projectStarted(MavenProject project) {
        timeline.start(MojoTimeline.key(project));
        writer.emit("project-start", projectFields(project));
    }

    private void projectEnded(MavenProject project, String outcome, Throwable cause) {
        String id = ReactorIndex.id(project);
        long millis = timeline.end(MojoTimeline.key(project)).map(MojoTimeline.Start::elapsedMillis).orElse(0L);
        outcomes.put(id, outcome);
        if ("failure".equals(outcome)) {
            failures.putIfAbsent(id, new FailureNote(id, "other", "", "", cause));
        }
        writer.emit("project-end", projectFields(project).put("outcome", outcome).put("durationMs", millis));
    }

    /** A fail-fast build stops without events for the projects after the failure; they end here as skipped. */
    void skipUnfinished() {
        index.modules().stream().filter(module -> !outcomes.containsKey(module.id())).forEach(module -> {
            outcomes.put(module.id(), "skipped");
            writer.emit("project-end", new JsonText.Obj().put("index", module.index()).put("project", module.id())
                    .put("path", module.path()).put("outcome", "skipped").put("durationMs", 0));
        });
    }

    private JsonText.Obj projectFields(MavenProject project) {
        Optional<ReactorIndex.Module> module = index.find(project);
        return new JsonText.Obj().put("index", module.map(ReactorIndex.Module::index).orElse(null))
                .put("project", ReactorIndex.id(project))
                .put("path", module.map(ReactorIndex.Module::path).orElse(settings.relative(project.getBasedir().toPath())));
    }

    private void mojoStarted(MavenProject project, MojoExecution mojo) {
        timeline.start(MojoTimeline.key(project, mojo));
        writer.emit("mojo-start", new JsonText.Obj().put("project", ReactorIndex.id(project))
                .put("mojo", MojoNames.json(mojo)));
    }

    private void mojoEnded(ExecutionEvent event, String outcome) {
        MavenProject project = event.getProject();
        MojoExecution mojo = event.getMojoExecution();
        Optional<MojoTimeline.Start> start = timeline.end(MojoTimeline.key(project, mojo));
        Throwable cause = event.getException();
        writer.emit("mojo-end", new JsonText.Obj().put("project", ReactorIndex.id(project)).put("mojo", MojoNames.json(mojo))
                .put("outcome", outcome).put("durationMs", start.map(MojoTimeline.Start::elapsedMillis).orElse(0L))
                .put("error", cause == null ? null
                        : Redact.firstLine(message(unwrap(cause)), settings.root(), settings.messageChars())));
        if (cause != null) {
            mojoFailed(project, mojo, cause);
        }
        if (TestSink.testMojo(mojo) && !"skipped".equals(outcome)) {
            tests.collect(event.getSession(), project, mojo, start.map(MojoTimeline.Start::wallMillis).orElse(0L));
        }
    }

    private void mojoFailed(MavenProject project, MojoExecution mojo, Throwable cause) {
        String id = ReactorIndex.id(project);
        failures.putIfAbsent(id, new FailureNote(id, MojoNames.kind(mojo), MojoNames.name(mojo),
                Optional.ofNullable(mojo.getLifecyclePhase()).orElse(""), cause));
        diagnostics.accept(DiagnosticLines.parse(message(cause), DiagnosticLines.ERROR, MojoNames.name(mojo),
                project.getArtifactId()), index, index.find(project), MojoNames.source(mojo));
    }

    /** The exception Maven wrapped in a {@link LifecycleExecutionException} ("Failed to execute goal..."). */
    static Throwable unwrap(Throwable cause) {
        return cause instanceof LifecycleExecutionException && cause.getCause() != null ? unwrap(cause.getCause()) : cause;
    }

    /** Messages of {@code cause} and up to two causes below it, distinct, one per line block. */
    static String message(Throwable cause) {
        return Stream.iterate(cause, Objects::nonNull, Throwable::getCause).limit(3)
                .map(t -> Optional.ofNullable(t.getMessage()).orElse(t.getClass().getSimpleName())).distinct()
                .collect(Collectors.joining("\n"));
    }

    /** Writes the session-end event and the agent files; later events are ignored. */
    void finish(List<Throwable> exceptions) throws IOException {
        if (!finished) {
            finished = true;
            SessionReport.write(this, exceptions);
        }
    }

    boolean finished() {
        return finished;
    }

    AgentSettings settings() {
        return settings;
    }

    EventWriter writer() {
        return writer;
    }

    MavenExecutionRequest request() {
        return request;
    }

    Optional<MavenSession> session() {
        return session;
    }

    ReactorIndex index() {
        return index;
    }

    DiagnosticSink diagnostics() {
        return diagnostics;
    }

    TestSink tests() {
        return tests;
    }

    Map<String, FailureNote> failures() {
        return failures;
    }

    Map<String, String> outcomes() {
        return outcomes;
    }

    long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
