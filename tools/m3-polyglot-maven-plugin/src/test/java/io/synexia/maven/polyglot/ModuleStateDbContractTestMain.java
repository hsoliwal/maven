/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Preserves current module-file/stat contracts alongside the shared leased facade. */
public final class ModuleStateDbContractTestMain {
    private static int checks;
    private static final String INPUT = "a".repeat(64);
    private static final String OUTPUT = "b".repeat(64);

    private ModuleStateDbContractTestMain() {}

    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("m3-module-db-test-");
        try {
            for (String backend : List.of("file", "embedded")) {
                compatibilityFingerprints(root.resolve(backend), backend);
                sharedFacade(root.resolve(backend + "-facade"), backend);
            }
            nativeSelectionIsExplicit(root.resolve("native"));
            activeBackendRevocation(root.resolve("active"));
            invalidAuxiliaryRevokesSuccess(root.resolve("metadata"));
            statePathContract();
            transitiveCompatibilityRevocation(root.resolve("compatibility-edges"));
            System.out.println("PASS: " + checks + " module-state facade and compatibility assertions");
        } finally {
            try (var walk = Files.walk(root)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void compatibilityFingerprints(Path module, String backend) throws Exception {
        Files.createDirectories(module);
        Path src = Files.writeString(module.resolve("source.txt"), "AAAA", StandardCharsets.UTF_8);
        Path output = Files.writeString(module.resolve("output.txt"), "result", StandardCharsets.UTF_8);
        Path tool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
        TaskManifest.Task task = new TaskManifest.Task("compile", tool.toString(), ".",
                List.of("-version"), List.of(), List.of("source.txt"), List.of("output.txt"));
        String fingerprint;
        try (ModuleStateDb first = ModuleStateDb.open(module, backend)) {
            fingerprint = first.stamp(task);
            require(!first.upToDate(task, fingerprint), "cold module state must miss");
            first.record(task, fingerprint);
        }
        Path database = module.resolve("file".equals(backend) ? ModuleStateDb.FILE : ModuleStateDb.EMBEDDED_FILE);
        require(Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS), "one module-local DB image");
        require(!Files.exists(module.resolve(".m3/polyglot-stamps/compile.sha256")),
                "DB mode must not create legacy per-task stamp files");

        try (ModuleStateDb reopened = ModuleStateDb.open(module, backend)) {
            String same = reopened.stamp(task);
            require(fingerprint.equals(same), "persistent content fingerprint across process state");
            require(reopened.upToDate(task, same), "unchanged task and output must hit");
            Object change = null;
            try { change = Files.getAttribute(src, "unix:ctime", LinkOption.NOFOLLOW_LINKS); }
            catch (UnsupportedOperationException unsupported) { /* content rehash is required */ }
            if (change != null) require(reopened.digestedFiles() == 0,
                    "retained compatibility helper reuses stable ctime/file-key metadata");
            Files.writeString(output, "tampered", StandardCharsets.UTF_8);
            require(!reopened.upToDate(task, same), "output drift invalidates previous successful build");
            Files.writeString(output, "result", StandardCharsets.UTF_8);
        }
        FileTime previousMtime = Files.getLastModifiedTime(src);
        Files.writeString(src, "BBBB", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(src, previousMtime);
        String changedStamp;
        try (ModuleStateDb changed = ModuleStateDb.open(module, backend)) {
            changedStamp = changed.stamp(task);
            require(!changedStamp.equals(fingerprint), "same-size edit with restored mtime invalidates cache");
            require(!changed.upToDate(task, changedStamp), "new content must not use old task state");
            changed.record(task, changedStamp);
        }
        try (ModuleStateDb reopened = ModuleStateDb.open(module, backend)) {
            require(reopened.upToDate(task, changedStamp), "updated successful state survives reopening");
            TaskManifest.Task flags = new TaskManifest.Task(task.name(), task.tool(), task.workingDir(),
                    List.of("-version", "-ea"), task.prerequisites(), task.inputs(), task.outputs());
            require(!reopened.stamp(flags).equals(changedStamp), "tool flags are part of the module key");
        }
        Files.delete(output);
        try (ModuleStateDb reopened = ModuleStateDb.open(module, backend)) {
            require(!reopened.upToDate(task, changedStamp), "deleted output always invalidates");
        }
        Files.write(database, new byte[] {1, 2, 3, 4});
        try (ModuleStateDb corrupt = ModuleStateDb.open(module, backend)) {
            require(!corrupt.upToDate(task, changedStamp), "malformed state is a safe cache miss");
        }
    }

    private static void sharedFacade(Path root, String backend) throws Exception {
        Files.createDirectories(root);
        String taskKey = "task-" + INPUT;
        String lifecycleKey = "lifecycle-" + OUTPUT;
        String generation;
        try (ModuleStateDb store = ModuleStateDb.open(root, backend, false, ignored -> {})) {
            store.record(taskKey, INPUT, OUTPUT);
            require(store.lookup(taskKey) == null, "disabled facade records no success");
        }
        Path file = root.resolve("file".equals(backend) ? ModuleStateDb.FILE : ModuleStateDb.EMBEDDED_FILE);
        require(!Files.exists(file), "disabled open creates no image");
        try (ModuleStateDb store = ModuleStateDb.open(root, backend, ignored -> {})) {
            store.put(taskKey, INPUT, OUTPUT);
            store.record(lifecycleKey, OUTPUT, INPUT);
            require(store.hit(taskKey, INPUT, OUTPUT), "public put/hit facade");
            require(!store.hit(taskKey, OUTPUT, INPUT), "digest mismatch refuses reuse");
            require(store.lookup(lifecycleKey) != null, "lifecycle and polyglot namespaces coexist");
            store.invalidatePrefix("lifecycle-");
            require(store.lookup(lifecycleKey) == null, "public prefix invalidation revokes lifecycle success");
            require(store.hit(taskKey, INPUT, OUTPUT), "public prefix invalidation preserves polyglot success");
            store.record(lifecycleKey, OUTPUT, INPUT);
            generation = store.generation(taskKey);
            require(generation != null && generation.matches("[0-9a-f]{64}"), "successful generation is a SHA-256");
            String[] copy = store.lookup(taskKey);
            copy[0] = OUTPUT;
            require(store.lookup(taskKey)[0].equals(INPUT), "public lookup is defensive");
        }
        try (ModuleStateDb reopened = ModuleStateDb.open(root, backend)) {
            require(generation.equals(reopened.generation(taskKey)), "generation survives clean reopen");
            reopened.invalidate(List.of(taskKey));
            require(reopened.lookup(taskKey) == null && reopened.generation(taskKey) == null,
                    "revocation clears lookup and generation");
            require(Arrays.equals(reopened.lookup(lifecycleKey), new String[] {OUTPUT, INPUT}),
                    "unaffected namespace remains committed");
            reopened.invalidate(lifecycleKey);
        }
        try (ModuleStateDb reopened = ModuleStateDb.open(root, backend)) {
            require(reopened.lookup(taskKey) == null && reopened.lookup(lifecycleKey) == null,
                    "all public revocations are durable");
        }
    }

    private static void nativeSelectionIsExplicit(Path root) throws Exception {
        Files.createDirectories(root);
        boolean installed;
        try { Class.forName("com.synexia.mindex.db.MIndexDB"); installed = true; }
        catch (ClassNotFoundException missing) { installed = false; }
        if (!installed) {
            boolean[] contextRequested = {false};
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            ClassLoader context = new ClassLoader(previous) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.equals("com.synexia.mindex.db.MIndexDB")) {
                        contextRequested[0] = true;
                        throw new ClassNotFoundException(name);
                    }
                    return super.loadClass(name, resolve);
                }
            };
            thread.setContextClassLoader(context);
            try {
                try (ModuleStateDb unexpected = ModuleStateDb.open(root, "m3db")) {
                    unexpected.lookup("compile");
                    throw new AssertionError("missing context provider silently downgraded");
                } catch (IOException expected) { checks++; }
            } finally {
                thread.setContextClassLoader(previous);
            }
            require(contextRequested[0], "native provider discovery first visits the plugin context realm");
            for (String backend : List.of("m3db", "native")) {
                try (ModuleStateDb unexpected = ModuleStateDb.open(root, backend)) {
                    unexpected.lookup("compile");
                    throw new AssertionError("missing native provider silently downgraded");
                } catch (IOException expected) { checks++; }
            }
            require(!Files.exists(root.resolve(ModuleStateDb.EMBEDDED_FILE)), "native choice never falls back to embedded");
            require(!Files.exists(root.resolve(ModuleStateDb.FILE)), "native choice never falls back to file");
        }
        try {
            ModuleStateDb.open(root, "unknown").close();
            throw new AssertionError("unknown backend accepted");
        } catch (IllegalArgumentException expected) { checks++; }
    }

    private static void invalidAuxiliaryRevokesSuccess(Path root) throws Exception {
        Files.createDirectories(root);
        try (M3DbModuleStore store = M3DbModuleStore.open(root, true, ignored -> {})) {
            store.auxiliary(new byte[] {1, 2, 3, 4});
            store.record("compile", INPUT, OUTPUT);
        }
        try (ModuleStateDb store = ModuleStateDb.open(root, "embedded")) {
            require(store.lookup("compile") == null, "invalid fingerprint metadata cannot retain task authority");
        }
        try (ModuleStateDb store = ModuleStateDb.open(root, "embedded")) {
            require(store.lookup("compile") == null, "metadata failure revocation survives reopening");
        }
    }

    private static void activeBackendRevocation(Path root) throws Exception {
        Files.createDirectories(root);
        ModuleStateDb.revokePrefix(root, "lifecycle-", ignored -> {});
        require(!Files.exists(root.resolve(ModuleStateDb.FILE))
                && !Files.exists(root.resolve(ModuleStateDb.EMBEDDED_FILE)), "revoking absent state creates no DB");
        for (String backend : List.of("file", "embedded")) {
            try (ModuleStateDb store = ModuleStateDb.open(root, backend)) {
                store.record("task-build", INPUT, OUTPUT);
                store.record("lifecycle-compile", OUTPUT, INPUT);
                try {
                    ModuleStateDb.revokePrefix(root, "lifecycle-", ignored -> {});
                    throw new AssertionError("active revocation ignored the lifetime lease");
                } catch (IOException expected) { checks++; }
            }
            byte[] marker = Files.readAllBytes(root.resolve(ModuleStateDb.MARKER));
            ModuleStateDb.revokePrefix(root, "lifecycle-", ignored -> {});
            require(Arrays.equals(marker, Files.readAllBytes(root.resolve(ModuleStateDb.MARKER))),
                    "lifecycle-off revocation retains current backend and generation");
            try (ModuleStateDb store = ModuleStateDb.open(root, backend)) {
                require(store.hit("task-build", INPUT, OUTPUT), "active-backend revocation preserves warm task receipt");
                require(store.lookup("lifecycle-compile") == null, "active-backend lifecycle receipt is revoked");
            }
        }
        try { Class.forName("com.synexia.mindex.db.MIndexDB"); }
        catch (ClassNotFoundException absent) {
            String before = "M3-MODULE-STATE-2\nm3db\n" + "0".repeat(32) + "\nclean\n";
            Files.writeString(root.resolve(ModuleStateDb.MARKER), before, StandardCharsets.US_ASCII);
            Path nativePath = Files.createDirectories(root.resolve(ModuleStateDb.NATIVE_DIRECTORY));
            Path untouched = Files.writeString(nativePath.resolve("provider-owned-data"), "unchanged");
            List<String> messages = new ArrayList<>();
            ModuleStateDb.revokePrefix(root, "lifecycle-", messages::add);
            List<String> after = Files.readAllLines(root.resolve(ModuleStateDb.MARKER), StandardCharsets.US_ASCII);
            require(after.get(1).equals("m3db") && !after.get(2).equals("0".repeat(32)),
                    "missing active native provider revokes generation without changing backend");
            require(Files.readString(untouched).equals("unchanged"), "native revocation never alters provider-owned image");
            require(messages.stream().anyMatch(value -> value.contains("provider unavailable")),
                    "native generation-only revocation reports its reason");
        }
    }

    /** Preserve current4d's direct task-helper producer/consumer revocation contract. */
    private static void transitiveCompatibilityRevocation(Path module) throws Exception {
        Files.createDirectories(module);
        Path tool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
        Files.writeString(module.resolve("source.txt"), "src");
        Files.writeString(module.resolve("product.txt"), "build");
        Files.writeString(module.resolve("consumer.txt"), "dependent");
        String manifest = TaskManifest.MAGIC + "\n"
                + "task\tcompile\t" + tool + "\t.\narg\tcompile\t-version\n"
                + "input\tcompile\tsource.txt\noutput\tcompile\tproduct.txt\n"
                + "task\tconsumer\t" + tool + "\t.\ndep\tconsumer\tcompile\n"
                + "arg\tconsumer\t-version\ninput\tconsumer\tproduct.txt\noutput\tconsumer\tconsumer.txt\n";
        TaskManifest graph = TaskManifest.load(Files.writeString(module.resolve("m3-build.tsv"), manifest));
        TaskManifest.Task producer = graph.orderAll().stream().filter(task -> task.name().equals("compile")).findFirst().orElseThrow();
        TaskManifest.Task consumer = graph.orderAll().stream().filter(task -> task.name().equals("consumer")).findFirst().orElseThrow();
        String first, second;
        try (ModuleStateDb state = ModuleStateDb.open(module, "file")) {
            first = state.stamp(producer);
            state.record(producer, first);
            second = state.stamp(consumer);
            state.record(consumer, second);
            require(state.upToDate(producer, first) && state.upToDate(consumer, second), "compatibility task helper successes established");
            state.invalidateTransitive(graph, "compile");
        }
        try (ModuleStateDb state = ModuleStateDb.open(module, "file")) {
            require(!state.upToDate(producer, first) && !state.upToDate(consumer, second), "transitive compatibility revocation persists before execution");
        }
    }

    private static void statePathContract() {
        for (String path : List.of(ModuleStateDb.FILE, ModuleStateDb.EMBEDDED_FILE, ModuleStateDb.LOCK,
                ModuleStateDb.MARKER, ModuleStateDb.NATIVE_DIRECTORY + "/blobs/hash", ".m3/module-state-123.tmp")) {
            require(ModuleStateDb.statePath(path), "owned state path excluded from build inputs: " + path);
        }
        for (String path : List.of(ModuleStateDb.SQLITE_FILE, ModuleStateDb.SQLITE_FILE + "-journal",
                ModuleStateDb.SQLITE_FILE + "-wal", ModuleStateDb.SQLITE_FILE + "-shm", ModuleStateDb.H2_FILE,
                ModuleStateDb.H2_FILE + ".newFile", ".m3/module-state.trace.db", ".m3/module-state.lock.db",
                ".m3/module-state.temp.db", ".m3/module.m3db.lock")) {
            require(ModuleStateDb.statePath(path), "JDBC and migration sidecar path is reserved: " + path);
        }
        require(!ModuleStateDb.statePath(".m3/user-config.json"), "ordinary user .m3 content remains an input");
        require(!ModuleStateDb.statePath("src/module-state.java"), "ordinary source remains an input");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
