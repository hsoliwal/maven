/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Sequential execution of a validated Ant/Make-like graph. Tasks invoke the
 * original compiler or build tool by argument vector, never through a shell.
 * There is deliberately no timestamp-only skipping or undeclared build cache. Opt-in incremental
 * execution compares content stamps of declared inputs ("legacy"), one sealed module image
 * ("file"/"m3db"), or the module M3IndexDB after authenticating declared input, context and
 * output content ("indexdb").
 */
public final class TaskRunner {
    /** Accepted incremental state backends; "legacy" stays the default. */
    static final Set<String> BACKENDS = Set.of("legacy", "file", "m3db", "indexdb");

    private TaskRunner() {}

    /**
     * Executes selected tasks or reports a dry-run plan. Each successful tool
     * executes exactly once after its declared prerequisites. An aggregation
     * target uses tool '-' and causes no process invocation.
     */
    public static List<String> run(
            TaskManifest plan,
            Path workspace,
            String target,
            boolean dryRun,
            Duration timeoutPerTask,
            Consumer<String> log) throws IOException, InterruptedException {
        return run(plan, workspace, target, dryRun, timeoutPerTask, false, log);
    }

    /** Opt-in recipe mutation policy, separate from ordinary build execution. */
    public static List<String> run(
            TaskManifest plan,
            Path workspace,
            String target,
            boolean dryRun,
            Duration timeoutPerTask,
            boolean allowRewriteApply,
            Consumer<String> log) throws IOException, InterruptedException {
        return run(plan, workspace, target, dryRun, timeoutPerTask, allowRewriteApply, false, log);
    }

    /**
     * Opt-in incremental execution: a task whose content stamp (its definition and the bytes of its declared
     * inputs, see {@link InputStamps}) equals the one recorded at its last success, and whose declared outputs
     * exist, is not started again. Declare an upstream task's outputs as inputs to have its changes re-run this one.
     */
    public static List<String> run(
            TaskManifest plan,
            Path workspace,
            String target,
            boolean dryRun,
            Duration timeoutPerTask,
            boolean allowRewriteApply,
            boolean incremental,
            Consumer<String> log) throws IOException, InterruptedException {
        return run(plan, workspace, target, dryRun, timeoutPerTask, allowRewriteApply, incremental, "legacy", log);
    }

    /**
     * When both incremental and moduleDb are explicit, one per-module M3DB ({@code .m3/module.m3db},
     * the "indexdb" backend) replaces per-task stamps. All existing entry points and the default Maven
     * lifecycle retain their earlier behavior.
     */
    public static List<String> run(
            TaskManifest plan,
            Path workspace,
            String target,
            boolean dryRun,
            Duration timeoutPerTask,
            boolean allowRewriteApply,
            boolean incremental,
            boolean moduleDb,
            Consumer<String> log) throws IOException, InterruptedException {
        if (moduleDb && !incremental) throw new IllegalArgumentException("M3DB requires explicit incremental mode");
        return run(plan, workspace, target, dryRun, timeoutPerTask, allowRewriteApply, incremental,
                moduleDb ? "indexdb" : "legacy", log);
    }

    /**
     * Per-module persistent state is opt-in: "legacy" preserves the original per-task stamps,
     * "file" uses one sealed state file per module, "m3db" stores that image through {@link IndexStore},
     * and "indexdb" keeps one M3IndexDB receipt archive per module with revocation before execution.
     * No mode changes Maven's lifecycle or the explicit native-tool execution contract.
     */
    public static List<String> run(
            TaskManifest plan,
            Path workspace,
            String target,
            boolean dryRun,
            Duration timeoutPerTask,
            boolean allowRewriteApply,
            boolean incremental,
            String stateBackend,
            Consumer<String> log) throws IOException, InterruptedException {
        checkBackend(incremental, stateBackend);
        requireArguments(plan, workspace, target, timeoutPerTask, log);
        Path root = workspaceRoot(workspace);
        List<TaskManifest.Task> tasks = plan.order(target);
        Execution execution = new Execution(plan, root, dryRun, timeoutPerTask, allowRewriteApply,
                incremental, stateBackend, log);
        // Validation/execution/publication share this lock. A write-only lock cannot
        // prevent one Maven process from reusing another process's unfinished outputs.
        try (M3DbModuleStore store = openStore(root, dryRun, incremental, stateBackend, log)) {
            execution.store = store;
            execution.moduleState = incremental && !dryRun && !"legacy".equals(stateBackend)
                    && !"indexdb".equals(stateBackend) ? ModuleStateDb.open(root, stateBackend) : null;
            return execution.all(tasks);
        }
    }

