/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;

/** Portable, dependency-free contract tests for one DB image per reactor module. */
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

            ModuleStateDb first = ModuleStateDb.open(module, "file");
            String fingerprint = first.stamp(task);
            require(!first.upToDate(task, fingerprint), "cold module state must miss");
            first.record(task, fingerprint);
            Path database = module.resolve(ModuleStateDb.FILE);
            require(Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS), "one module-local DB image");
            require(!Files.exists(module.resolve(InputStamps.DIRECTORY).resolve("compile.sha256")),
                    "DB mode must not create legacy per-task stamp files");

            ModuleStateDb reopened = ModuleStateDb.open(module, "file");
            String same = reopened.stamp(task);
            require(fingerprint.equals(same), "persistent content fingerprint across process state");
            require(reopened.upToDate(task, same), "unchanged task and output must hit");

            // On POSIX, a stable ctime + fileKey avoids rereading unchanged source/tool bytes.
            Object change = null;
            try { change = Files.getAttribute(src, "unix:ctime", LinkOption.NOFOLLOW_LINKS); }
            catch (UnsupportedOperationException unsupported) { /* rehash is required */ }
            if (change != null) require(reopened.digestedFiles() == 0, "unchanged files should use M3DB metadata");

            Files.writeString(output, "tampered", StandardCharsets.UTF_8);
            require(!reopened.upToDate(task, same), "output drift invalidates previous successful build");
            Files.writeString(output, "result", StandardCharsets.UTF_8);

            FileTime previousMtime = Files.getLastModifiedTime(src);
            Files.writeString(src, "BBBB", StandardCharsets.UTF_8);
            Files.setLastModifiedTime(src, previousMtime);
            ModuleStateDb changed = ModuleStateDb.open(module, "file");
            String changedStamp = changed.stamp(task);
            require(!changedStamp.equals(fingerprint), "same-size edit with restored mtime invalidates cache");
            require(!changed.upToDate(task, changedStamp), "new content must not use old task state");
            changed.record(task, changedStamp);
            require(ModuleStateDb.open(module, "file").upToDate(task, changedStamp),
                    "updated successful state survives reopening");

            TaskManifest.Task flags = new TaskManifest.Task(task.name(), task.tool(), task.workingDir(),
                    List.of("-version", "-ea"), task.prerequisites(), task.inputs(), task.outputs());
            require(!ModuleStateDb.open(module, "file").stamp(flags).equals(changedStamp),
                    "tool flags are part of the module key");

            Files.delete(output);
            require(!ModuleStateDb.open(module, "file").upToDate(task, changedStamp),
                    "deleted output always invalidates");
            boolean missingOutputRefused = false;
            try {
                ModuleStateDb.open(module, "file").record(task, changedStamp);
            } catch (java.io.IOException expected) {
                missingOutputRefused = true;
            }
            require(missingOutputRefused, "missing outputs may never be recorded as successful");

            Files.write(database, new byte[] {1, 2, 3, 4});
            ModuleStateDb corrupt = ModuleStateDb.open(module, "file");
            require(!corrupt.upToDate(task, changedStamp), "malformed state is a safe cache miss");
            verifiesTransitiveRevocation(root);

            System.out.println("PASS: per-module persistence, edits, outputs, revocation, corruption");
        } finally {
            try (var walk = Files.walk(root)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    /** A failed producer must not leave a previous consumer success usable. */
    private static void verifiesTransitiveRevocation(Path root) throws Exception {
        Path module = Files.createDirectories(root.resolve("reactor-edges"));
        Path tool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
        Files.writeString(module.resolve("source.txt"), "src");
        Files.writeString(module.resolve("product.txt"), "build");
        Files.writeString(module.resolve("consumer.txt"), "dependent");
        String manifest = TaskManifest.MAGIC + "\n"
                + "task\tcompile\t" + tool + "\t.\n"
                + "arg\tcompile\t-version\n"
                + "input\tcompile\tsource.txt\n"
                + "output\tcompile\tproduct.txt\n"
                + "task\tconsumer\t" + tool + "\t.\n"
                + "dep\tconsumer\tcompile\n"
                + "arg\tconsumer\t-version\n"
                + "input\tconsumer\tproduct.txt\n"
                + "output\tconsumer\tconsumer.txt\n";
        Path manifestPath = Files.writeString(module.resolve("m3-build.tsv"), manifest);
        TaskManifest graph = TaskManifest.load(manifestPath);
        TaskManifest.Task first = graph.orderAll().stream()
                .filter(task -> "compile".equals(task.name())).findFirst().orElseThrow();
        TaskManifest.Task dependent = graph.orderAll().stream()
                .filter(task -> "consumer".equals(task.name())).findFirst().orElseThrow();
        ModuleStateDb state = ModuleStateDb.open(module, "file");
        String producer = state.stamp(first);
        state.record(first, producer);
        String consumer = state.stamp(dependent);
        state.record(dependent, consumer);
        require(state.upToDate(first, producer) && state.upToDate(dependent, consumer),
                "producer and dependent cache records were established");
        state.invalidateTransitive(graph, "compile");
        ModuleStateDb reopened = ModuleStateDb.open(module, "file");
        require(!reopened.upToDate(first, producer) && !reopened.upToDate(dependent, consumer),
                "producer invalidation revokes transitive dependent records");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
