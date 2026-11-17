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

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Diagnostics of one build: made root-relative and attributed to the project holding the file, deduplicated across
 * sources (a failure message and the log), written as {@code diagnostic} events until {@code maxRepeat} of one rule
 * in one project and severity, then counted and closed by one {@code repeat} event.
 */
final class DiagnosticSink {
    /** Distinct diagnostics kept in memory for the digest and the summary. */
    static final int MAX_KEPT = 5000;

    private enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    private static final class Repeat {
        private final Diagnostic example;
        private int count;

        Repeat(Diagnostic example) {
            this.example = example;
        }
    }

    private final AgentSettings settings;
    private final EventWriter writer;
    private final Set<String> seen = new HashSet<>();
    private final Map<String, Repeat> repeats = new LinkedHashMap<>();
    private final List<Diagnostic> kept = new ArrayList<>();
    private final Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
    private int collapsed;

    DiagnosticSink(AgentSettings settings, EventWriter writer) {
        this.settings = settings;
        this.writer = writer;
    }

    /** Records raw diagnostics of {@code fallbackProject} (when the file names none) reported by {@code source}. */
    void accept(List<DiagnosticLines.Raw> raws, ReactorIndex index, Optional<ReactorIndex.Module> fallbackProject,
            String source) {
        raws.forEach(raw -> accept(diagnostic(raw, index, fallbackProject, source)));
    }

    private void accept(Diagnostic diagnostic) {
        if (!seen.add(diagnostic.key())) {
            return;
        }
        Severity severity = severity(diagnostic.severity());
        counts.merge(severity, 1, Integer::sum);
        String group = diagnostic.project() + '\u0001' + diagnostic.severity() + '\u0001' + diagnostic.repeatKey();
        Repeat repeat = repeats.computeIfAbsent(group, key -> new Repeat(diagnostic));
        if (++repeat.count <= settings.maxRepeat()) {
            writer.emit("diagnostic", diagnostic.json());
        } else {
            collapsed++;
        }
        if (kept.size() < MAX_KEPT) {
            kept.add(diagnostic);
        }
    }

    private Diagnostic diagnostic(DiagnosticLines.Raw raw, ReactorIndex index,
            Optional<ReactorIndex.Module> fallbackProject, String source) {
        Path base = fallbackProject.map(ReactorIndex.Module::basedir).orElse(settings.root());
        Optional<Path> file = path(base, raw.file());
        Optional<ReactorIndex.Module> module = file.flatMap(index::owning).or(() -> fallbackProject)
                .or(() -> Optional.ofNullable(raw.artifactId()).flatMap(index::byArtifactId));
        String relative = file.map(settings::relative).orElse(raw.file());
        String message = Redact.clean(raw.message(), settings.root(), settings.messageChars());
        String mojo = Optional.ofNullable(raw.mojo()).orElse("");
        return new Diagnostic(module.map(ReactorIndex.Module::id).orElse(""), source, raw.severity(), relative,
                raw.line(), raw.column(), raw.rule(), message, mojo);
    }

    private static Optional<Path> path(Path base, String file) {
        try {
            return Optional.of(base.resolve(file).toAbsolutePath().normalize());
        } catch (InvalidPathException notAPath) {
            return Optional.empty();
        }
    }

    private static Severity severity(String text) {
        return "ERROR".equals(text) ? Severity.ERROR : "WARNING".equals(text) ? Severity.WARNING : Severity.INFO;
    }

    /** Writes a {@code repeat} event for every group that went past {@code maxRepeat}. */
    void flushRepeats() {
        repeats.values().stream().filter(r -> r.count > settings.maxRepeat()).forEach(r -> writer.emit("repeat",
                new JsonText.Obj().put("project", JsonText.orNull(r.example.project()))
                        .put("severity", r.example.severity()).put("rule", JsonText.orNull(r.example.rule()))
                        .put("key", r.example.repeatKey()).put("count", r.count)
                        .put("example", r.example.location() + " " + r.example.message())));
    }

    /** Distinct diagnostics of {@code severity}, of one project when {@code project} is not empty. */
    List<Diagnostic> of(String severity, String project) {
        return kept.stream().filter(d -> d.severity().equals(severity))
                .filter(d -> project.isEmpty() || d.project().equals(project)).collect(Collectors.toList());
    }

    int count(String severity) {
        return counts.getOrDefault(severity(severity), 0);
    }

    /** The {@code diagnostics} counts of the session-end event. */
    JsonText.Obj counts() {
        return new JsonText.Obj().put("error", count("ERROR")).put("warning", count("WARNING"))
                .put("info", count("INFO")).put("collapsed", collapsed);
    }
}
