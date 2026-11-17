/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Sequential execution of a validated Ant/Make-like graph. Tasks invoke the
 * original compiler or build tool by argument vector, never through a shell.
 * There is deliberately no timestamp-only skipping or undeclared build cache; the opt-in incremental mode
 * compares content stamps of declared inputs only, and the opt-in build cache ({@link BuildCache}) restores
 * declared outputs under the same kind of stamp.
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
     * Per-module persistent state is opt-in: "legacy" preserves the original per-task stamps,
     * "file" uses one sealed state file per module, and "m3db" uses native MIndexDB artifacts.
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
        return run(plan, workspace, target, dryRun, timeoutPerTask, allowRewriteApply, incremental, stateBackend,
                null, log);
    }

    /**
     * Opt-in local build cache: with a {@code buildCache} folder, a task that declares inputs and outputs and has
     * no provider-owned state is looked up by its content stamp before it starts. On a hit its declared outputs are
     * restored and the tool is not started; after a success its outputs are stored. {@code null} disables the
     * cache. Independent of {@code incremental}; with both, the up-to-date check comes first.
     */
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
        return run(plan, workspace, target, dryRun, timeoutPerTask, allowRewriteApply, incremental, "legacy",
                buildCache, log);
    }

    /** Both opt-ins together: a persistent state backend for incremental mode and a local build cache folder. */
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
        List<TaskManifest.Task> tasks = plan.order(target);
        try (M3ModuleDbRunLock moduleLock = incremental && !dryRun && !"legacy".equals(stateBackend)
                ? M3ModuleDbRunLock.acquire(root) : null) {
            if (moduleLock != null) log.accept("M3DB module execution lock acquired");
            ModuleStateDb moduleState = incremental && !dryRun && !"legacy".equals(stateBackend)
                    ? ModuleStateDb.open(root, stateBackend) : null;
            List<String> performed = new ArrayList<>(tasks.size());
            BuildCache cache = buildCache == null || dryRun ? null : new BuildCache(buildCache);
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
                performed.add(task.name());
                continue;
            }
            Path directory = checked(root, task.workingDir());
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("working directory does not exist for target " + task.name());
            }
            for (String input : task.inputs()) {
                Path file = checked(root, input);
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("missing declared input for " + task.name() + ": " + input);
                }
            }
            // Only explicitly declared, ordinary tool actions with both inputs and outputs
            // are candidates for this opt-in content-stamp shortcut. CodeQL databases and
            // OpenRewrite recipes have provider-owned state and must execute normally.
            // A path stamp never proves complete toolchain, environment or ABI inputs.
            // Pluggable providers own further hidden inputs, so never skip them.
            boolean candidate = !task.inputs().isEmpty() && !task.outputs().isEmpty()
                    && qualityCommand == null && m3Command == null;
            String stamp = !(incremental && candidate) ? null : moduleState == null
                    ? InputStamps.stamp(task, root) : moduleState.stamp(task);
            if (stamp != null && (moduleState == null
                    ? InputStamps.upToDate(task, root, stamp) : moduleState.upToDate(task, stamp))) {
                log.accept("UP-TO-DATE " + task.name() + " (declared inputs unchanged, outputs present)");
                performed.add(task.name());
                continue;
            }
            // The cache key always uses the relocatable InputStamps stamp, whatever the state backend.
            String cacheStamp = cache == null || !candidate ? null
                    : moduleState == null && stamp != null ? stamp : InputStamps.stamp(task, root);
            if (restored(cache, plan, moduleState, task, root, cacheStamp, stamp, log)) {
                performed.add(task.name());
                continue;
            }
            List<String> command = new ArrayList<>(task.arguments().size() + 1);
            if (qualityCommand != null) {
                command.addAll(qualityCommand);
            } else if (m3Command != null) {
                command.addAll(m3Command);
            } else {
                command.add(resolveTool(task.tool(), directory));
                command.addAll(task.arguments());
            }
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(directory.toFile());
            processBuilder.inheritIO();
            // Revoke the old task and all consumers before tool effects begin.
            // Stale successful records cannot survive a failed or interrupted invocation.
            if (moduleState != null) moduleState.invalidateTransitive(plan, task.name());
            final Process process;
            try {
                process = processBuilder.start();
            } catch (IOException failure) {
                throw new IOException("cannot start tool for target " + task.name() + ": " + task.tool(), failure);
            }
            final boolean finished;
            try {
                finished = process.waitFor(timeoutPerTask.toNanos(), TimeUnit.NANOSECONDS);
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
            for (String output : task.outputs()) {
                Path file = checked(root, output);
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("missing declared output from " + task.name() + ": " + output);
                }
            }
            if (stamp != null) {
                if (moduleState == null) {
                    InputStamps.record(task, root, stamp);
                } else {
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
            }
            if (cacheStamp != null) store(cache, task, root, cacheStamp, log);
            performed.add(task.name());
        }
        return List.copyOf(performed);
        }
    }

    /**
     * True when the build cache restored the task's declared outputs. As before a tool run, the task and its
     * consumers are revoked in the module state first; after the restore the incremental stamp is recorded.
     */
    private static boolean restored(BuildCache cache, TaskManifest plan, ModuleStateDb moduleState,
            TaskManifest.Task task, Path root, String cacheStamp, String stamp, Consumer<String> log)
            throws IOException {
        if (cache == null || cacheStamp == null || !Files.isRegularFile(cache.entry(BuildCache.key(cacheStamp)))) {
            return false;
        }
        if (moduleState != null) moduleState.invalidateTransitive(plan, task.name());
        if (!cache.restore(task, root, cacheStamp)) return false;
        log.accept("FROM-CACHE " + task.name() + " (declared outputs restored, key "
                + BuildCache.key(cacheStamp).substring(0, 12) + ")");
        if (stamp != null && moduleState == null) InputStamps.record(task, root, stamp);
        if (stamp != null && moduleState != null) moduleState.record(task, stamp);
        return true;
    }

    private static void store(BuildCache cache, TaskManifest.Task task, Path root, String cacheStamp,
            Consumer<String> log) throws IOException {
        boolean stored = cache.store(task, root, cacheStamp);
        log.accept((stored ? "CACHED " : "NOT-CACHED ") + task.name()
                + (stored ? "" : " (an output is a link or special file, or exceeds the entry budget)"));
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
                Path candidate = folder.resolve(tool + extension);
                if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) return candidate.toString();
            }
        }
        return tool;
    }

    /** Rejects lexical escapes and existing symlinks in the path to a declared input/output. */
    static Path checked(Path root, String relative) throws IOException {
        Path value = root.resolve(relative).normalize();
        if (!value.startsWith(root)) {
            throw new IOException("resource is outside polyglot project root: " + relative);
        }
        Path cursor = root;
        for (Path component : root.relativize(value)) {
            cursor = cursor.resolve(component);
            if (Files.isSymbolicLink(cursor)) {
                throw new IOException("polyglot declared path traverses symlink: " + relative);
            }
        }
        return value;
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
