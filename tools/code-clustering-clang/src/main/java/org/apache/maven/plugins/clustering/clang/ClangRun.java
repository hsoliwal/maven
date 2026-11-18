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
package org.apache.maven.plugins.clustering.clang;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * Runs one clang command without a shell and turns its JSON dump into atoms while clang writes it. Standard error
 * goes to a temporary file; its first 64 KiB decide the outcome. Clang reports errors in the code and still dumps a
 * usable tree, so errors alone do not fail a file. A fatal error does: clang stops at it, so the tree after it is
 * missing (typically a header that is not found). A run longer than the timeout is killed.
 */
final class ClangRun {
    /** What became of one file. */
    enum Status {
        /** Atoms from clang's tree. */
        AST,
        /** No compile_commands.json entry, no header to borrow one for, no default flags. */
        NO_COMMAND,
        /** Clang stopped at a fatal error, for example a header it could not find. */
        FATAL,
        /** The driver refused an argument of the compile command (for example a GCC-only option). */
        UNKNOWN_ARGUMENT,
        /** Clang ran longer than the timeout and was stopped. */
        TIMEOUT,
        /** Clang could not start, crashed, or wrote no readable tree. */
        FAILED
    }

    /**
     * The result of one run.
     *
     * @param status what became of the file
     * @param atoms  the atoms, when the status is {@link Status#AST}
     * @param detail the first line that explains a failure; for AST, the number of errors clang reported, if any
     * @param errors the head of clang's standard error
     */
    record Result(Status status, List<Atom> atoms, String detail, String errors) { }

    private static final int ERRORS_HEAD = 64 * 1024;

    private ClangRun() { }

    static Result run(List<String> command, Path directory, Path file, String path, Duration timeout) {
        Path errors = null;
        try {
            errors = Files.createTempFile("clang-ast-", ".err");
            Process process = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectError(errors.toFile()).start();
            process.getOutputStream().close();
            return finish(process, file, path, timeout, errors);
        } catch (IOException | RuntimeException failed) {
            return new Result(Status.FAILED, List.of(), String.valueOf(failed.getMessage()), "");
        } finally {
            deleteQuietly(errors);
        }
    }

    private static Result finish(Process process, Path file, String path, Duration timeout, Path errors)
            throws IOException {
        AtomicBoolean timedOut = watchdog(process, timeout);
        Predicate<String> isMain = sameFile(file);
        AstAtoms atoms = new AstAtoms(path, SourceText.read(file), isMain);
        String parseFailure = stream(process, isMain, atoms);
        waitQuietly(process);
        return classify(timedOut.get(), parseFailure, head(errors), atoms.atoms());
    }

    /** Reads the dump into the atoms; returns why it could not be read, or empty. */
    private static String stream(Process process, Predicate<String> isMain, AstAtoms atoms) {
        try (InputStream out = process.getInputStream()) {
            AstStream.read(out, isMain, atoms.records(), atoms::visit);
            return "";
        } catch (IOException | RuntimeException unreadable) {
            process.destroyForcibly();
            return "unreadable syntax tree: " + unreadable.getMessage();
        }
    }

    private static Result classify(boolean timedOut, String parseFailure, String errors, List<Atom> atoms) {
        if (timedOut) return new Result(Status.TIMEOUT, List.of(), "timed out", errors);
        String fatal = firstLine(errors, "fatal error:");
        if (!fatal.isEmpty()) return new Result(Status.FATAL, List.of(), fatal, errors);
        String refused = firstLine(errors, "unknown argument");
        if (refused.isEmpty()) refused = firstLine(errors, "unsupported option");
        if (!refused.isEmpty()) return new Result(Status.UNKNOWN_ARGUMENT, List.of(), refused, errors);
        if (!parseFailure.isEmpty()) return new Result(Status.FAILED, List.of(), parseFailure, errors);
        long count = errors.lines().filter(line -> line.contains(": error: ")).count();
        return new Result(Status.AST, List.copyOf(atoms), count == 0 ? "" : count + " errors", errors);
    }

    /** The first line of the text that contains the marker, or empty. */
    static String firstLine(String text, String marker) {
        return text.lines().filter(line -> line.contains(marker)).findFirst().orElse("");
    }

    /** Tells whether a file name, as clang printed it, is this file. */
    static Predicate<String> sameFile(Path file) {
        Path target = file.toAbsolutePath().normalize();
        Map<String, Boolean> seen = new HashMap<>();
        return name -> seen.computeIfAbsent(name, n -> same(n, target));
    }

    private static boolean same(String name, Path target) {
        try {
            Path path = Path.of(name);
            if (path.isAbsolute() && path.normalize().equals(target)) return true;
            return path.isAbsolute() && Files.exists(path) && Files.isSameFile(path, target);
        } catch (IOException | InvalidPathException other) {
            return false;
        }
    }

    private static AtomicBoolean watchdog(Process process, Duration timeout) {
        AtomicBoolean fired = new AtomicBoolean();
        Thread watchdog = new Thread(() -> guard(process, timeout, fired), "clang-ast-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        return fired;
    }

    private static void guard(Process process, Duration timeout, AtomicBoolean fired) {
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                fired.set(true);
                process.destroyForcibly();
            }
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitQuietly(Process process) {
        try {
            process.waitFor();
        } catch (InterruptedException stop) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static String head(Path errors) throws IOException {
        try (InputStream in = Files.newInputStream(errors)) {
            return new String(in.readNBytes(ERRORS_HEAD), StandardCharsets.UTF_8);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            if (file != null) Files.deleteIfExists(file);
        } catch (IOException ignored) {
            file.toFile().deleteOnExit();
        }
    }
}
