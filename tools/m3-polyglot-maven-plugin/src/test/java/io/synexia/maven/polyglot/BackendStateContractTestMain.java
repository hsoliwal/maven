/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** Public runner and CLI contracts across all preserved storage selections. */
public final class BackendStateContractTestMain {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static int checks;

    private BackendStateContractTestMain() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length > 0 && "invoke".equals(arguments[0])) {
            cli(Path.of(arguments[1]), arguments[2]);
            return;
        }
        if (arguments.length > 0 && "tool".equals(arguments[0])) {
            Files.writeString(Path.of("runs.log"), "run\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.writeString(Path.of("result.txt"), Files.readString(Path.of("source.txt")));
            if ("fail".equals(arguments[1])) System.exit(7);
            return;
        }
        Path temporary = Files.createTempDirectory("m3-backend-contract-");
        try {
            preservesPublicOverloads();
            defaultsToOriginalEmbeddedDatabase(temporary);
            preservesFileStorageAcrossProcesses(temporary);
            invalidatesFailureAcrossBackendChanges(temporary);
            invalidatesExplicitFileStateDuringDisabledRuns(temporary);
            refusesAbsentNativeRuntimeWithoutFallback(temporary);
            preservesOptionalPortableBackendSelection(temporary);
            serializesDifferentBackendsForOneModule(temporary);
            keepsLifecycleAndTaskRecordsDistinct(temporary);
            reservesEveryBackendStatePath(temporary);
            neverReusesDatabaseFilesAsTaskInputs(temporary);
            validatesBackendFlagsBeforeExecution(temporary);
            System.out.println("PASS: " + checks + " backend integration contract checks");
        } finally {
            try (var entries = Files.walk(temporary)) {
                for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
            }
        }
    }

    private static void preservesPublicOverloads() throws Exception {
        for (Class<?>[] signature : List.of(
                new Class<?>[] {TaskManifest.class, Path.class, String.class, boolean.class,
                        Duration.class, Consumer.class},
                new Class<?>[] {TaskManifest.class, Path.class, String.class, boolean.class,
                        Duration.class, boolean.class, Consumer.class},
                new Class<?>[] {TaskManifest.class, Path.class, String.class, boolean.class,
                        Duration.class, boolean.class, boolean.class, Consumer.class},
                new Class<?>[] {TaskManifest.class, Path.class, String.class, boolean.class,
                        Duration.class, boolean.class, boolean.class, String.class, Consumer.class})) {
            var method = TaskRunner.class.getMethod("run", signature);
            check(method.getReturnType() == List.class
                            && List.of(method.getExceptionTypes()).equals(List.of(IOException.class, InterruptedException.class)),
                    "existing run overload retains return type and declared exceptions");
        }
    }

    private static void defaultsToOriginalEmbeddedDatabase(Path temporary) throws Exception {
        Path project = project(temporary, "default");
        cli(project, null);
        check(count(project) == 1, "default incremental run executes once");
        check(Files.isRegularFile(project.resolve(ModuleStateDb.EMBEDDED_FILE)), "default owns a module M3DB archive");
        try (ZipFile archive = new ZipFile(project.resolve(ModuleStateDb.EMBEDDED_FILE).toFile())) {
            check(archive.stream().anyMatch(entry -> entry.getName().contains("refs/")),
                    "default archive contains the original M3IndexDB reference format");
        }
        check(!Files.exists(project.resolve(ModuleStateDb.FILE)), "default does not substitute sealed file backend");
        check(!Files.exists(project.resolve(".m3/polyglot-stamps")), "default does not publish legacy loose stamps");
        check(cli(project, "embedded").stream().anyMatch(line -> line.startsWith("UP-TO-DATE compile")),
                "explicit embedded shares the default committed generation");
        check(cli(project, "legacy").stream().anyMatch(line -> line.startsWith("UP-TO-DATE compile")),
                "legacy is the documented embedded compatibility alias");
        check(count(project) == 1, "alias selection does not rerun unchanged work");
    }

    private static void preservesFileStorageAcrossProcesses(Path temporary) throws Exception {
        Path project = project(temporary, "file");
        cli(project, "file");
        check(Files.isRegularFile(project.resolve(ModuleStateDb.FILE)), "explicit file storage is retained");
        check(!Files.exists(project.resolve(ModuleStateDb.EMBEDDED_FILE)), "file mode does not silently use embedded storage");
        invoke(project, "file");
        check(count(project) == 1, "a fresh JVM reuses the file backend's committed success");
        Files.writeString(project.resolve("result.txt"), "changed output");
        cli(project, "file");
        check(count(project) == 2 && Files.readString(project.resolve("result.txt")).equals("source"),
                "file backend authenticates present output contents");
        cli(project, "embedded");
        check(count(project) == 3, "changing backend starts a fresh successful generation");
        cli(project, "file");
        check(count(project) == 4, "returning to an old backend cannot revive its earlier receipt");
    }

    private static void invalidatesFailureAcrossBackendChanges(Path temporary) throws Exception {
        for (String[] selection : List.of(new String[] {"file", "embedded"}, new String[] {"embedded", "file"})) {
            Path project = project(temporary, "failure-" + selection[0]);
            cli(project, selection[0]);
            manifest(project, "compile", "fail", "result.txt");
            expectIo(() -> cli(project, selection[1]), "failed task is reported when switching backends");
            check(count(project) == 2 && Files.readString(project.resolve("result.txt")).equals("source"),
                    "failed process produced bytes matching the earlier successful output");
            manifest(project, "compile", "copy", "result.txt");
            cli(project, selection[0]);
            check(count(project) == 3, "backend switch after failure cannot revive matching old input/output evidence");
            cli(project, selection[0]);
            check(count(project) == 3, "a newly complete success remains reusable after recovery");
        }
    }

    private static void invalidatesExplicitFileStateDuringDisabledRuns(Path temporary) throws Exception {
        Path project = project(temporary, "disabled");
        cli(project, "file");
        manifest(project, "compile", "fail", "result.txt");
        expectIo(() -> TaskRunner.run(TaskManifest.load(project.resolve("m3-build.tsv")), project,
                "compile", false, TIMEOUT, false, false, line -> {}),
                "disabled incremental run still reports task failure");
        manifest(project, "compile", "copy", "result.txt");
        cli(project, "file");
        check(count(project) == 3, "default nonincremental failure revokes explicit file-backend evidence");
    }

    private static void refusesAbsentNativeRuntimeWithoutFallback(Path temporary) throws Exception {
        // The portable contract classpath deliberately contains no optional native MIndexDB runtime.
        try {
            Class.forName("com.synexia.mindex.db.MIndexDB");
            throw new AssertionError("native refusal fixture requires an isolated classpath");
        } catch (ClassNotFoundException absent) {
            // This is a missing-runtime contract, not proof of the native implementation itself.
        }
        for (String backend : List.of("m3db", "native")) {
            Path project = project(temporary, "native-" + backend);
            expectIo(() -> cli(project, backend), "explicit native selection fails when its runtime is unavailable");
            check(count(project) == 0, "missing native runtime cannot execute the task through another backend");
            check(!Files.exists(project.resolve(ModuleStateDb.EMBEDDED_FILE))
                            && !Files.exists(project.resolve(ModuleStateDb.FILE)),
                    "missing native runtime never silently publishes another backend");
        }
    }

    private static void preservesOptionalPortableBackendSelection(Path temporary) throws Exception {
        for (String[] option : List.of(new String[] {"sqlite", "org.sqlite.JDBC"},
                new String[] {"h2", "org.h2.Driver"},
                new String[] {"hsqldb", "org.hsqldb.jdbc.JDBCDriver"},
                new String[] {"ext-unavailable-contract-v1", ""})) {
            String backend = option[0];
            Path project = project(temporary, "portable-" + backend);
            List<String> plan = TaskRunner.run(TaskManifest.load(project.resolve("m3-build.tsv")), project,
                    "compile", true, TIMEOUT, false, true, backend, line -> {});
            check(plan.equals(List.of("compile")), "public dry-run API accepts the preserved portable backend identifier");
            check(count(project) == 0 && !Files.exists(project.resolve(".m3")),
                    "planning does not initialize an optional driver or storage provider");
            boolean available = false;
            if (!option[1].isEmpty()) {
                try {
                    Class.forName(option[1], false, Thread.currentThread().getContextClassLoader());
                    available = true;
                } catch (ClassNotFoundException missing) {
                    // Absence is qualified below; a present real driver exercises the actual CLI path.
                }
            }
            if (available) {
                cli(project, backend);
                check(cli(project, backend).stream().anyMatch(line -> line.startsWith("UP-TO-DATE compile"))
                                && count(project) == 1,
                        "a present authentic JDBC driver retains public CLI reuse through the common runner");
            } else {
                expectIo(() -> cli(project, backend), "missing portable driver/provider fails after valid CLI selection");
                check(count(project) == 0, "missing portable storage never starts the original tool");
            }
            check(!Files.exists(project.resolve(ModuleStateDb.EMBEDDED_FILE))
                            && !Files.exists(project.resolve(ModuleStateDb.FILE)),
                    "explicit JDBC or SPI selection never publishes an embedded/file fallback");
        }
    }

    private static void serializesDifferentBackendsForOneModule(Path temporary) throws Exception {
        Path project = project(temporary, "lock");
        try (ModuleStateDb held = ModuleStateDb.open(project, "embedded", true, line -> {})) {
            check(held.lookup("unrecorded") == null, "owning module session starts without a receipt");
            expectIo(() -> cli(project, "file"), "another backend cannot bypass the shared module lifetime lock");
            check(count(project) == 0, "lock refusal precedes tool execution");
        }
        cli(project, "file");
        check(count(project) == 1, "different backend can run after the owning session releases the lock");
    }

    private static void keepsLifecycleAndTaskRecordsDistinct(Path temporary) throws Exception {
        Path project = project(temporary, "namespaces");
        String target = "lifecycle-" + "a".repeat(64);
        manifest(project, target, "copy", "result.txt");
        try (ModuleStateDb state = ModuleStateDb.open(project, "embedded", true, line -> {})) {
            state.record(target, "b".repeat(64), "c".repeat(64));
        }
        TaskRunner.run(TaskManifest.load(project.resolve("m3-build.tsv")), project,
                target, false, TIMEOUT, false, true, "embedded", line -> {});
        try (ModuleStateDb state = ModuleStateDb.open(project, "embedded", true, line -> {})) {
            check(state.lookup(target)[0].equals("b".repeat(64)), "task name cannot overwrite a lifecycle receipt");
            check(state.lookup("task-" + InputStamps.combine(List.of(target))) != null,
                    "task result has its own bounded namespace");
        }
    }

    private static void reservesEveryBackendStatePath(Path temporary) throws Exception {
        int ordinal = 0;
        for (String output : List.of(ModuleStateDb.EMBEDDED_FILE, ModuleStateDb.FILE,
                ".m3/module.m3db.lock", ".m3/module.m3db.state", ".m3/module-state.lock",
                ".m3/module-state.run.lock", ".m3/module-state.sqlite", ".m3/module-state.sqlite-journal",
                ".m3/module-state.sqlite-wal", ".m3/module-state.sqlite-shm", ".m3/module-state.mv.db",
                ".m3/module-state.lock.db", ".m3/module-state.trace.db", ".m3/module-state.temp.db",
                ".m3/maven-build-db/artifact", ".m3/polyglot-stamps/compile.sha256")) {
            Path project = project(temporary, "reserved-" + ordinal++);
            manifest(project, "compile", "copy", output);
            expectIo(() -> cli(project, "embedded"), "task output cannot overwrite module coordination or state");
            check(count(project) == 0, "reserved output is rejected before tool invocation");
        }
    }

    private static void neverReusesDatabaseFilesAsTaskInputs(Path temporary) throws Exception {
        int ordinal = 0;
        for (String input : List.of(".m3/module-state.sqlite-journal", ".m3/module-state.mv.db",
                ".m3/module-state.run.lock")) {
            Path project = project(temporary, "state-input-" + ordinal++);
            Files.createDirectories(project.resolve(".m3"));
            Files.writeString(project.resolve(input), "coordination fixture\n");
            Files.writeString(project.resolve("m3-build.tsv"), "input\tcompile\t" + input + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            cli(project, "embedded");
            List<String> second = cli(project, "embedded");
            check(count(project) == 2 && second.stream().noneMatch(line -> line.startsWith("UP-TO-DATE ")),
                    "declaring a database or coordination file as an input keeps ordinary work uncached");
        }
    }

    private static void validatesBackendFlagsBeforeExecution(Path temporary) throws Exception {
        Path project = project(temporary, "flags");
        for (String[] suffix : List.of(
                new String[] {"--state-backend", "embedded"},
                new String[] {"--incremental", "--state-backend"},
                new String[] {"--incremental", "--state-backend", "unknown"},
                new String[] {"--incremental", "--state-backend", "ext-"},
                new String[] {"--incremental", "--state-backend", "ext-UPPER"},
                new String[] {"--incremental", "--state-backend", "ext-unsafe/path"},
                new String[] {"--incremental", "--state-backend", "ext-" + "a".repeat(49)},
                new String[] {"--incremental", "--state-backend", "file", "--state-backend", "embedded"})) {
            List<String> arguments = new ArrayList<>(List.of("run", project.toString(),
                    project.resolve("m3-build.tsv").toString(), "compile"));
            arguments.addAll(List.of(suffix));
            boolean refused = false;
            try {
                PolyglotCli.execute(arguments.toArray(String[]::new), line -> {});
            } catch (IllegalArgumentException expected) {
                refused = true;
            }
            check(refused, "malformed backend option is refused");
        }
        check(count(project) == 0 && !Files.exists(project.resolve(".m3")),
                "invalid CLI options neither run work nor create module state");
    }

    private static Path project(Path temporary, String name) throws Exception {
        Path project = Files.createDirectories(temporary.resolve(name));
        Files.writeString(project.resolve("source.txt"), "source");
        manifest(project, "compile", "copy", "result.txt");
        return project;
    }

    private static void manifest(Path project, String name, String operation, String output) throws Exception {
        StringBuilder manifest = new StringBuilder(TaskManifest.MAGIC).append('\n')
                .append("task\t").append(name).append('\t').append(javaExecutable()).append("\t.\n");
        for (String argument : List.of("-cp", classpath(), BackendStateContractTestMain.class.getName(), "tool", operation)) {
            manifest.append("arg\t").append(name).append('\t').append(argument).append('\n');
        }
        manifest.append("input\t").append(name).append("\tsource.txt\n")
                .append("output\t").append(name).append('\t').append(output).append('\n');
        Files.writeString(project.resolve("m3-build.tsv"), manifest);
    }

    private static List<String> cli(Path project, String backend) throws Exception {
        List<String> arguments = new ArrayList<>(List.of("run", project.toString(),
                project.resolve("m3-build.tsv").toString(), "compile", "--incremental"));
        if (backend != null) arguments.addAll(List.of("--state-backend", backend));
        List<String> output = new ArrayList<>();
        PolyglotCli.execute(arguments.toArray(String[]::new), output::add);
        return output;
    }

    private static void invoke(Path project, String backend) throws Exception {
        Process process = new ProcessBuilder(javaExecutable(), "-cp", classpath(),
                BackendStateContractTestMain.class.getName(), "invoke", project.toString(), backend)
                .redirectErrorStream(true).redirectOutput(project.resolve("child.log").toFile()).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("child JVM timed out");
        }
        if (process.exitValue() != 0) {
            throw new AssertionError("child JVM failed: " + Files.readString(project.resolve("child.log")));
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java").toString();
    }

    private static String classpath() throws URISyntaxException {
        List<String> entries = new ArrayList<>();
        for (Class<?> owner : List.of(BackendStateContractTestMain.class, TaskRunner.class)) {
            String entry = Path.of(owner.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            if (!entries.contains(entry)) entries.add(entry);
        }
        return String.join(File.pathSeparator, entries);
    }

    private static int count(Path project) throws IOException {
        Path counter = project.resolve("runs.log");
        return Files.exists(counter) ? Files.readAllLines(counter).size() : 0;
    }

    private static void expectIo(Checked action, String message) throws Exception {
        boolean failed = false;
        try {
            action.run();
        } catch (IOException expected) {
            failed = true;
        }
        check(failed, message);
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
