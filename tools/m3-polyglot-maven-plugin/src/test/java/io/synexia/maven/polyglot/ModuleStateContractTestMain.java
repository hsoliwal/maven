/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Independent process-level contracts for the persistent module database and original task runner. */
public final class ModuleStateContractTestMain {
    private static final String DATABASE = ".m3/module.m3db";
    private static final String ENVIRONMENT = "M3_MODULE_DB_CONTRACT_ENVIRONMENT";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String BACKEND = "indexdb";
    private static int checks;

    private ModuleStateContractTestMain() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length > 0 && "invoke".equals(arguments[0])) {
            run(Path.of(arguments[1]), arguments[2], Boolean.parseBoolean(arguments[3]), System.out::println);
            return;
        }
        Path temporary = Files.createTempDirectory("m3-module-state-contract-");
        try {
            persistsOneDatabaseAcrossProcesses(temporary);
            authenticatesInputsAndOutputs(temporary);
            separatesFramedFileContents(temporary);
            fingerprintsResolvedExecutable(temporary);
            tracksDirectoryMembership(temporary);
            invalidatesFailedAndUncachedRuns(temporary);
            refusesChangedDuringExecution(temporary);
            invalidatesTimedOutAndInterruptedRuns(temporary);
            propagatesPrerequisites(temporary);
            refusesReuseForOverlappingOutputOwners(temporary);
            bindsEnvironmentAndConfiguration(temporary);
            recoversFromCorruptState(temporary);
            serializesOrRefusesConcurrentWriters(temporary);
            isolatesParallelModules(temporary);
            doesNotWriteDuringPlanning(temporary);
            rejectsNestedLinks(temporary);
            resolvesWindowsExecutableSuffix(temporary);
            System.out.println("PASS: " + checks + " persistent module state contract checks");
        } finally {
            erase(temporary);
        }
    }

    private static void persistsOneDatabaseAcrossProcesses(Path temporary) throws Exception {
        Path project = project(temporary, "persistent");
        String body = task("first", "copy", "a.txt", "b.txt", "first.out", "first.log", List.of("a.txt", "b.txt"))
                + task("second", "copy", "b.txt", "-", "second.out", "second.log", List.of("b.txt"))
                + "task\tall\t-\t.\ndep\tall\tfirst\ndep\tall\tsecond\n";
        manifest(project, body);
        run(project, "all", true);
        check(count(project, "first.log") == 1 && count(project, "second.log") == 1, "first build executes both tasks");
        check(Files.isRegularFile(project.resolve(DATABASE)), "one module database was published");
        try (var files = Files.list(project.resolve(".m3"))) {
            check(files.filter(path -> path.getFileName().toString().endsWith(".m3db")).count() == 1,
                    "all tasks share one module database file");
        }
        check(!Files.exists(project.resolve(".m3/polyglot-stamps")), "no per-task stamp files are created");
        try (ZipFile zip = new ZipFile(project.resolve(DATABASE).toFile())) {
            check(zip.size() > 0, "module database is a readable nonempty archive");
        }
        invoke(project, "all", Map.of());
        check(count(project, "first.log") == 1 && count(project, "second.log") == 1,
                "a fresh JVM reuses both committed task results");
        Files.writeString(project.resolve("a.txt"), "changed");
        run(project, "all", true);
        check(count(project, "first.log") == 2 && count(project, "second.log") == 1,
                "a changed task does not invalidate an independent task in the same module");
    }

    private static void authenticatesInputsAndOutputs(Path temporary) throws Exception {
        Path project = copyProject(temporary, "bytes", "copy");
        run(project, "copy", true);
        FileTime timestamp = Files.getLastModifiedTime(project.resolve("a.txt"));
        Files.writeString(project.resolve("a.txt"), "ONE");
        Files.setLastModifiedTime(project.resolve("a.txt"), timestamp);
        run(project, "copy", true);
        check(count(project) == 2, "same-size input edits with restored timestamps execute");
        String expected = Files.readString(project.resolve("result.txt"));
        timestamp = Files.getLastModifiedTime(project.resolve("result.txt"));
        Files.writeString(project.resolve("result.txt"), "x".repeat(expected.length()));
        Files.setLastModifiedTime(project.resolve("result.txt"), timestamp);
        run(project, "copy", true);
        check(count(project) == 3 && expected.equals(Files.readString(project.resolve("result.txt"))),
                "present but modified output bytes are rebuilt");
        Files.delete(project.resolve("result.txt"));
        run(project, "copy", true);
        check(count(project) == 4, "deleted output is rebuilt");
    }

    private static void separatesFramedFileContents(Path temporary) throws Exception {
        Path project = copyProject(temporary, "framing", "copy");
        // Exact collision in the old raw-content stamp: file contents could absorb the next input delimiter.
        String boundary = "5:input5:b.txt";
        Files.writeString(project.resolve("a.txt"), "A");
        Files.writeString(project.resolve("b.txt"), "B" + boundary + "C");
        run(project, "copy", true);
        String first = Files.readString(project.resolve("result.txt"));
        Files.writeString(project.resolve("a.txt"), "A" + boundary + "B");
        Files.writeString(project.resolve("b.txt"), "C");
        run(project, "copy", true);
        check(count(project) == 2 && !first.equals(Files.readString(project.resolve("result.txt"))),
                "different file boundaries cannot collide in the task fingerprint");
    }

    private static void tracksDirectoryMembership(Path temporary) throws Exception {
        Path project = project(temporary, "directories");
        Files.createDirectories(project.resolve("source/deep/empty"));
        Files.writeString(project.resolve("source/deep/a.txt"), "a");
        manifest(project, task("copy", "directory", "source", "-", "out", "runs.log", List.of("source")));
        run(project, "copy", true);
        run(project, "copy", true);
        check(count(project) == 1, "unchanged nested input and output directories reuse the result");
        Files.writeString(project.resolve("source/deep/b.txt"), "b");
        run(project, "copy", true);
        check(count(project) == 2 && Files.exists(project.resolve("out/deep/b.txt")), "new nested input is included");
        Files.delete(project.resolve("source/deep/a.txt"));
        run(project, "copy", true);
        check(count(project) == 3 && !Files.exists(project.resolve("out/deep/a.txt")), "deleted input removes stale output");
        Files.move(project.resolve("source/deep/b.txt"), project.resolve("source/deep/renamed.txt"));
        run(project, "copy", true);
        check(count(project) == 4 && Files.exists(project.resolve("out/deep/renamed.txt")), "renamed input changes identity");
        Files.delete(project.resolve("out/deep/renamed.txt"));
        run(project, "copy", true);
        check(count(project) == 5, "a missing nested output is detected although the output root exists");
        Files.writeString(project.resolve("out/stale.txt"), "stale");
        run(project, "copy", true);
        check(count(project) == 6 && !Files.exists(project.resolve("out/stale.txt")), "added stale output is detected");
        Files.createDirectory(project.resolve("source/new-empty"));
        run(project, "copy", true);
        check(count(project) == 7 && Files.isDirectory(project.resolve("out/new-empty")), "empty directory membership is recorded");
    }

    private static void fingerprintsResolvedExecutable(Path temporary) throws Exception {
        Path project = project(temporary, "executable-identity");
        Path executable = Files.writeString(project.resolve("fixture-executable"), "first tool bytes");
        TaskManifest.Task task = new TaskManifest.Task("tool", executable.toString(), ".",
                List.of("--option"), List.of("parent"), List.of("a.txt"), List.of("result.txt"));
        List<String> command = List.of(executable.toString(), "--option");
        Map<String, String> environment = Map.of("A", "one", "B", "two");
        Map<String, String> parents = Map.of("parent", "a".repeat(64));
        String original = InputStamps.stamp(task, project, command, environment, parents);
        Map<String, String> reordered = new java.util.LinkedHashMap<>();
        reordered.put("B", "two");
        reordered.put("A", "one");
        check(original.equals(InputStamps.stamp(task, project, command, reordered, parents)),
                "environment iteration order does not change identity");
        FileTime timestamp = Files.getLastModifiedTime(executable);
        Files.writeString(executable, "other tool bytes");
        Files.setLastModifiedTime(executable, timestamp);
        check(!original.equals(InputStamps.stamp(task, project, command, environment, parents)),
                "same-size executable replacement with restored timestamp changes identity");
        Files.writeString(executable, "first tool bytes");
        check(original.equals(InputStamps.stamp(task, project, command, environment, parents)),
                "executable identity depends on content rather than modification time");
        check(!original.equals(InputStamps.stamp(task, project,
                        List.of(executable.toString(), "--changed-option"), environment, parents)),
                "the exact command argument vector participates in identity");
        check(!original.equals(InputStamps.stamp(task, project, command, environment, Map.of("parent", "b".repeat(64)))),
                "the successful prerequisite generation participates in identity");
    }

    private static void invalidatesFailedAndUncachedRuns(Path temporary) throws Exception {
        Path project = copyProject(temporary, "failures", "copy");
        String original = Files.readString(project.resolve("m3-build.tsv"));
        run(project, "copy", true);
        Files.writeString(project.resolve("m3-build.tsv"), original.replace("arg\tcopy\tcopy\n", "arg\tcopy\tfail\n"));
        expectFailure(() -> run(project, "copy", true), "nonzero exit fails the incremental execution");
        Files.writeString(project.resolve("m3-build.tsv"), original);
        run(project, "copy", true);
        check(count(project) == 3, "a failed attempt cannot revive the previous successful identity");
        Files.writeString(project.resolve("m3-build.tsv"), original.replace("arg\tcopy\tcopy\n", "arg\tcopy\tfail\n"));
        expectFailure(() -> run(project, "copy", false), "nonzero exit fails a nonincremental execution");
        Files.writeString(project.resolve("m3-build.tsv"), original);
        run(project, "copy", true);
        check(count(project) == 5, "a failed nonincremental attempt invalidates previous incremental state");
        run(project, "copy", false);
        run(project, "copy", true);
        check(count(project) == 7, "disabled incremental execution does not leave an older reusable record");
        run(project, "copy", true);
        check(count(project) == 7, "a later complete incremental success becomes reusable");
    }

    private static void refusesChangedDuringExecution(Path temporary) throws Exception {
        Path project = copyProject(temporary, "mutation", "mutate");
        String input = Files.readString(project.resolve("a.txt"));
        runAllowingChangedInputRefusal(project);
        check(count(project) == 1 && !input.equals(Files.readString(project.resolve("a.txt"))), "fixture changed input during execution");
        Files.writeString(project.resolve("a.txt"), input);
        runAllowingChangedInputRefusal(project);
        check(count(project) == 2, "input mutation during execution cannot publish a reusable success");
    }

    private static void invalidatesTimedOutAndInterruptedRuns(Path temporary) throws Exception {
        Path project = copyProject(temporary, "timeout", "copy");
        String original = Files.readString(project.resolve("m3-build.tsv"));
        run(project, "copy", true);
        Files.writeString(project.resolve("m3-build.tsv"), original.replace("arg\tcopy\tcopy\n", "arg\tcopy\tsleep\n"));
        expectFailure(() -> TaskRunner.run(TaskManifest.load(project.resolve("m3-build.tsv")), project, "copy", false,
                Duration.ofMillis(300), false, true, BACKEND, ignored -> {}), "timed out execution fails");
        long attempts = count(project);
        Files.writeString(project.resolve("m3-build.tsv"), original);
        run(project, "copy", true);
        check(count(project) == attempts + 1, "timeout invalidates a previous reusable result");

        Path interrupted = copyProject(temporary, "interruption", "copy");
        String successful = Files.readString(interrupted.resolve("m3-build.tsv"));
        run(interrupted, "copy", true);
        Files.writeString(interrupted.resolve("m3-build.tsv"), successful.replace("arg\tcopy\tcopy\n", "arg\tcopy\thold\n"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                run(interrupted, "copy", true);
            } catch (Throwable thrown) {
                failure.set(thrown);
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        }, "module-state-interruption-contract");
        worker.start();
        try {
            awaitFile(interrupted.resolve("ready"));
            worker.interrupt();
            worker.join(5000);
            check(!worker.isAlive() && failure.get() instanceof InterruptedException && interruptPreserved.get(),
                    "interruption stops the child and preserves the interrupt flag");
        } finally {
            Files.writeString(interrupted.resolve("release"), "release");
            worker.interrupt();
            worker.join(5000);
        }
        attempts = count(interrupted);
        Files.writeString(interrupted.resolve("m3-build.tsv"), successful);
        run(interrupted, "copy", true);
        check(count(interrupted) == attempts + 1, "interrupted execution cannot retain a reusable result");
    }

    private static void propagatesPrerequisites(Path temporary) throws Exception {
        Path project = project(temporary, "prerequisites");
        String upstream = task("up", "copy", "a.txt", "-", "up.out", "up.log", List.of("a.txt"));
        String downstream = "task\tbridge\t-\t.\ndep\tbridge\tup\n"
                + task("leaf", "copy", "b.txt", "-", "leaf.out", "leaf.log", List.of("b.txt"))
                + "dep\tleaf\tbridge\n";
        manifest(project, upstream + downstream);
        run(project, "leaf", true);
        run(project, "leaf", true);
        check(count(project, "up.log") == 1 && count(project, "leaf.log") == 1, "unchanged prerequisite chain reuses results");
        Files.writeString(project.resolve("a.txt"), "changed upstream");
        run(project, "leaf", true);
        check(count(project, "up.log") == 2 && count(project, "leaf.log") == 2,
                "prerequisite change propagates through an aggregation task");
        manifest(project, upstream.replace("arg\tup\tcopy\n", "arg\tup\tupper\n") + downstream);
        run(project, "leaf", true);
        check(count(project, "up.log") == 3 && count(project, "leaf.log") == 3,
                "changed prerequisite definition invalidates its dependent");

        Path untracked = project(temporary, "untracked-prerequisite");
        manifest(untracked, task("up", "copy", "a.txt", "-", "up.out", "up.log", List.of()) + downstream);
        run(untracked, "leaf", true);
        run(untracked, "leaf", true);
        check(count(untracked, "up.log") == 2 && count(untracked, "leaf.log") == 2,
                "an uncacheable prerequisite cannot authorize a dependent hit through aggregation");
    }

    private static void bindsEnvironmentAndConfiguration(Path temporary) throws Exception {
        Path project = copyProject(temporary, "environment", "environment");
        invoke(project, "copy", Map.of(ENVIRONMENT, "first"));
        invoke(project, "copy", Map.of(ENVIRONMENT, "first"));
        check(count(project) == 1, "identical process environment permits reuse across JVMs");
        invoke(project, "copy", Map.of(ENVIRONMENT, "second"));
        check(count(project) == 2 && Files.readString(project.resolve("result.txt")).contains("second"),
                "changed inherited environment reruns the task");

        Path parent = Files.createDirectory(temporary.resolve("configuration-parent"));
        Path configured = copyProject(parent, "module", "copy");
        run(configured, "copy", true);
        Files.writeString(configured.resolve("pom.xml"), "<project>first</project>\n");
        run(configured, "copy", true);
        check(count(configured) == 2, "module POM creation changes the context");
        Files.writeString(configured.resolve("pom.xml"), "<project>second</project>\n");
        run(configured, "copy", true);
        check(count(configured) == 3, "module POM edit changes the context");
        Files.createDirectories(parent.resolve(".mvn"));
        Files.writeString(parent.resolve(".mvn/maven.config"), "-Dfixture=one\n");
        run(configured, "copy", true);
        check(count(configured) == 4, "ancestor Maven configuration changes the context");
        run(configured, "copy", true);
        check(count(configured) == 4, "unchanged context remains reusable");
    }

    private static void refusesReuseForOverlappingOutputOwners(Path temporary) throws Exception {
        Path project = project(temporary, "overlapping-outputs");
        Files.createDirectories(project.resolve("source"));
        Files.writeString(project.resolve("source/data.txt"), "directory owner");
        manifest(project,
                task("a", "directory", "source", "-", "build", "a.log", List.of("source"))
                + task("b", "copy", "b.txt", "-", "build/file.txt", "b.log", List.of("b.txt"))
                + "task\tbridge\t-\t.\ndep\tbridge\ta\n"
                + task("consumer", "copy", "a.txt", "-", "consumer.out", "consumer.log", List.of("a.txt"))
                + "dep\tconsumer\tbridge\ntask\tall\t-\t.\ndep\tall\ta\ndep\tall\tb\ndep\tall\tconsumer\n");
        run(project, "all", true);
        List<String> messages = new ArrayList<>();
        run(project, "all", true, messages::add);
        check(count(project, "a.log") == 2 && count(project, "b.log") == 2,
                "accepted parent/child output owners execute instead of reusing incompatible generations");
        check(count(project, "consumer.log") == 2 && messages.stream().noneMatch(line -> line.startsWith("UP-TO-DATE")),
                "overlapping output ownership cannot supply a stale prerequisite through aggregation");
    }

    private static void recoversFromCorruptState(Path temporary) throws Exception {
        Path project = copyProject(temporary, "corruption", "copy");
        run(project, "copy", true);
        Files.writeString(project.resolve(DATABASE), "truncated database");
        run(project, "copy", true);
        check(count(project) == 2, "corrupt database is a cache miss");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(project.resolve(DATABASE)))) {
            zip.putNextEntry(new ZipEntry("unknown-schema"));
            zip.write("future unsupported state".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        run(project, "copy", true);
        check(count(project) == 3, "unknown database contents cannot authorize reuse");
        run(project, "copy", true);
        check(count(project) == 3, "a valid success replaces unusable state");
    }

    private static void serializesOrRefusesConcurrentWriters(Path temporary) throws Exception {
        Path project = copyProject(temporary, "same-module", "hold");
        CompletableFuture<Throwable> first = attemptAsync(project);
        CompletableFuture<Throwable> second = null;
        try {
            awaitFile(project.resolve("ready"));
            second = attemptAsync(project);
            try {
                // Keep the first writer active while the competing call either refuses or blocks.
                second.get(200, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException serialized) {
                // Blocking until the first writer completes is also permitted.
            }
            Files.writeString(project.resolve("release"), "release");
            check(first.get(15, TimeUnit.SECONDS) == null, "first module writer completes");
            Throwable competing = second.get(15, TimeUnit.SECONDS);
            check(competing == null || competing instanceof IOException,
                    "a second writer either serializes or is explicitly refused");
            check(count(project) == 1, "concurrent same-module admission does not execute the tool twice");
            run(project, "copy", true);
            check(count(project) == 1, "concurrent access leaves a reusable committed database");
        } finally {
            Files.writeString(project.resolve("release"), "release");
            first.get(15, TimeUnit.SECONDS);
            if (second != null) second.get(15, TimeUnit.SECONDS);
        }
    }

    private static void isolatesParallelModules(Path temporary) throws Exception {
        Path left = copyProject(temporary, "parallel-left", "hold");
        Path right = copyProject(temporary, "parallel-right", "hold");
        CompletableFuture<Throwable> first = attemptAsync(left);
        CompletableFuture<Throwable> second = attemptAsync(right);
        try {
            awaitFile(left.resolve("ready"));
            awaitFile(right.resolve("ready"));
            check(true, "independent modules can both execute before either releases its lock");
        } finally {
            Files.writeString(left.resolve("release"), "release");
            Files.writeString(right.resolve("release"), "release");
        }
        check(first.get(15, TimeUnit.SECONDS) == null && second.get(15, TimeUnit.SECONDS) == null,
                "parallel modules complete independently");
        check(Files.isRegularFile(left.resolve(DATABASE)) && Files.isRegularFile(right.resolve(DATABASE)),
                "parallel modules publish distinct databases");
    }

    private static void doesNotWriteDuringPlanning(Path temporary) throws Exception {
        Path project = copyProject(temporary, "dry-run", "copy");
        TaskRunner.run(TaskManifest.load(project.resolve("m3-build.tsv")), project, "copy", true,
                TIMEOUT, false, true, BACKEND, ignored -> {});
        check(!Files.exists(project.resolve(".m3")) && count(project) == 0, "planning creates no state and starts no tool");
        run(project, "copy", false);
        run(project, "copy", false);
        check(count(project) == 2, "disabled incremental mode always executes");
    }

    private static void rejectsNestedLinks(Path temporary) throws Exception {
        Path project = project(temporary, "nested-link");
        Files.createDirectories(project.resolve("source"));
        Files.writeString(project.resolve("source/regular.txt"), "regular");
        Path external = Files.writeString(temporary.resolve("linked-input.txt"), "outside");
        try {
            Files.createSymbolicLink(project.resolve("source/link.txt"), external);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            System.out.println("SKIP: nested symlink contract (host cannot create symbolic links)");
            return;
        }
        manifest(project, task("copy", "directory", "source", "-", "out", "runs.log", List.of("source")));
        expectFailure(() -> run(project, "copy", true), "a nested input symlink is not silently excluded from identity");
        check(count(project) == 0, "nested symlink refusal happens before tool invocation");
    }

    private static void resolvesWindowsExecutableSuffix(Path temporary) throws Exception {
        Path project = Files.createDirectory(temporary.resolve("windows-tool-resolution"));
        Path executable = Files.writeString(project.resolve("fixture.exe"), "not executed");
        String original = System.getProperty("os.name");
        try {
            // Exercise only the pure name resolver; no process is started with an altered OS property.
            System.setProperty("os.name", "Windows 11");
            check(executable.toString().equals(TaskRunner.resolveTool("fixture.exe", project)),
                    "an explicit Windows executable suffix is not appended a second time");
        } finally {
            if (original == null) System.clearProperty("os.name");
            else System.setProperty("os.name", original);
        }
    }

    private static Path project(Path temporary, String name) throws IOException {
        Path project = Files.createDirectory(temporary.resolve(name));
        Files.writeString(project.resolve("a.txt"), "one");
        Files.writeString(project.resolve("b.txt"), "two");
        return project;
    }

    private static Path copyProject(Path temporary, String name, String operation) throws Exception {
        Path project = project(temporary, name);
        manifest(project, task("copy", operation, "a.txt", "b.txt", "result.txt", "runs.log", List.of("a.txt", "b.txt")));
        return project;
    }

    private static String task(String name, String operation, String first, String second, String output,
                               String counter, List<String> inputs) throws URISyntaxException {
        StringBuilder body = new StringBuilder("task\t").append(name).append('\t').append(java()).append("\t.\n");
        for (String argument : List.of("-cp", classpath(), Tool.class.getName(), operation, counter, first, second, output)) {
            body.append("arg\t").append(name).append('\t').append(argument).append('\n');
        }
        for (String input : inputs) body.append("input\t").append(name).append('\t').append(input).append('\n');
        body.append("output\t").append(name).append('\t').append(output).append('\n');
        return body.toString();
    }

    private static void manifest(Path project, String body) throws IOException {
        Files.writeString(project.resolve("m3-build.tsv"), TaskManifest.MAGIC + "\n" + body);
    }

    private static void run(Path project, String target, boolean incremental) throws Exception {
        run(project, target, incremental, ignored -> {});
    }

    private static void run(Path project, String target, boolean incremental, java.util.function.Consumer<String> log)
            throws Exception {
        // The M3IndexDB backend is explicit; a nonincremental run uses the default backend and still
        // revokes an existing module database before it executes.
        TaskRunner.run(TaskManifest.load(project.resolve("m3-build.tsv")), project, target, false,
                TIMEOUT, false, incremental, incremental ? BACKEND : "legacy", log);
    }

    private static void invoke(Path project, String target, Map<String, String> environment) throws Exception {
        Path log = project.resolve("invocation.log");
        List<String> command = new ArrayList<>();
        command.add(java());
        for (String property : List.of("file.encoding", "user.language", "user.country")) {
            String value = System.getProperty(property);
            if (value != null) command.add("-D" + property + "=" + value);
        }
        command.addAll(List.of("-cp", classpath(), ModuleStateContractTestMain.class.getName(),
                "invoke", project.toString(), target, "true"));
        ProcessBuilder invocation = new ProcessBuilder(command);
        invocation.environment().putAll(environment);
        invocation.redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = invocation.start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("fresh JVM did not finish within the fixture budget");
        }
        check(process.exitValue() == 0, "fresh JVM completes: " + Files.readString(log));
    }

    private static CompletableFuture<Throwable> attemptAsync(Path project) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                run(project, "copy", true);
                return null;
            } catch (Throwable failure) {
                return failure;
            }
        });
    }

    private static void runAllowingChangedInputRefusal(Path project) throws Exception {
        try {
            run(project, "copy", true);
        } catch (IOException changedInput) {
            // Both a refusal and an explicitly uncached completion satisfy the storage contract.
        }
    }

    private static void awaitFile(Path file) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.isRegularFile(file)) {
            if (System.nanoTime() >= until) throw new AssertionError("fixture never created " + file);
            Thread.sleep(10);
        }
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java").toString();
    }

    private static String classpath() throws URISyntaxException {
        // exec-maven-plugin uses its own class loader; java.class.path alone does not include test classes.
        List<String> entries = new ArrayList<>();
        for (Class<?> type : List.of(ModuleStateContractTestMain.class, TaskRunner.class)) {
            String path = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            if (!entries.contains(path)) entries.add(path);
        }
        return String.join(File.pathSeparator, entries);
    }

    private static long count(Path project) throws IOException {
        return count(project, "runs.log");
    }

    private static long count(Path project, String filename) throws IOException {
        Path file = project.resolve(filename);
        return Files.exists(file) ? Files.readAllLines(file).size() : 0;
    }

    @FunctionalInterface
    private interface Attempt {
        void run() throws Exception;
    }

    private static void expectFailure(Attempt attempt, String description) throws Exception {
        try {
            attempt.run();
        } catch (IOException expected) {
            check(true, description);
            return;
        }
        throw new AssertionError(description);
    }

    private static void check(boolean accepted, String description) {
        if (!accepted) throw new AssertionError(description);
        checks++;
    }

    private static void erase(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }

    /** Portable child executable; task processes use Java directly and never invoke a shell. */
    public static final class Tool {
        private Tool() {}

        public static void main(String[] args) throws Exception {
            String operation = args[0];
            Files.writeString(Path.of(args[1]), "run\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if ("fail".equals(operation)) System.exit(7);
            if ("sleep".equals(operation)) Thread.sleep(5000);
            if ("hold".equals(operation)) {
                Files.writeString(Path.of("ready"), "ready");
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (!Files.exists(Path.of("release"))) {
                    if (System.nanoTime() >= until) throw new IOException("fixture release timeout");
                    Thread.sleep(10);
                }
            }
            Path first = Path.of(args[2]);
            Path output = Path.of(args[4]);
            if ("directory".equals(operation)) {
                erase(output);
                try (var files = Files.walk(first)) {
                    for (Path file : files.sorted().toList()) {
                        Path destination = output.resolve(first.relativize(file));
                        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(destination);
                        else Files.copy(file, destination);
                    }
                }
                return;
            }
            String a = Files.readString(first);
            String b = "-".equals(args[3]) ? "" : Files.readString(Path.of(args[3]));
            String value = a.length() + ":" + a + b.length() + ":" + b;
            if ("upper".equals(operation)) value = value.toUpperCase(java.util.Locale.ROOT);
            if ("environment".equals(operation)) value += System.getenv().getOrDefault(ENVIRONMENT, "");
            Files.writeString(output, value);
            if ("mutate".equals(operation)) Files.writeString(first, a + "!");
        }
    }
}
