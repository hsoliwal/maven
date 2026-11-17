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
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Sequential execution of a validated Ant/Make-like graph. Tasks invoke the
 * original compiler or build tool by argument vector, never through a shell.
 * There is no timestamp-only skipping or undeclared build cache. Explicit incremental execution
 * compares content stamps of declared inputs; with the "m3db" state backend it reuses successful
 * task generations from the module M3IndexDB after authenticating input, context and output content.
 */
public final class TaskRunner {
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
     * Opt-in incremental execution with the default "legacy" state backend: a task whose content stamp
     * (its definition and the bytes of its declared inputs, see {@link InputStamps}) equals the one
     * recorded at its last success, and whose declared outputs exist, is not started again.
     * Provider-owned and untracked work always executes. Existing overloads retain their defaults.
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
     * Per-module persistent state is opt-in: "legacy" preserves the original per-task stamps,
     * "file" (or "sqlite", "h2", "ext-*") uses one sealed state image per module, and "m3db" keeps
     * one {@code .m3/module.m3db} image of the owner {@code jdk.m3.indexdb.M3IndexDB}: reuse only
     * after verifying task, command, environment, Maven configuration, inputs, prerequisite
     * generations and output content. In-place, overlapping-output and provider-owned tasks execute.
     * Neither mode changes Maven's lifecycle or the explicit native-tool execution contract.
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
        if (!java.util.Set.of("legacy", "file", "m3db", "sqlite", "h2").contains(stateBackend)
                && (stateBackend == null || !stateBackend.matches("ext-[a-z0-9][a-z0-9-]{0,47}"))) {
            throw new IllegalArgumentException("unknown portable module state backend");
        }
        if (!incremental && !"legacy".equals(stateBackend)) {
            throw new IllegalArgumentException("module state DB requires incremental execution");
        }
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(timeoutPerTask, "timeoutPerTask");
        Objects.requireNonNull(log, "log");
        if (timeoutPerTask.isNegative() || timeoutPerTask.isZero() || timeoutPerTask.compareTo(Duration.ofHours(12)) > 0) {
            throw new IllegalArgumentException("timeout must be between 1ns and 12 hours");
        }
        Path root = workspace.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("polyglot workspace is not a directory: " + root);
        }
        root = root.toRealPath();
        List<TaskManifest.Task> tasks = plan.order(target);
        boolean m3db = incremental && "m3db".equals(stateBackend);
        boolean portable = incremental && !dryRun && !m3db && !"legacy".equals(stateBackend);
        // "m3db" persists through the owner jdk.m3.indexdb.M3IndexDB (M3DbModuleStore). Its module lock
        // covers validation, execution and publication. Any other run revokes an existing M3DB image
        // before launching, so a failed or foreign build never leaves an earlier M3DB success usable.
        try (M3ModuleDbRunLock moduleLock = incremental && !dryRun && !"legacy".equals(stateBackend)
                        ? M3ModuleDbRunLock.acquire(root) : null;
                M3DbModuleStore store = dryRun ? null
                        : m3db || Files.exists(root.resolve(M3DbModuleStore.FILE), LinkOption.NOFOLLOW_LINKS)
                        ? M3DbModuleStore.open(root, m3db, log) : null) {
            if (moduleLock != null) log.accept("M3DB module execution lock acquired");
            ModuleStateDb moduleState = portable ? ModuleStateDb.open(root, stateBackend) : null;
            return runTasks(plan, tasks, root, dryRun, timeoutPerTask, allowRewriteApply, incremental,
                    new State(m3db, store, moduleState), log);
        }
    }

    /** Selected persistent state: owner M3IndexDB image, portable sealed image, or legacy stamps. */
    private record State(boolean m3db, M3DbModuleStore store, ModuleStateDb moduleState) {}

    private static List<String> runTasks(TaskManifest plan, List<TaskManifest.Task> tasks,
            Path root, boolean dryRun, Duration timeout, boolean allowRewriteApply, boolean incremental,
            State state, Consumer<String> log) throws IOException, InterruptedException {
        M3DbModuleStore store = state.store();
        ModuleStateDb moduleState = state.moduleState();
        List<String> performed = new ArrayList<>(tasks.size());
        Map<String, String> generations = new HashMap<>();
        Set<String> overlappingOutputs = overlappingOutputs(plan);
        boolean cleared = false;
        for (TaskManifest.Task task : tasks) {
            log.accept((dryRun ? "PLAN " : "TASK ") + task.name() + " tool=" + task.tool()
                    + " prerequisites=" + task.prerequisites().size()
                    + " inputs=" + task.inputs().size() + " outputs=" + task.outputs().size());
            // Validate provider argument contracts even in a read-only plan.
            List<String> qualityCommand = QualityTaskCommands.supported(task.tool())
                    ? QualityTaskCommands.command(task, root, dryRun || allowRewriteApply) : null;
            List<String> m3Command = task.tool().startsWith("@m3")
                    ? M3ToolProviders.command(task, root) : null;
            if (dryRun) {
                performed.add(task.name());
                continue;
            }
            if ("-".equals(task.tool())) {
                aggregate(task, generations);
                performed.add(task.name());
                continue;
            }
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
            List<String> command = qualityCommand != null ? qualityCommand
                    : m3Command != null ? m3Command : ordinaryCommand(task, directory);
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).inheritIO();
            Map<String, String> environment = Map.copyOf(builder.environment());
            // Only explicitly declared, ordinary tool actions with both inputs and outputs
            // are candidates for reuse. CodeQL databases, OpenRewrite recipes and pluggable
            // providers own further hidden inputs and must execute normally.
            boolean ordinary = incremental && qualityCommand == null && m3Command == null
                    && !task.inputs().isEmpty() && !task.outputs().isEmpty();
            boolean candidate = state.m3db() && ordinary && InputStamps.separate(task)
                    && !overlappingOutputs.contains(task.name())
                    && task.prerequisites().stream().allMatch(generations::containsKey);
            String input = candidate ? InputStamps.stamp(task, root, command, environment, generations) : null;
            String[] prior = input == null ? null : store.lookup(task.name());
            if (prior != null && input.equals(prior[0])) {
                String output = existingOutputs(task, root);
                if (prior[1].equals(output)) {
                    generations.put(task.name(), InputStamps.combine(List.of(input, output)));
                    log.accept("UP-TO-DATE " + task.name() + " (M3DB input and output content verified)");
                    performed.add(task.name());
                    continue;
                }
            }
            String stamp = !ordinary || state.m3db() ? null
                    : moduleState == null ? InputStamps.stamp(task, root) : moduleState.stamp(task);
            if (stamp != null && (moduleState == null
                    ? InputStamps.upToDate(task, root, stamp) : moduleState.upToDate(task, stamp))) {
                log.accept("UP-TO-DATE " + task.name() + " (declared inputs unchanged, outputs present)");
                performed.add(task.name());
                continue;
            }
            // Revoke the old task and all consumers before tool effects begin.
            // A failure or crash must never leave an earlier success reusable.
            if (store != null && !state.m3db() && !cleared) {
                store.invalidateAll();
                cleared = true;
            } else if (store != null && state.m3db()) {
                invalidateDependents(plan, task.name(), store);
            }
            if (moduleState != null) moduleState.invalidateTransitive(plan, task.name());
            execute(task, builder, timeout);
            for (String output : task.outputs()) {
                if (!Files.exists(checked(root, output), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("missing declared output from " + task.name() + ": " + output);
                }
            }
            if (input != null) recordM3db(task, root, directory, input, environment, generations, store, log);
            if (stamp != null) recordStamp(task, root, stamp, moduleState, log);
            performed.add(task.name());
        }
        return List.copyOf(performed);
    }

    private static void recordM3db(TaskManifest.Task task, Path root, Path directory, String input,
            Map<String, String> environment, Map<String, String> generations, M3DbModuleStore store,
            Consumer<String> log) throws IOException {
        String after = null;
        String output = null;
        try {
            after = InputStamps.stamp(task, root, ordinaryCommand(task, directory), environment, generations);
            output = InputStamps.outputs(task, root);
        } catch (IOException changed) {
            // The original command succeeded. Unstable evidence grants no reuse,
            // but optional caching must not reclassify that success as compilation failure.
            log.accept("UNCACHED " + task.name() + " (input/output identity unavailable after execution)");
        }
        if (input.equals(after) && output != null) {
            store.record(task.name(), input, output);
            generations.put(task.name(), InputStamps.combine(List.of(input, output)));
        } else {
            log.accept("UNCACHED " + task.name() + " (inputs changed during execution)");
        }
    }

    private static void recordStamp(TaskManifest.Task task, Path root, String stamp, ModuleStateDb moduleState,
            Consumer<String> log) throws IOException {
        if (moduleState == null) {
            InputStamps.record(task, root, stamp);
            return;
        }
        try {
            String after = moduleState.stamp(task);
            if (stamp.equals(after)) {
                moduleState.record(task, stamp);
            } else {
                log.accept("UNCACHED " + task.name() + " (module inputs changed during execution)");
            }
        } catch (IOException unstable) {
            // A successful original tool is not reclassified as failure by optional cache recording.
            // Its old success was already durably revoked before starting the process.
            log.accept("UNCACHED " + task.name() + " (module state publication unavailable)");
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
