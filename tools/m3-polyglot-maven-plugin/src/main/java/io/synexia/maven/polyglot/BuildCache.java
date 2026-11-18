/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Opt-in, content-addressed local cache of the declared outputs of polyglot tasks. The design follows Gradle's
 * local build cache ({@code DirectoryBuildCacheService}, Apache-2.0): look up before executing, store after a
 * success, publish through a temporary file and an atomic move. No Gradle code is copied.
 *
 * <p>The key is the task's {@link InputStamps} content stamp (definition plus the bytes of its declared inputs)
 * with this format version and the host OS and architecture. An entry ({@code entries/xx/<key>.tsv}) lists every
 * output folder and file; each file body is stored once under {@code blobs/xx/<sha256>}. On a hit the declared
 * outputs are replaced as a whole. An entry that is malformed, names a path outside the declared outputs, or whose
 * stored bytes do not match their SHA-256 is a miss, and the task executes. Outputs that contain a link or a special
 * file are never stored. Like the stamps, a key does not certify tool versions, environment or ABI.
 */
final class BuildCache {
    static final String ENTRY_MAGIC = "M3-POLYGLOT-CACHE-ENTRY-V1";
    private static final long MAX_ENTRY_BYTES = 1L << 30;
    private static final int MAX_ENTRY_ROWS = 100_000;
    private static final long MAX_ENTRY_FILE = 64L << 20;

    private final Path directory;

    BuildCache(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    /** The cache key of a task stamp on this host. */
    static String key(String stamp) {
        return InputStamps.digest(ENTRY_MAGIC, stamp, System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""));
    }

    Path entry(String key) {
        return directory.resolve("entries").resolve(key.substring(0, 2)).resolve(key + ".tsv");
    }

    Path blob(String sha256) {
        return directory.resolve("blobs").resolve(sha256.substring(0, 2)).resolve(sha256);
    }

    /** Restores a stored entry over the declared outputs; false on a miss or on an entry that fails verification. */
    boolean restore(TaskManifest.Task task, Path root, String stamp) throws IOException {
        List<String[]> rows = rows(lines(entry(key(stamp))), task);
        if (rows.isEmpty() || !rows.stream().allMatch(this::intact)) return false;
        for (String output : task.outputs()) clear(TaskRunner.checked(root, output));
        for (String[] row : rows) materialize(root, row);
        return true;
    }

