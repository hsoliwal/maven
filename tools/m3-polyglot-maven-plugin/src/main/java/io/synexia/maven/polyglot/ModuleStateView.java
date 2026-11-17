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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Read-only view of the polyglot task state recorded in one module, for a project database to ingest.
 * Each backend is read through its own owner; nothing is created, revoked or published here.
 */
public final class ModuleStateView {
    /** One recorded task: backend, task name, input and output digests, and the module-relative state file. */
    public record TaskState(String backend, String task, String input, String output, String source) {}

    private ModuleStateView() {}

    /** All task states of a module, legacy stamps first, then the file/m3db image, then the M3IndexDB. */
    public static List<TaskState> read(Path module) throws IOException {
        Path root = module.toAbsolutePath().normalize();
        List<TaskState> states = new ArrayList<>(legacy(root));
        states.addAll(image(root, "file", ModuleStateDb.FILE));
        states.addAll(image(root, "m3db", ModuleStateDb.NATIVE_DIRECTORY));
        states.addAll(indexDb(root));
        return List.copyOf(states);
    }

    private static List<TaskState> legacy(Path root) throws IOException {
        Path directory = root.resolve(LegacyStamps.DIRECTORY);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> stamps = files.filter(file -> file.getFileName().toString().endsWith(".sha256")).sorted().toList();
            List<TaskState> states = new ArrayList<>();
            for (Path stamp : stamps) states.add(legacyState(root, stamp));
            return states;
        }
    }

    private static TaskState legacyState(Path root, Path stamp) throws IOException {
        String name = stamp.getFileName().toString();
        String input = Files.readString(stamp, StandardCharsets.UTF_8).strip();
        return new TaskState("legacy", name.substring(0, name.length() - ".sha256".length()), input, "",
                relative(root, stamp));
    }

    private static List<TaskState> image(Path root, String backend, String location) throws IOException {
        if (!Files.exists(root.resolve(location), LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<TaskState> states = new ArrayList<>();
        for (String[] row : ModuleStateDb.open(root, backend).taskStates()) {
            states.add(new TaskState(backend, row[0], row[1], row[2], location));
        }
        return states;
    }

    private static List<TaskState> indexDb(Path root) throws IOException {
        if (!Files.isRegularFile(root.resolve(M3DbModuleStore.FILE), LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<TaskState> states = new ArrayList<>();
        try (M3DbModuleStore store = M3DbModuleStore.open(root, false, ignored -> {})) {
            for (String[] row : store.receipts()) {
                states.add(new TaskState("indexdb", row[0], row[1], row[2], M3DbModuleStore.FILE));
            }
        }
        return states;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
