/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;

/** Offline contract tests for persistent M3 module state, concurrency and invalidation. */
public final class ModuleStateDbContractTestMain {
    private ModuleStateDbContractTestMain() {}

    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("m3-module-db-test-");
        try {
            Path module = Files.createDirectories(root.resolve("module"));
            Path src = Files.writeString(module.resolve("source.txt"), "AAAA", StandardCharsets.UTF_8);
            Path output = Files.writeString(module.resolve("output.txt"), "result", StandardCharsets.UTF_8);
            Path tool = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
            TaskManifest.Task task = new TaskManifest.Task("compile", tool.toString(), ".",
                    List.of("-version"), List.of(), List.of("source.txt"), List.of("output.txt"));

            String fingerprint;
            try (ModuleStateDb first = ModuleStateDb.open(module, "file")) {
                fingerprint = first.stamp(task);
                require(!first.upToDate(task, fingerprint), "cold state must miss");
                first.record(task, fingerprint);
                expectIOException(() -> ModuleStateDb.open(module, "file"),
                        "second concurrent writer must be refused");
            }
            Path database = module.resolve(ModuleStateDb.FILE);
            require(Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS), "one module DB image");
            require(!Files.exists(module.resolve(InputStamps.DIRECTORY).resolve("compile.sha256")),
                    "DB mode must not create legacy per-task stamps");

            try (ModuleStateDb reopened = ModuleStateDb.open(module, "file")) {
                String same = reopened.stamp(task);
                require(fingerprint.equals(same), "fingerprints stable across process state");
                require(reopened.upToDate(task, same), "unchanged task and output must hit");
                // JGit-inspired racy timestamp guard may deliberately rehash recently modified
                // files even on POSIX. A hit is about correctness, not zero digests.
                Files.writeString(output, "tampered", StandardCharsets.UTF_8);
                require(!reopened.upToDate(task, same), "output drift must invalidate");
                Files.writeString(output, "result", StandardCharsets.UTF_8);
            }

            FileTime previousMtime = Files.getLastModifiedTime(src);
            Files.writeString(src, "BBBB", StandardCharsets.UTF_8);
            Files.setLastModifiedTime(src, previousMtime);
            String changedStamp;
            try (ModuleStateDb changed = ModuleStateDb.open(module, "file")) {
                changedStamp = changed.stamp(task);
                require(!changedStamp.equals(fingerprint), "same-size edit with restored mtime invalidates");
                require(!changed.upToDate(task, changedStamp), "new content must miss");
                changed.record(task, changedStamp);
            }
            try (ModuleStateDb verified = ModuleStateDb.open(module, "file")) {
                require(verified.upToDate(task, changedStamp), "committed result survives reopening");
                TaskManifest.Task flags = new TaskManifest.Task(task.name(), task.tool(), task.workingDir(),
                        List.of("-version", "-ea"), task.prerequisites(), task.inputs(), task.outputs());
                require(!verified.stamp(flags).equals(changedStamp), "tool arguments affect the key");
                Files.delete(output);
                require(!verified.upToDate(task, changedStamp), "missing output invalidates");
            }

            // Independent modules cannot overwrite one another's state.
            Path sibling = Files.createDirectories(root.resolve("sibling"));
            Files.writeString(sibling.resolve("source.txt"), "BBBB", StandardCharsets.UTF_8);
            Files.writeString(sibling.resolve("output.txt"), "result", StandardCharsets.UTF_8);
            try (ModuleStateDb other = ModuleStateDb.open(sibling, "file")) {
                require(!other.upToDate(task, other.stamp(task)), "different module starts cold");
            }

            Files.write(database, new byte[] {1, 2, 3, 4});
            try (ModuleStateDb corrupt = ModuleStateDb.open(module, "file")) {
                require(!corrupt.upToDate(task, changedStamp), "corruption must miss safely");
            }

            // Driver-optional modes reject missing backend drivers. The dedicated JDBC suite
            // exercises the real SQLite/H2 engines when Maven test dependencies are available.
            require(Files.exists(module.resolve(".m3/module-state.lock")), "lease file is module-local");
            System.out.println("PASS: module DB persistence, lease, invalidation, racy times, corruption, isolation");
        } finally {
            try (var walk = Files.walk(root)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    private interface Checked { void run() throws Exception; }

    private static void expectIOException(Checked operation, String message) throws Exception {
        try {
            operation.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            // Contention is a deliberate refusal, not a cache hit or a timeout.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
