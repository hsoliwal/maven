// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded, explicitly configured CPU summary process; its output remains untrusted. */
final class CpuSummarizer {
    private static final int MAX_OUTPUT_BYTES = 1_048_576;
    private static final int MAX_LINES = 40;
    private static final int MAX_LINE_CHARS = 300;
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final Set<String> OUTPUTS = Set.of("pre-build.summary.txt", "post-build.summary.txt");

    record Result(String status, List<String> lines) { }

    private CpuSummarizer() { }

    /** Retains at most one MiB of stdout, and never closes a blocked reader on the caller thread. */
    static Result run(List<String> command, Path base, Path output, int timeoutSeconds) {
        String status = "available";
        boolean interrupted = false;
        Process process = null;
        ExecutorService worker = null;
        Future<Boolean> reading = null;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Map<Long, ProcessHandle> descendants = new LinkedHashMap<>();
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            validateOutput(output);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            process = new ProcessBuilder(command).directory(base.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            Process owned = process;
            worker = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "maven-cpu-summary-reader");
                thread.setDaemon(true);
                return thread;
            });
            reading = worker.submit(() -> read(owned, bytes));
            if (awaitOutput(reading, process, descendants, deadline)) status = "failed: output-limit";
            else {
                awaitExit(process, descendants, deadline);
                if (process.exitValue() != 0) status = "failed: exit " + process.exitValue();
            }
        } catch (InterruptedException cancelled) {
            interrupted = true;
            status = "failed: interrupted";
        } catch (TimeoutException deadline) {
            status = "failed: timeout";
        } catch (ExecutionException failure) {
            status = "failed: " + failure.getCause().getClass().getSimpleName();
        } catch (IOException | RuntimeException failure) {
            status = "failed: " + failure.getClass().getSimpleName();
        } finally {
            if (reading != null) reading.cancel(true);
            if (worker != null) worker.shutdownNow();
            if (process != null) interrupted |= retire(process, descendants);
        }
        List<String> lines = List.of();
        try {
            if (process != null) publish(output, bytes.toByteArray());
            if (status.equals("available")) lines = lines(bytes.toByteArray());
        } catch (IOException | RuntimeException failure) {
            status = "failed: " + failure.getClass().getSimpleName();
        } finally {
            interrupted |= Thread.currentThread().isInterrupted();
            if (interrupted) {
                status = "failed: interrupted";
                lines = List.of();
                Thread.currentThread().interrupt();
            }
        }
        return new Result(status, lines);
    }

    /** Returns true as soon as a byte beyond the retained limit arrives. */
    private static boolean read(Process process, ByteArrayOutputStream output) throws IOException {
        byte[] scratch = new byte[8_192];
        try (var input = process.getInputStream()) {
            int retained = 0;
            while (true) {
                int count = input.read(scratch, 0, Math.min(scratch.length, MAX_OUTPUT_BYTES - retained + 1));
                if (count < 0) return false;
                int accepted = Math.min(count, MAX_OUTPUT_BYTES - retained);
                output.write(scratch, 0, accepted);
                retained += accepted;
                if (accepted != count) return true;
            }
        }
    }

    private static boolean awaitOutput(Future<Boolean> reading, Process process,
            Map<Long, ProcessHandle> descendants, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        while (true) {
            observe(process, descendants);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            try { return reading.get(Math.min(remaining, POLL_NANOS), TimeUnit.NANOSECONDS); }
            catch (TimeoutException pending) {
                if (System.nanoTime() >= deadline) throw pending;
            }
        }
    }

    private static void awaitExit(Process process, Map<Long, ProcessHandle> descendants, long deadline)
            throws InterruptedException, TimeoutException {
        while (true) {
            observe(process, descendants);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            if (process.waitFor(Math.min(remaining, POLL_NANOS), TimeUnit.NANOSECONDS)) return;
        }
    }

    private static void observe(Process process, Map<Long, ProcessHandle> descendants) {
        try { process.descendants().forEach(child -> descendants.putIfAbsent(child.pid(), child)); }
        catch (RuntimeException unavailable) { /* Process observation is best effort. */ }
    }

    /** Retires the direct child and observed descendants with a bounded wait, preserving cancellation. */
    private static boolean retire(Process process, Map<Long, ProcessHandle> descendants) {
        boolean interrupted = Thread.interrupted();
        observe(process, descendants);
        List<ProcessHandle> children = List.copyOf(descendants.values());
        for (int i = children.size() - 1; i >= 0; i--) {
            try { children.get(i).destroyForcibly(); }
            catch (RuntimeException unavailable) { /* Still retire the direct child. */ }
        }
        if (!children.isEmpty()) {
            try { process.waitFor(100, TimeUnit.MILLISECONDS); }
            catch (InterruptedException cancelled) { interrupted = true; }
            catch (RuntimeException unavailable) { /* Still retire the direct child. */ }
        }
        try { process.toHandle().destroyForcibly(); }
        catch (RuntimeException unavailable) { /* Cleanup cannot replace the advisory result. */ }
        try { process.waitFor(2, TimeUnit.SECONDS); }
        catch (InterruptedException cancelled) { interrupted = true; }
        catch (RuntimeException unavailable) { /* Bounded, best-effort retirement. */ }
        return interrupted;
    }

    /** The caller supplies a directory already admitted by SafeBuildOutput. */
    private static void validateOutput(Path output) throws IOException {
        Path directory = output.getParent();
        if (!OUTPUTS.contains(output.getFileName().toString()) || directory == null
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)
                || Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("summary destination must be an ordinary file");
    }

    /** Atomically replaces an ordinary output; a link/non-file is refused before process launch and publication. */
    private static void publish(Path output, byte[] bytes) throws IOException {
        validateOutput(output);
        Path temporary = Files.createTempFile(output.getParent(), "m3-summary-", ".part");
        try {
            Files.write(temporary, bytes);
            validateOutput(output);
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static List<String> lines(byte[] bytes) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        return text.lines().map(String::strip).filter(line -> !line.isEmpty()).limit(MAX_LINES)
                .map(CpuSummarizer::truncate).toList();
    }

    private static String truncate(String line) {
        int end = Math.min(line.length(), MAX_LINE_CHARS);
        if (end < line.length() && Character.isHighSurrogate(line.charAt(end - 1))
                && Character.isLowSurrogate(line.charAt(end))) end--;
        return line.substring(0, end);
    }
}