    /** Stores the declared outputs of a task that has just succeeded; false when they cannot be cached. */
    boolean store(TaskManifest.Task task, Path root, String stamp) throws IOException {
        List<String> rows = new ArrayList<>(List.of(ENTRY_MAGIC));
        for (String output : task.outputs()) {
            if (!describe(root, output, rows)) return false;
        }
        if (rows.size() > MAX_ENTRY_ROWS || bytes(rows) > MAX_ENTRY_BYTES) return false;
        for (String row : rows) storeBlob(root, row.split("\t", -1));
        Path entry = entry(key(stamp));
        Files.createDirectories(entry.getParent());
        Path temporary = Files.createTempFile(entry.getParent(), "entry-", ".tmp");
        Files.writeString(temporary, String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
        publish(temporary, entry);
        return true;
    }

    /** Appends the rows of one declared output; false for a link or special file, which is never cached. */
    private static boolean describe(Path root, String output, List<String> rows) throws IOException {
        Path path = TaskRunner.checked(root, output);
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return rows.add(fileRow(root, path));
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return false;
        List<Path> tree;
        try (Stream<Path> walk = Files.walk(path)) {
            tree = walk.sorted(Comparator.comparing(file -> relative(root, file))).toList();
        }
        for (Path file : tree) {
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) rows.add("dir\t" + relative(root, file));
            else if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) rows.add(fileRow(root, file));
            else return false;
        }
        return true;
    }

    private static String fileRow(Path root, Path file) throws IOException {
        return "file\t" + relative(root, file) + "\t" + InputStamps.fileSha256(file) + "\t" + Files.size(file);
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static long bytes(List<String> rows) {
        return rows.stream().map(row -> row.split("\t", -1)).filter(fields -> "file".equals(fields[0]))
                .mapToLong(fields -> Long.parseLong(fields[3])).sum();
    }

    /** Copies one output file into the blob store unless its content is already there. */
    private void storeBlob(Path root, String[] row) throws IOException {
        if (!"file".equals(row[0]) || Files.isRegularFile(blob(row[2]), LinkOption.NOFOLLOW_LINKS)) return;
        Path target = blob(row[2]);
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "blob-", ".tmp");
        Files.copy(TaskRunner.checked(root, row[1]), temporary, StandardCopyOption.REPLACE_EXISTING);
        if (!InputStamps.fileSha256(temporary).equals(row[2])) {
            Files.delete(temporary);
            throw new IOException("declared output changed while it was cached: " + row[1]);
        }
        publish(temporary, target);
    }

    /** Atomic publication; a concurrent build that published the same key or blob first wins. */
    private static void publish(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            Files.deleteIfExists(temporary);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw failure;
        }
    }

    /** The lines of an entry file, or none when it is absent, oversized or not UTF-8. */
    private static List<String> lines(Path entry) throws IOException {
        if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) || Files.size(entry) > MAX_ENTRY_FILE) {
            return List.of();
        }
        try {
            return Files.readAllLines(entry, StandardCharsets.UTF_8);
        } catch (CharacterCodingException invalid) {
            return List.of();
        }
    }

    /** Validated rows covering exactly the declared outputs, or none for an entry that cannot be trusted. */
    private static List<String[]> rows(List<String> lines, TaskManifest.Task task) {
        if (lines.isEmpty() || !ENTRY_MAGIC.equals(lines.get(0)) || lines.size() > MAX_ENTRY_ROWS) return List.of();
        List<String[]> rows = new ArrayList<>(lines.size());
        for (String line : lines.subList(1, lines.size())) {
            String[] row = line.split("\t", -1);
            if (!wellFormed(row) || !covered(task, row[1])) return List.of();
            rows.add(row);
        }
        boolean complete = task.outputs().stream()
                .allMatch(output -> rows.stream().anyMatch(row -> row[1].equals(output)));
        return complete ? rows : List.of();
    }

    private static boolean wellFormed(String[] row) {
        boolean shape = ("dir".equals(row[0]) && row.length == 2) || ("file".equals(row[0]) && row.length == 4
                && row[2].matches("[0-9a-f]{64}") && row[3].matches("[0-9]{1,18}"));
        try {
            return shape && TaskManifest.relativePath(row[1], false).equals(row[1]);
        } catch (IOException invalid) {
            return false;
        }
    }

    private static boolean covered(TaskManifest.Task task, String path) {
        return task.outputs().stream().anyMatch(output -> path.equals(output) || path.startsWith(output + "/"));
    }

    /** A folder row, or a file row whose stored blob has the recorded size and SHA-256; a damaged blob is dropped. */
    private boolean intact(String[] row) {
        if ("dir".equals(row[0])) return true;
        Path stored = blob(row[2]);
        try {
            if (Files.isRegularFile(stored, LinkOption.NOFOLLOW_LINKS) && Files.size(stored) == Long.parseLong(row[3])
                    && InputStamps.fileSha256(stored).equals(row[2])) {
                return true;
            }
            Files.deleteIfExists(stored);
            return false;
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** Shared by restoration and explicitly qualified provider output cleanup. */
    static void clear(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }

    private void materialize(Path root, String[] row) throws IOException {
        Path target = TaskRunner.checked(root, row[1]);
        if ("dir".equals(row[0])) {
            Files.createDirectories(target);
            return;
        }
        Files.createDirectories(target.getParent());
        Files.copy(blob(row[2]), target, StandardCopyOption.REPLACE_EXISTING);
    }
}
