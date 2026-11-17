/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Portable ServiceLoader provider and all-or-nothing module commit checks. */
public final class ModuleStateProviderContractTestMain {
    private ModuleStateProviderContractTestMain() {}

    public static void main(String[] ignored) throws Exception {
        Path parent = Files.createTempDirectory("m3-provider-db-");
        try {
            Path module = Files.createDirectories(parent.resolve("one"));
            Files.writeString(module.resolve("src.txt"), "hello");
            Files.writeString(module.resolve("out.txt"), "world");
            Path java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
            TaskManifest.Task task = new TaskManifest.Task("compile", java.toString(), ".",
                    List.of("-version"), List.of(), List.of("src.txt"), List.of("out.txt"));
            String digest;
            try (ModuleStateDb state = ModuleStateDb.open(module, "testmemory")) {
                digest = state.stamp(task);
                require(!state.upToDate(task, digest), "initial external provider miss");
                state.recordPending(task, digest);
                // No commit means a build that fails after a task must leave no
                // published state, even though the in-memory store was opened.
            }
            try (ModuleStateDb state = ModuleStateDb.open(module, "testmemory")) {
                require(!state.upToDate(task, digest), "uncommitted task not restored");
                state.recordPending(task, digest);
                state.commit();
            }
            try (ModuleStateDb state = ModuleStateDb.open(module, "testmemory")) {
                require(state.upToDate(task, state.stamp(task)), "service-loaded backend preserves state");
            }
            try {
                ModuleStateDb.open(module, "not-installed");
                throw new AssertionError("missing provider must refuse");
            } catch (IllegalArgumentException expected) {
                // Unknown providers must not substitute file/native or silently succeed.
            }
            System.out.println("PASS: custom provider discovery, atomic batch and missing-provider refusal");
        } finally {
            try (var tree = Files.walk(parent)) {
                for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
