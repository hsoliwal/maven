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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.maven.exception.DefaultExceptionHandler;

/**
 * The end of a recorded build: warnings from the {@code -l} log, repeat counts, the {@code session-end} event, the
 * published event file and, in agent mode, {@code failure.json}, {@code failure.md} (or their removal after a green
 * build) and {@code summary.txt}, printed on standard output in summary mode.
 */
final class SessionReport {
    /** Characters of the {@code -l} log read for warnings. */
    static final long MAX_LOG_CHARS = 64L * 1024 * 1024;

    private SessionReport() {}

    static void write(BuildRecorder recorder, List<Throwable> exceptions) throws IOException {
        AgentSettings settings = recorder.settings();
        recorder.skipUnfinished();
        diagnose(recorder, exceptions);
        List<BuildRecorder.FailureNote> notes = notes(recorder, exceptions);
        List<FailureDigest.Failure> failures = notes.stream().map(note -> failure(recorder, note))
                .collect(Collectors.toList());
        String outcome = failures.isEmpty() ? "success" : "failure";
        Optional<FailureDigest> digest = failures.isEmpty() || !settings.agent() ? Optional.empty()
                : Optional.of(digest(recorder, outcome, notes, failures));
        recorder.writer().emitFinal("session-end", sessionEnd(recorder, outcome, notes, digest.isPresent()));
        recorder.writer().publish(settings.eventsFile());
        agentFiles(settings, digest);
        summary(recorder, outcome, failures, digest.isPresent());
    }

    /** The diagnostics known only at the end: the log's, a POM failure's model problems, then the repeat counts. */
    private static void diagnose(BuildRecorder recorder, List<Throwable> exceptions) throws IOException {
        scanLog(recorder);
        exceptions.forEach(e -> recorder.diagnostics().accept(modelProblems(e), recorder.index(), Optional.empty(),
                "model"));
        recorder.diagnostics().flushRepeats();
    }

