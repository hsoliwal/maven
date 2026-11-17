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
package org.apache.maven.impl.process;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.maven.api.services.LaunchedProcess;
import org.apache.maven.api.services.ProcessLaunchRequest;
import org.apache.maven.api.services.ProcessLaunchResult;
import org.apache.maven.api.services.ProcessLauncherException;

/**
 * A running process with its stream threads and shutdown hook.
 * <p>
 * The wait, timeout, stream grace period and shutdown hook follow maven-shared-utils 3.5.0
 * {@code CommandLineUtils.executeCommandLineAsCallable} and {@code ShutdownHookUtils} (Apache-2.0); unlike them, a
 * destroy also reaches the descendants of the process (the program behind a shell) and falls back to
 * {@link Process#destroyForcibly()} after the request's grace period, and an expired timeout is a result, not an
 * exception.
 */
final class DefaultLaunchedProcess implements LaunchedProcess {

    /** How long stream threads may run after exit before the process streams are closed. */
    static final Duration STREAM_GRACE_PERIOD = Duration.ofSeconds(5);

    private final ProcessLaunchRequest request;
    private final List<String> commandLine;
    private final Process process;
    private final long startNanos;
    private final List<StreamThread> streams = new ArrayList<>();
    private final Optional<Thread> shutdownHook;
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean streamsForcedClosed;

    private DefaultLaunchedProcess(
            ProcessLaunchRequest request, List<String> commandLine, Process process, long startNanos) {
        this.request = request;
        this.commandLine = commandLine;
        this.process = process;
        this.startNanos = startNanos;
        this.shutdownHook = request.isDestroyOnShutdown() ? Optional.of(newHook(process)) : Optional.empty();
    }

    /**
     * Starts a process and its stream threads.
     *
     * @param builder the configured process builder
     * @param request the request
     * @param commandLine the command line of {@code builder}
     * @param closeIdleInput whether standard input is closed when the request has no input
     * @return the launched process
     * @throws ProcessLauncherException if the process cannot be started
     */
    static DefaultLaunchedProcess start(
            ProcessBuilder builder, ProcessLaunchRequest request, List<String> commandLine, boolean closeIdleInput) {
        long startNanos = System.nanoTime();
        DefaultLaunchedProcess launched =
                new DefaultLaunchedProcess(request, commandLine, startProcess(builder), startNanos);
        launched.connect(closeIdleInput);
        return launched;
    }

    private static Process startProcess(ProcessBuilder builder) {
        try {
            return builder.start();
        } catch (IOException e) {
            throw new ProcessLauncherException("Error while executing process.", e);
        }
    }

    private void connect(boolean closeIdleInput) {
        shutdownHook.ifPresent(DefaultLaunchedProcess::addHook);
        request.getInput()
                .ifPresentOrElse(input -> streams.add(StreamThread.feed(input, process.getOutputStream())), () -> {
                    if (closeIdleInput) {
                        closeQuietly(process.getOutputStream());
                    }
                });
        Charset charset = request.getCharset().orElse(Charset.defaultCharset());
        request.getOutputConsumer()
                .ifPresent(out -> streams.add(StreamThread.pump("stdout", process.getInputStream(), out, charset)));
        request.getErrorConsumer()
                .filter(err -> !request.isRedirectErrorStream())
                .ifPresent(err -> streams.add(StreamThread.pump("stderr", process.getErrorStream(), err, charset)));
    }

    @Override
    public Process process() {
        return process;
    }

    @Override
    public List<String> commandLine() {
        return commandLine;
    }

    @Override
    public ProcessLaunchResult await() {
        try {
            return awaitResult();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroy();
            throw new ProcessLauncherException("Error while executing external command, process killed.", e);
        } finally {
            close();
        }
    }

    private ProcessLaunchResult awaitResult() throws InterruptedException {
        boolean timedOut = !waitForExit();
        if (timedOut) {
            destroy();
        }
        int exitCode = process.waitFor();
        Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
        finishStreams();
        return new DefaultProcessLaunchResult(request, exitCode, timedOut, commandLine, duration);
    }

    private boolean waitForExit() throws InterruptedException {
        Optional<Duration> timeout = request.getTimeout();
        if (timeout.isEmpty()) {
            process.waitFor();
            return true;
        }
        long remaining = timeout.get().toNanos() - (System.nanoTime() - startNanos);
        return process.waitFor(Math.max(0, remaining), TimeUnit.NANOSECONDS);
    }

    @Override
    public void destroy() {
        List<ProcessHandle> descendants = descendants(process);
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        if (!exitsWithin(request.getDestroyGracePeriod())) {
            descendants.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private boolean exitsWithin(Duration gracePeriod) {
        try {
            return process.waitFor(gracePeriod.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void finishStreams() throws InterruptedException {
        if (!allEnded()) {
            streamsForcedClosed = true;
            closeQuietly(process.getOutputStream());
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            allEnded();
        }
        streams.stream()
                .filter(thread -> thread.failure().filter(this::reported).isPresent())
                .findFirst()
                .ifPresent(thread -> {
                    throw new ProcessLauncherException(
                            "Failure processing " + thread.stream() + ".",
                            thread.failure().orElseThrow());
                });
    }

    /** A stream closed here after the grace period fails with an I/O error that is not the process's fault. */
    private boolean reported(Exception failure) {
        return !(streamsForcedClosed && failure instanceof IOException);
    }

    private boolean allEnded() throws InterruptedException {
        long deadline = System.nanoTime() + STREAM_GRACE_PERIOD.toNanos();
        boolean ended = true;
        for (StreamThread thread : streams) {
            ended &= thread.awaitEnd(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        }
        return ended;
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            destroy();
        }
        if (closed.compareAndSet(false, true)) {
            shutdownHook.ifPresent(DefaultLaunchedProcess::removeHook);
        }
    }

    /**
     * Gets the shutdown hook registered for the process.
     *
     * @return the hook, or empty when the request does not destroy on shutdown
     */
    Optional<Thread> shutdownHook() {
        return shutdownHook;
    }

    private static Thread newHook(Process process) {
        Thread hook = new Thread(
                () -> {
                    descendants(process).forEach(ProcessHandle::destroy);
                    process.destroy();
                },
                "ProcessLauncher shutdown hook");
        hook.setContextClassLoader(null);
        return hook;
    }

    private static List<ProcessHandle> descendants(Process process) {
        try {
            return process.descendants().toList();
        } catch (UnsupportedOperationException e) {
            return List.of();
        }
    }

    /** As {@code ShutdownHookUtils.addShutDownHook}: a JVM already shutting down cannot take hooks. */
    private static void addHook(Thread hook) {
        try {
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (IllegalStateException | SecurityException ignore) {
            // the JVM is shutting down or hooks are not permitted
        }
    }

    /** As {@code ShutdownHookUtils.removeShutdownHook}. */
    private static void removeHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException | SecurityException ignore) {
            // the JVM is shutting down or hooks are not permitted
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignore) {
            // the process side of the stream is gone
        }
    }
}
