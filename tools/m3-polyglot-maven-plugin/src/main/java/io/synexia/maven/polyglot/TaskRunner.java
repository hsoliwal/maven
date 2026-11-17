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
 * compares content stamps of declared inputs only.
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
        List<String> performed = new ArrayList<>(tasks.size());
        for (TaskManifest.Task task : tasks) {
            log.accept((dryRun ? "PLAN " : "TASK ") + task.name() + " tool=" + task.tool()
                    + " prerequisites=" + task.prerequisites().size()
                    + " inputs=" + task.inputs().size() + " outputs=" + task.outputs().size());
            // Validate provider argument contracts even in a read-only plan.
            List<String> qualityCommand = QualityTaskCommands.supported(task.tool())
                    ? QualityTaskCommands.command(task, root, dryRun || allowRewriteApply) : null;
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
            String stamp = incremental ? InputStamps.stamp(task, root) : null;
            if (stamp != null && InputStamps.upToDate(task, root, stamp)) {
                log.accept("UP-TO-DATE " + task.name() + " (declared inputs unchanged, outputs present)");
                performed.add(task.name());
                continue;
            }
            List<String> command = new ArrayList<>(task.arguments().size() + 1);
            if (qualityCommand != null) {
                command.addAll(qualityCommand);
            } else {
                command.add(task.tool());
                command.addAll(task.arguments());
            }
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(directory.toFile());
            processBuilder.inheritIO();
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
            if (stamp != null) InputStamps.record(task, root, stamp);
            performed.add(task.name());
        }
        return List.copyOf(performed);
    }

    /** Rejects lexical escapes and existing symlinks in the path to a declared input/output. */
    private static Path checked(Path root, String relative) throws IOException {
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