    /** Diagnostics of the {@code -l} log ({@code maven.events.log}), mostly the warnings a failure message lacks. */
    private static void scanLog(BuildRecorder recorder) throws IOException {
        Optional<Path> log = recorder.settings().eventsLog().filter(Files::isRegularFile);
        if (log.isEmpty()) {
            return;
        }
        DiagnosticLines reader = new DiagnosticLines(DiagnosticLines.ERROR, "", "");
        Charset charset = LogCharset.of(log.get(), LogCharset.candidates(), MAX_LOG_CHARS);
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(Files.newInputStream(log.get()), charset))) {
            long[] chars = {0};
            lines.lines().takeWhile(line -> (chars[0] += line.length() + 1) <= MAX_LOG_CHARS).forEach(reader::accept);
        }
        Map<Boolean, List<DiagnosticLines.Raw>> byCompiler = reader.found().stream()
                .collect(Collectors.partitioningBy(raw -> raw.mojo() != null && raw.mojo().startsWith("compiler:")));
        recorder.diagnostics().accept(byCompiler.get(true), recorder.index(), Optional.empty(), "compiler");
        recorder.diagnostics().accept(byCompiler.get(false), recorder.index(), Optional.empty(), "log");
    }

    /** Failed projects in reactor order; a build that failed before its projects ran gets one note per cause. */
    private static List<BuildRecorder.FailureNote> notes(BuildRecorder recorder, List<Throwable> exceptions) {
        List<BuildRecorder.FailureNote> notes = new ArrayList<>(recorder.failures().values());
        notes.sort(Comparator.comparingInt(note -> recorder.index().byId(note.project())
                .map(ReactorIndex.Module::index).orElse(Integer.MAX_VALUE)));
        if (notes.isEmpty()) {
            exceptions.stream().limit(3).forEach(e -> notes.add(new BuildRecorder.FailureNote("",
                    modelProblems(e).isEmpty() ? "other" : "model", "", "", List.of(), e)));
        }
        return notes;
    }

    /** The model problems of a POM failure; none when this Maven lacks the Maven 3 model classes. */
    static List<DiagnosticLines.Raw> modelProblems(Throwable failure) {
        try {
            return ModelProblems.of(failure);
        } catch (LinkageError absent) {
            return List.of();
        }
    }

    private static FailureDigest.Failure failure(BuildRecorder recorder, BuildRecorder.FailureNote note) {
        AgentSettings settings = recorder.settings();
        Optional<ReactorIndex.Module> module = recorder.index().byId(note.project());
        String path = module.map(ReactorIndex.Module::path).orElse(".");
        List<Diagnostic> errors = recorder.diagnostics().of(DiagnosticLines.ERROR, note.project());
        List<TestResult> tests = recorder.tests().failed(note.project());
        List<FailureDigest.Item> items = errors.stream().limit(settings.printLimit()).map(d -> new FailureDigest.Item(d,
                SourceWindow.read(settings.root(), d.file(), d.line(), settings.context()))).collect(Collectors.toList());
        String reproduce = ReproduceCommand.of(MavenCommands.launcher(settings.root()), note.kind(), path, errors,
                tests.stream().map(TestResult::test).collect(Collectors.toList()), note.phase(), note.goal());
        return new FailureDigest.Failure(module.map(ReactorIndex.Module::index).orElse(0), note.project(), path,
                note.kind(), note.mojo(), message(note.cause(), settings), helpReference(note.cause()), items,
                tests.stream().limit(settings.printLimit()).collect(Collectors.toList()), reproduce);
    }

    private static String message(Throwable cause, AgentSettings settings) {
        String text = cause == null ? "" : Redact.firstLine(BuildRecorder.message(BuildRecorder.unwrap(cause)),
                settings.root(), settings.messageChars());
        return text.isEmpty() ? "no message" : text;
    }

    /** The {@code [Help]} reference Maven prints for the cause, from Maven's own exception handler. */
    static String helpReference(Throwable cause) {
        try {
            return cause == null ? "" : Optional.ofNullable(new DefaultExceptionHandler().handleException(cause)
                    .getReference()).orElse("");
        } catch (RuntimeException | LinkageError unavailable) {
            return "";
        }
    }

    private static FailureDigest digest(BuildRecorder recorder, String outcome, List<BuildRecorder.FailureNote> notes,
            List<FailureDigest.Failure> failures) {
        AgentSettings settings = recorder.settings();
        int limit = settings.printLimit();
        int omittedDiagnostics = notes.stream().mapToInt(n -> Math.max(0,
                recorder.diagnostics().of(DiagnosticLines.ERROR, n.project()).size() - limit)).sum();
        int omittedTests = notes.stream().mapToInt(n -> Math.max(0, recorder.tests().failed(n.project()).size() - limit))
                .sum();
        Path projectDir = recorder.index().byId(notes.get(0).project()).map(ReactorIndex.Module::basedir)
                .orElse(settings.root());
        FailureDigest.Advice advice = AdviceBridge.advise(recorder.request().getUserProperties(),
                recorder.request().getSystemProperties(), localRepository(recorder), projectDir, settings.eventsLog());
        return new FailureDigest(outcome, recorder.request().getGoals(), failures, skipped(recorder), advice,
                new FailureDigest.Limits(limit, omittedDiagnostics, omittedTests));
    }

    private static Path localRepository(BuildRecorder recorder) {
        return recorder.session().map(s -> s.getRepositorySession().getLocalRepository().getBasedir().toPath())
                .or(() -> Optional.ofNullable(recorder.request().getLocalRepositoryPath()).map(java.io.File::toPath))
                .orElse(Path.of(System.getProperty("user.home"), ".m2", "repository"));
    }

    private static List<String> skipped(BuildRecorder recorder) {
        return recorder.index().modules().stream().map(ReactorIndex.Module::id)
                .filter(id -> "skipped".equals(recorder.outcomes().get(id))).collect(Collectors.toList());
    }

    private static long outcomeCount(BuildRecorder recorder, String outcome) {
        return recorder.outcomes().values().stream().filter(outcome::equals).count();
    }

    private static JsonText.Obj sessionEnd(BuildRecorder recorder, String outcome, List<BuildRecorder.FailureNote> notes,
            boolean digest) {
        AgentSettings settings = recorder.settings();
        JsonText.Obj first = recorder.failures().values().stream().findFirst().or(() -> notes.stream().findFirst())
                .map(note -> new JsonText.Obj().put("project", JsonText.orNull(note.project()))
                        .put("mojo", JsonText.orNull(note.mojo()))).orElse(null);
        return new JsonText.Obj().put("outcome", outcome).put("durationMs", recorder.elapsedMillis())
                .put("projects", projects(recorder)).put("diagnostics", recorder.diagnostics().counts())
                .put("tests", recorder.tests().counts()).put("firstFailure", first)
                .put("digest", digest ? settings.relative(settings.agentDir().resolve("failure.json")) : null)
                .put("events", recorder.writer().written() + 1).put("truncated", recorder.writer().dropped() > 0);
    }

    private static JsonText.Obj projects(BuildRecorder recorder) {
        return new JsonText.Obj().put("total", recorder.index().modules().size())
                .put("success", outcomeCount(recorder, "success")).put("failure", outcomeCount(recorder, "failure"))
                .put("skipped", outcomeCount(recorder, "skipped"));
    }

    /**
     * Removes the previous build's event stream and, in agent mode, its digest and summary before this build runs, so
     * a build that is killed before its session end leaves none of them looking current.
     */
    static void removePrevious(AgentSettings settings) throws IOException {
        Files.deleteIfExists(settings.eventsFile());
        if (settings.agent()) {
            Files.deleteIfExists(settings.agentDir().resolve("failure.json"));
            Files.deleteIfExists(settings.agentDir().resolve("failure.md"));
            Files.deleteIfExists(settings.agentDir().resolve("summary.txt"));
        }
    }

    /** In agent mode: failure.json and failure.md after a failure, their removal after a green build. */
    private static void agentFiles(AgentSettings settings, Optional<FailureDigest> digest) throws IOException {
        if (!settings.agent()) {
            return;
        }
        Path dir = settings.agentDir();
        Files.createDirectories(dir);
        if (digest.isPresent()) {
            Files.writeString(dir.resolve("failure.json"), digest.get().json(), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("failure.md"), digest.get().markdown(), StandardCharsets.UTF_8);
        } else {
            Files.deleteIfExists(dir.resolve("failure.json"));
            Files.deleteIfExists(dir.resolve("failure.md"));
        }
    }

    private static void summary(BuildRecorder recorder, String outcome, List<FailureDigest.Failure> failures,
            boolean digest) throws IOException {
        AgentSettings settings = recorder.settings();
        if (!settings.agent() && settings.stdout() != AgentSettings.Stdout.SUMMARY) {
            return;
        }
        List<String> files = files(settings, digest);
        List<String> lines = SummaryPrinter.lines(header(recorder, outcome), failures, skipped(recorder),
                recorder.diagnostics().of(DiagnosticLines.WARNING, ""), files, settings.printLimit());
        String text = SummaryPrinter.bounded(lines, settings.summaryBytes(), files.get(0));
        if (settings.agent()) {
            Files.writeString(settings.agentDir().resolve("summary.txt"), text, StandardCharsets.UTF_8);
        }
        if (settings.stdout() == AgentSettings.Stdout.SUMMARY) {
            System.out.print(text);
            System.out.flush();
        }
    }

    /** The written files, the digest first, root-relative. */
    private static List<String> files(AgentSettings settings, boolean digest) {
        List<String> files = new ArrayList<>();
        if (digest) {
            files.add(settings.relative(settings.agentDir().resolve("failure.md")));
            files.add(settings.relative(settings.agentDir().resolve("failure.json")));
        }
        files.add(settings.relative(settings.eventsFile()));
        return files;
    }

    private static SummaryPrinter.Header header(BuildRecorder recorder, String outcome) {
        DiagnosticSink d = recorder.diagnostics();
        TestSink t = recorder.tests();
        return new SummaryPrinter.Header(outcome, recorder.elapsedMillis(), recorder.index().modules().size(),
                (int) outcomeCount(recorder, "success"), (int) outcomeCount(recorder, "failure"),
                (int) outcomeCount(recorder, "skipped"), d.count("ERROR"), d.count("WARNING"), d.count("INFO"),
                t.run(), t.failures(), t.errors(), t.skipped());
    }
}