    private static void checkBackend(boolean incremental, String stateBackend) {
        if (!BACKENDS.contains(stateBackend)) {
            throw new IllegalArgumentException("state backend must be legacy, file, m3db or indexdb");
        }
        if (!incremental && !"legacy".equals(stateBackend)) {
            throw new IllegalArgumentException("module state DB requires incremental execution");
        }
    }

    private static void requireArguments(TaskManifest plan, Path workspace, String target, Duration timeout,
            Consumer<String> log) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(timeout, "timeoutPerTask");
        Objects.requireNonNull(log, "log");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofHours(12)) > 0) {
            throw new IllegalArgumentException("timeout must be between 1ns and 12 hours");
        }
    }

    private static Path workspaceRoot(Path workspace) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("polyglot workspace is not a directory: " + root);
        }
        return root.toRealPath();
    }

    /**
     * The module M3IndexDB is opened for the indexdb backend, and for any other real run of a module that
     * already holds one, so that an older success is revoked before a task mutates outputs. A module
     * without the database and without the indexdb backend creates no state here.
     */
    private static M3DbModuleStore openStore(Path root, boolean dryRun, boolean incremental, String backend,
            Consumer<String> log) throws IOException {
        if (dryRun) return null;
        boolean indexDb = "indexdb".equals(backend);
        if (!indexDb && !Files.exists(root.resolve(M3DbModuleStore.FILE), LinkOption.NOFOLLOW_LINKS)) return null;
        return M3DbModuleStore.open(root, incremental && indexDb, log);
    }

    /** Reuse decision of one task: the input stamp to publish after success, and whether it is current. */
    private record Receipt(String stamp, boolean upToDate) {
        static final Receipt NONE = new Receipt(null, false);
    }

    /** One module invocation; each method is one step of the original sequential loop. */
    private static final class Execution {
        private final TaskManifest plan;
        private final Path root;
        private final boolean dryRun;
        private final Duration timeout;
        private final boolean allowRewriteApply;
        private final boolean incremental;
        private final boolean indexDb;
        private final Consumer<String> log;
        private final Map<String, String> generations = new HashMap<>();
        private final Set<String> overlappingOutputs;
        private M3DbModuleStore store;
        private ModuleStateDb moduleState;
        private boolean cleared;

        Execution(TaskManifest plan, Path root, boolean dryRun, Duration timeout, boolean allowRewriteApply,
                boolean incremental, String backend, Consumer<String> log) {
            this.plan = plan;
            this.root = root;
            this.dryRun = dryRun;
            this.timeout = timeout;
            this.allowRewriteApply = allowRewriteApply;
            this.incremental = incremental;
            this.indexDb = "indexdb".equals(backend);
            this.log = log;
            this.overlappingOutputs = overlappingOutputs(plan);
        }

        List<String> all(List<TaskManifest.Task> tasks) throws IOException, InterruptedException {
            List<String> performed = new ArrayList<>(tasks.size());
            for (TaskManifest.Task task : tasks) {
                one(task);
                performed.add(task.name());
            }
            return List.copyOf(performed);
        }

        private void one(TaskManifest.Task task) throws IOException, InterruptedException {
            log.accept((dryRun ? "PLAN " : "TASK ") + task.name() + " tool=" + task.tool()
                    + " prerequisites=" + task.prerequisites().size()
                    + " inputs=" + task.inputs().size() + " outputs=" + task.outputs().size());
            // Validate provider argument contracts even in a read-only plan.
            List<String> quality = QualityTaskCommands.supported(task.tool())
                    ? QualityTaskCommands.command(task, root, dryRun || allowRewriteApply) : null;
            List<String> provider = task.tool().startsWith("@m3") ? M3ToolProviders.command(task, root) : null;
            if (dryRun) return;
            if ("-".equals(task.tool())) {
                aggregate(task, generations);
                return;
            }
            perform(task, quality, provider);
        }

        private void perform(TaskManifest.Task task, List<String> quality, List<String> provider)
                throws IOException, InterruptedException {
            Path directory = checkDeclarations(task);
            // Only explicitly declared, ordinary tool actions are reuse candidates. CodeQL databases,
            // OpenRewrite recipes and pluggable providers own further hidden inputs and always execute.
            boolean ordinary = quality == null && provider == null;
            List<String> command = quality != null ? quality : provider != null ? provider
                    : ordinaryCommand(task, directory);
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).inheritIO();
            Map<String, String> environment = Map.copyOf(builder.environment());
            Receipt receipt = indexDb ? indexReceipt(task, ordinary, command, environment) : stampReceipt(task, ordinary);
            if (receipt.upToDate()) {
                log.accept("UP-TO-DATE " + task.name() + (indexDb ? " (M3DB input and output content verified)"
                        : " (declared inputs unchanged, outputs present)"));
                return;
            }
            revoke(task);
            execute(task, builder, timeout);
            requireOutputs(task);
            if (receipt.stamp() != null) publish(task, receipt.stamp(), directory, environment);
        }

        private Path checkDeclarations(TaskManifest.Task task) throws IOException {
            Path directory = checked(root, task.workingDir());
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("working directory does not exist for target " + task.name());
            }
            for (String input : task.inputs()) {
                if (!Files.exists(checked(root, input), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("missing declared input for " + task.name() + ": " + input);
                }
            }
            for (String output : task.outputs()) {
                checked(root, output);
                if (InputStamps.statePath(output)) {
                    throw new IOException("declared output overlaps Maven module database: " + output);
                }
            }
            return directory;
        }

        /** Master's content stamps: legacy per-task files, or the sealed "file"/"m3db" module image. */
        private Receipt stampReceipt(TaskManifest.Task task, boolean ordinary) throws IOException {
            if (!incremental || !ordinary || task.inputs().isEmpty() || task.outputs().isEmpty()) return Receipt.NONE;
            String stamp = moduleState == null ? InputStamps.stamp(task, root) : moduleState.stamp(task);
            boolean current = moduleState == null
                    ? InputStamps.upToDate(task, root, stamp) : moduleState.upToDate(task, stamp);
            return new Receipt(stamp, current);
        }

        /** The module M3IndexDB: authenticated input, prerequisite generations and exact output content. */
        private Receipt indexReceipt(TaskManifest.Task task, boolean ordinary, List<String> command,
                Map<String, String> environment) throws IOException {
            if (!indexCandidate(task, ordinary)) return Receipt.NONE;
            String input = InputStamps.stamp(task, root, command, environment, generations);
            String[] prior = store.lookup(task.name());
            if (prior == null || !input.equals(prior[0])) return new Receipt(input, false);
            String output = existingOutputs(task, root);
            if (!prior[1].equals(output)) return new Receipt(input, false);
            generations.put(task.name(), InputStamps.combine(List.of(input, output)));
            return new Receipt(input, true);
        }

        private boolean indexCandidate(TaskManifest.Task task, boolean ordinary) {
            return incremental && ordinary && !task.inputs().isEmpty() && !task.outputs().isEmpty()
                    && InputStamps.separate(task) && !overlappingOutputs.contains(task.name())
                    && task.prerequisites().stream().allMatch(generations::containsKey);
        }

        /**
         * Revoke old success before launching, including an explicit non-incremental build. A failure or
         * crash must never leave an earlier success reusable; a failed revocation stops the task.
         */
        private void revoke(TaskManifest.Task task) throws IOException {
            if (store == null) return;
            if (indexDb && incremental) {
                invalidateDependents(plan, task.name(), store);
            } else if (!cleared) {
                store.invalidateAll();
                cleared = true;
            }
        }

        private void requireOutputs(TaskManifest.Task task) throws IOException {
            for (String output : task.outputs()) {
                if (!Files.exists(checked(root, output), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("missing declared output from " + task.name() + ": " + output);
                }
            }
        }

        private void publish(TaskManifest.Task task, String stamp, Path directory, Map<String, String> environment)
                throws IOException {
            if (!indexDb) {
                if (moduleState == null) InputStamps.record(task, root, stamp);
                else moduleState.record(task, stamp);
                return;
            }
            String[] after = verifiedAfter(task, directory, environment);
            if (after != null && stamp.equals(after[0])) {
                recordIndex(task, stamp, after[1]);
                generations.put(task.name(), InputStamps.combine(List.of(stamp, after[1])));
            } else {
                log.accept("UNCACHED " + task.name() + " (inputs changed during execution)");
            }
        }

        /**
         * The original command succeeded. Unstable evidence grants no reuse, but optional caching must
         * not reclassify that success as compilation failure.
         */
        private String[] verifiedAfter(TaskManifest.Task task, Path directory, Map<String, String> environment) {
            try {
                return new String[] {
                    InputStamps.stamp(task, root, ordinaryCommand(task, directory), environment, generations),
                    InputStamps.outputs(task, root)
                };
            } catch (IOException changed) {
                log.accept("UNCACHED " + task.name() + " (input/output identity unavailable after execution)");
                return null;
            }
        }

        /** Persistence is an optimization; a successful original build stays successful (a later miss). */
        private void recordIndex(TaskManifest.Task task, String input, String output) {
            try {
                store.record(task.name(), input, output);
            } catch (IOException unavailable) {
                log.accept("M3DB NOT-RECORDED " + task.name() + " (" + unavailable.getMessage() + ")");
            }
        }
    }

    private static List<String> ordinaryCommand(TaskManifest.Task task, Path directory) {
        List<String> command = new ArrayList<>(task.arguments().size() + 1);
        command.add(resolveTool(task.tool(), directory));
        command.addAll(task.arguments());
        return command;
    }

    private static String existingOutputs(TaskManifest.Task task, Path root) throws IOException {
        try {
            return InputStamps.outputs(task, root);
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    private static void aggregate(TaskManifest.Task task, Map<String, String> generations) {
        if (!task.inputs().isEmpty() || !task.outputs().isEmpty()
                || !task.prerequisites().stream().allMatch(generations::containsKey)) return;
        List<String> values = new ArrayList<>();
        values.add(task.name());
        for (String dependency : task.prerequisites().stream().sorted().toList()) {
            values.add(dependency);
            values.add(generations.get(dependency));
        }
        generations.put(task.name(), InputStamps.combine(values));
    }

    /** Cross-task directory/file ownership overlap prevents a stable generation claim. */
    private static Set<String> overlappingOutputs(TaskManifest plan) {
        Map<String, String> owners = new HashMap<>();
        for (TaskManifest.Task task : plan.orderAll()) {
            for (String output : task.outputs()) owners.put(output, task.name());
        }
        Set<String> overlapping = new HashSet<>();
        for (Map.Entry<String, String> output : owners.entrySet()) {
            String path = output.getKey();
            for (int slash = path.lastIndexOf('/'); slash >= 0; slash = path.lastIndexOf('/', slash - 1)) {
                String ancestor = owners.get(path.substring(0, slash));
                if (ancestor != null && !ancestor.equals(output.getValue())) {
                    overlapping.add(ancestor);
                    overlapping.add(output.getValue());
                }
            }
        }
        return overlapping;
    }

    private static void invalidateDependents(TaskManifest plan, String task, M3DbModuleStore store) throws IOException {
        Map<String, List<String>> consumers = new HashMap<>();
        for (TaskManifest.Task node : plan.orderAll()) {
            for (String dependency : node.prerequisites()) {
                consumers.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(node.name());
            }
        }
        Set<String> invalid = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(task);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (invalid.add(name)) pending.addAll(consumers.getOrDefault(name, List.of()));
        }
        store.invalidate(invalid);
    }

    private static void execute(TaskManifest.Task task, ProcessBuilder builder, Duration timeout)
            throws IOException, InterruptedException {
        final Process process;
        try {
            process = builder.start();
        } catch (IOException failure) {
            throw new IOException("cannot start tool for target " + task.name() + ": " + task.tool(), failure);
        }
        final boolean finished;
        try {
            finished = process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupt) {
            stop(process);
            Thread.currentThread().interrupt();
            throw interrupt;
        }
        if (!finished) {
            stop(process);
            throw new IOException("polyglot target timed out: " + task.name());
        }
        if (process.exitValue() != 0) {
            throw new IOException("polyglot tool failed: " + task.name() + " (exit " + process.exitValue() + ")");
        }
    }

    /**
     * A bare tool name (no path separator) is looked up in the task's working directory first (wrappers such as
     * gradlew), then on PATH; on Windows with the PATHEXT extensions, so a portable manifest can name npm or
     * gradle and still start npm.cmd or gradle.bat. Anything else, or a name not found, is passed unchanged.
     */
    static String resolveTool(String tool, Path directory) {
        if (tool.indexOf('/') >= 0 || tool.indexOf('\\') >= 0) return tool;
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        // Windows starts only files with an executable extension (a bare "npm" next to npm.cmd is a shell script).
        List<String> extensions = new ArrayList<>();
        if (windows) {
            for (String extension : System.getenv().getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD").split(";")) {
                if (!extension.isBlank()) extensions.add(extension.toLowerCase(java.util.Locale.ROOT));
            }
            String lower = tool.toLowerCase(java.util.Locale.ROOT);
            if (extensions.stream().anyMatch(lower::endsWith)) extensions.add(0, "");
        } else {
            extensions.add("");
        }
        List<Path> folders = new ArrayList<>();
        folders.add(directory);
        for (String entry : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                try {
                    folders.add(Path.of(entry));
                } catch (java.nio.file.InvalidPathException ignored) {
                    // An unusable PATH entry is skipped.
                }
            }
        }
        for (Path folder : folders) {
            for (String extension : extensions) {
                Path absoluteFolder = folder.isAbsolute() ? folder : directory.resolve(folder);
                Path candidate = absoluteFolder.resolve(tool + extension).normalize();
                if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) return candidate.toString();
            }
        }
        return tool;
    }

    /** Rejects lexical escapes and existing symlinks in the path to a declared input/output. */
    private static Path checked(Path root, String relative) throws IOException {
        return InputStamps.checked(root, relative);
    }

    private static void stop(Process process) {
        process.descendants().forEach(child -> {
            try {
                child.destroyForcibly();
            } catch (RuntimeException ignored) {
                // The owning process is also stopped below.
            }
        });
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupt) {
            Thread.currentThread().interrupt();
        }
    }
}
