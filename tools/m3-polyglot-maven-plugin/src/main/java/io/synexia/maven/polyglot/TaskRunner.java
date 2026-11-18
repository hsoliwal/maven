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
 * Explicit incremental execution reuses successful task generations from the module
 * M3IndexDB after authenticating declared input, context and output content.
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
     * Opt-in incremental execution with one persistent M3DB file per module.
     * Only ordinary deterministic tasks with complete declared inputs and outputs
     * may reuse a success. Provider-owned, in-place and untracked dependency work
     * always executes. Existing overloads retain their non-incremental default.
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
        return run(plan, workspace, target, dryRun, timeoutPerTask, allowRewriteApply,
                incremental, "embedded", log);
    }

    /**
     * Select persistent storage without changing the successful-result contract.
     * "embedded" uses the original M3IndexDB inside one module archive; "legacy"
     * is its compatibility alias. "file" retains the sealed binary image and
     * "m3db"/"native" retain the optional native MIndexDB integration without fallback.
     * "sqlite"/"h2" retain local JDBC storage; explicit "ext-*" IDs use the public storage SPI.
     * A nonincremental execution revokes earlier reusable state before launching work.
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
        return run(plan, workspace, target, dryRun, timeoutPerTask, allowRewriteApply,
                incremental, stateBackend, null, log);
    }

    /** Explicit output-cache opt-in; the caller declares that the task's outputs are relocatable. */
    public static List<String> run(
            TaskManifest plan,
            Path workspace,
            String target,
            boolean dryRun,
            Duration timeoutPerTask,
            boolean allowRewriteApply,
            boolean incremental,
            Path buildCache,
            Consumer<String> log) throws IOException, InterruptedException {
        return run(plan, workspace, target, dryRun, timeoutPerTask, allowRewriteApply,
                incremental, "embedded", buildCache, log);
    }

    /** Preserve both upstream opt-ins: verified local receipts and explicitly requested output restoration. */
    public static List<String> run(
            TaskManifest plan,
            Path workspace,
            String target,
            boolean dryRun,
            Duration timeoutPerTask,
            boolean allowRewriteApply,
            boolean incremental,
            String stateBackend,
            Path buildCache,
            Consumer<String> log) throws IOException, InterruptedException {
        Objects.requireNonNull(stateBackend, "stateBackend");
        if (!Set.of("embedded", "legacy", "file", "m3db", "native", "sqlite", "h2").contains(stateBackend)
                && !stateBackend.matches("ext-[a-z0-9][a-z0-9-]{0,47}")) {
            throw new IllegalArgumentException("unknown portable module state backend");
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
        Path cacheDirectory = buildCache == null || dryRun ? null : realCachePath(buildCache);
        if (cacheDirectory != null) {
            for (TaskManifest.Task task : tasks) {
                for (String output : task.outputs()) {
                    if (overlaps(cacheDirectory, checked(root, output))) {
                        throw new IOException("declared output overlaps build cache: " + output);
                    }
                }
            }
        }
        BuildCache cache = cacheDirectory == null ? null : new BuildCache(cacheDirectory);
        // Validation/execution/publication share this lock. A write-only lock cannot
        // prevent one Maven process from reusing another process's unfinished outputs.
        try (ModuleStateDb store = dryRun ? null : ModuleStateDb.open(root, stateBackend, incremental, log)) {
            return runTasks(plan, tasks, root, dryRun, timeoutPerTask, allowRewriteApply,
                    incremental, store, cache, cacheDirectory, log);
        }
    }

    private static List<String> runTasks(TaskManifest plan, List<TaskManifest.Task> tasks,
            Path root, boolean dryRun, Duration timeout, boolean allowRewriteApply, boolean incremental,
            ModuleStateDb store, BuildCache cache, Path cacheDirectory, Consumer<String> log)
            throws IOException, InterruptedException {
        List<String> performed = new ArrayList<>(tasks.size());
        Map<String, String> generations = new HashMap<>();
        Map<String, String> portableGenerations = new HashMap<>();
        Set<String> overlappingOutputs = overlappingOutputs(plan);
        TaskInvalidationIndex reverseGraph = new TaskInvalidationIndex(plan);
        boolean cleared = false;
        for (TaskManifest.Task task : tasks) {
            log.accept((dryRun ? "PLAN " : "TASK ") + task.name() + " tool=" + task.tool()
                    + " prerequisites=" + task.prerequisites().size()
                    + " inputs=" + task.inputs().size() + " outputs=" + task.outputs().size());
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
                aggregate(task, portableGenerations);
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
            boolean candidate = (incremental || cache != null) && qualityCommand == null && m3Command == null
                    && !task.inputs().isEmpty() && !task.outputs().isEmpty() && InputStamps.separate(task)
                    && (cacheDirectory == null || task.inputs().stream()
                            .noneMatch(path -> overlaps(cacheDirectory, root.resolve(path))))
                    && !overlappingOutputs.contains(task.name())
                    && task.prerequisites().stream().allMatch(generations::containsKey);
            String input = candidate ? InputStamps.stamp(task, root, command, environment, generations) : null;
            String cacheStamp = candidate && cache != null
                    && task.prerequisites().stream().allMatch(portableGenerations::containsKey)
                    ? InputStamps.portableStamp(task, root, command, environment, portableGenerations) : null;
            String[] prior = !incremental || input == null ? null : store.lookup(stateKey(task.name()));
            if (prior != null && input.equals(prior[0])) {
                String output = existingOutputs(task, root);
                if (prior[1].equals(output)) {
                    generations.put(task.name(), InputStamps.combine(List.of(input, output)));
                    if (cacheStamp != null) {
                        portableGenerations.put(task.name(), InputStamps.combine(List.of(cacheStamp, output)));
                    }
                    log.accept("UP-TO-DATE " + task.name() + " (M3DB input and output content verified)");
                    performed.add(task.name());
                    continue;
                }
            }
            // Restoration also mutates outputs. Revoke before either mutation so a partial
            // restore, failed command or crash cannot leave an earlier success reusable.
            if (!incremental && !cleared) {
                store.invalidateAll();
                cleared = true;
            } else {
                invalidateDependents(reverseGraph, task.name(), store);
            }
            boolean restored = cacheStamp != null && cache.restore(task, root, cacheStamp);
            if (!restored) execute(task, builder, timeout);
            for (String output : task.outputs()) {
                if (!Files.exists(checked(root, output), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("missing declared output from " + task.name() + ": " + output);
                }
            }
            if (input != null) {
                String after = null;
                String portableAfter = null;
                String output = null;
                try {
                    List<String> afterCommand = ordinaryCommand(task, directory);
                    after = InputStamps.stamp(task, root, afterCommand, environment, generations);
                    if (cacheStamp != null) {
                        portableAfter = InputStamps.portableStamp(
                                task, root, afterCommand, environment, portableGenerations);
                    }
                    output = InputStamps.outputs(task, root);
                } catch (IOException changed) {
                    if (restored) throw new IOException("cannot verify restored target " + task.name(), changed);
                    // The original command succeeded. Unstable evidence grants no reuse,
                    // but optional caching must not reclassify that success as compilation failure.
                    log.accept("UNCACHED " + task.name() + " (input/output identity unavailable after execution)");
                }
                if (input.equals(after) && (cacheStamp == null || cacheStamp.equals(portableAfter)) && output != null) {
                    if (!restored && cacheStamp != null) {
                        log.accept((cache.store(task, root, cacheStamp) ? "CACHED " : "NOT-CACHED ") + task.name());
                    }
                    // Stage only; one module commit follows a successful full task graph.
                    if (incremental) store.stage(stateKey(task.name()), input, output);
                    generations.put(task.name(), InputStamps.combine(List.of(input, output)));
                    if (cacheStamp != null) {
                        portableGenerations.put(task.name(), InputStamps.combine(List.of(cacheStamp, output)));
                    }
                    if (restored) log.accept("FROM-CACHE " + task.name() + " (input and restored output content verified)");
                } else {
                    if (restored) throw new IOException("inputs changed during restoration of target " + task.name());
                    log.accept("UNCACHED " + task.name() + " (inputs changed during execution)");
                }
            }
            performed.add(task.name());
        }
        // A crash or failed later task cannot publish partially successful work.
        // Earlier cache entries were already revoked before any output mutation.
        if (incremental) store.commitStaged();
        return List.copyOf(performed);
    }

    /** Resolve existing cache ancestors so a symlink alias cannot hide declared-path overlap. */
    private static Path realCachePath(Path requested) throws IOException {
        Path path = requested.toAbsolutePath().normalize();
        Path existing = path;
        while (Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        return existing.toRealPath().resolve(existing.relativize(path)).normalize();
    }

    private static boolean overlaps(Path first, Path second) {
        return first.startsWith(second) || second.startsWith(first);
    }

    /** A separate bounded namespace keeps manifest names distinct from lifecycle receipts. */
    private static String stateKey(String task) {
        return "task-" + InputStamps.combine(List.of(task));
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

    private static void invalidateDependents(TaskInvalidationIndex graph, String task,
            ModuleStateDb store) throws IOException {
        // This closure is computed from the already-built reverse CSR graph.
        store.invalidate(graph.closure(task).stream().map(TaskRunner::stateKey).toList());
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
    static Path checked(Path root, String relative) throws IOException {
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
