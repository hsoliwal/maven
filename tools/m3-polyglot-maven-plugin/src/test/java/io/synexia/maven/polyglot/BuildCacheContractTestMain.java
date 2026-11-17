/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Standalone no-dependency verification of the opt-in local build cache, also run by the Maven verify goal. */
public final class BuildCacheContractTestMain {
    private static final String TOOL = "import java.nio.file.*;\n"
            + "public class Tool { public static void main(String[] a) throws Exception {\n"
            + "  Files.createDirectories(Path.of(\"out/sub\")); Files.createDirectories(Path.of(\"out/empty\"));\n"
            + "  Files.writeString(Path.of(\"out/result.txt\"), Files.readString(Path.of(\"in.txt\")) + \"!\");\n"
            + "  Files.writeString(Path.of(\"out/sub/nested.txt\"), \"nested\");\n"
            + "  Files.writeString(Path.of(\"single.txt\"), \"single\");\n"
            + "  Files.writeString(Path.of(\"runs.log\"), \"run\\n\", StandardOpenOption.CREATE, StandardOpenOption.APPEND);"
            + " } }\n";
    private static int passed;

    private BuildCacheContractTestMain() {}

    public static void main(String[] ignored) throws Exception {
        Path temp = Files.createTempDirectory("m3-polyglot-cache-test-");
        try {
            Path cache = temp.resolve("cache");
            restoresOutputsInAFreshWorkspace(temp, cache);
            missesOnChangedInputsAndTamperedBlobs(temp, cache);
            refusesEntriesOutsideDeclaredOutputs(temp, cache);
            staysOffAndSkipsUndeclaredTasks(temp);
            combinesWithIncrementalAndCli(temp, cache);
            combinesWithModuleStateFile(temp, cache);
            combinesWithOwnerM3db(temp, cache);
            System.out.println("PASS: " + passed + " M3 polyglot build cache contract cases");
        } finally {
            erase(temp);
        }
    }

    private static void restoresOutputsInAFreshWorkspace(Path temp, Path cache) throws Exception {
        Path first = workspace(temp, "first", "one");
        List<String> log = run(first, cache, false);
        require(runs(first) == 1 && log.contains("CACHED build"), "a miss runs the tool and stores its outputs");
        Path second = workspace(temp, "second", "one");
        Files.createDirectories(second.resolve("out/stale"));
        Files.writeString(second.resolve("out/stale/old.txt"), "stale", StandardCharsets.UTF_8);
        log = run(second, cache, false);
        require(runs(second) == 0 && log.stream().anyMatch(row -> row.startsWith("FROM-CACHE build")),
                "a hit in another checkout restores without starting the tool");
        require(sameOutputs(first, second), "restored outputs are byte-identical");
        require(!Files.exists(second.resolve("out/stale")), "a hit replaces a declared output folder as a whole");
        require(Files.isDirectory(second.resolve("out/empty")), "empty output folders are restored");
        passed++;
    }

    private static void missesOnChangedInputsAndTamperedBlobs(Path temp, Path cache) throws Exception {
        Path changed = workspace(temp, "changed", "two");
        run(changed, cache, false);
        require(runs(changed) == 1 && read(changed, "out/result.txt").equals("two!"), "a changed input is a miss");
        String sha = InputStamps.fileSha256(changed.resolve("out/result.txt"));
        Files.writeString(new BuildCache(cache).blob(sha), "tampered", StandardCharsets.UTF_8);
        Path victim = workspace(temp, "victim", "two");
        List<String> log = run(victim, cache, false);
        require(runs(victim) == 1 && log.stream().noneMatch(row -> row.startsWith("FROM-CACHE")),
                "an entry whose stored bytes do not match their SHA-256 is a miss");
        require(read(victim, "out/result.txt").equals("two!"), "the tool rebuilds after a corrupt entry");
        require(InputStamps.fileSha256(new BuildCache(cache).blob(sha)).equals(sha), "the damaged blob is replaced");
        passed++;
    }

    private static void refusesEntriesOutsideDeclaredOutputs(Path temp, Path cache) throws Exception {
        Path poisoned = workspace(temp, "poisoned", "three");
        TaskManifest.Task task = manifest(poisoned).order("build").get(0);
        String stamp = InputStamps.stamp(task, poisoned);
        Path entry = new BuildCache(cache).entry(BuildCache.key(stamp));
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, BuildCache.ENTRY_MAGIC + "\ndir\tout\nfile\tsingle.txt\t" + "0".repeat(64)
                + "\t1\nfile\tin.txt\t" + "0".repeat(64) + "\t1\n", StandardCharsets.UTF_8);
        require(!new BuildCache(cache).restore(task, poisoned, stamp), "a row outside the declared outputs is a miss");
        require(read(poisoned, "in.txt").equals("three"), "a refused entry changes nothing");
        Files.writeString(entry, "not an entry\n", StandardCharsets.UTF_8);
        require(!new BuildCache(cache).restore(task, poisoned, stamp), "a malformed entry is a miss");
        require(!BuildCache.key(stamp).equals(stamp) && BuildCache.key(stamp).equals(BuildCache.key(stamp)),
                "the key extends the stamp deterministically");
        passed++;
    }

    private static void staysOffAndSkipsUndeclaredTasks(Path temp) throws Exception {
        Path plain = workspace(temp, "plain", "four");
        List<String> log = new ArrayList<>();
        TaskRunner.run(manifest(plain), plain, "build", false, Duration.ofSeconds(60), false, false, log::add);
        require(runs(plain) == 1 && log.stream().noneMatch(row -> row.contains("CACHE")), "off unless a folder is set");
        Path cache = temp.resolve("cache-undeclared");
        String body = "task\tbuild\t" + java() + "\t.\narg\tbuild\tTool.java\noutput\tbuild\tsingle.txt\n";
        TaskManifest undeclared = TaskManifest.load(Files.writeString(plain.resolve("m3-build.tsv"),
                TaskManifest.MAGIC + "\n" + body, StandardCharsets.UTF_8));
        TaskRunner.run(undeclared, plain, "build", false, Duration.ofSeconds(60), false, false, cache, log::add);
        require(!Files.exists(cache) && log.stream().noneMatch(row -> row.contains("CACHED")),
                "a task without declared inputs is never cached");
        passed++;
    }

    private static void combinesWithIncrementalAndCli(Path temp, Path cache) throws Exception {
        Path cli = workspace(temp, "cli", "one");
        List<String> log = new ArrayList<>();
        PolyglotCli.execute(new String[] {"run", cli.toString(), cli.resolve("m3-build.tsv").toString(), "build",
                "--incremental", "--build-cache", cache.toString()}, log::add);
        require(runs(cli) == 0 && log.stream().anyMatch(row -> row.startsWith("FROM-CACHE build")), "CLI flag");
        require(Files.exists(InputStamps.file(manifest(cli).order("build").get(0), cli)), "a hit records the stamp");
        require(run(cli, cache, true).stream().anyMatch(row -> row.startsWith("UP-TO-DATE build")) && runs(cli) == 0,
                "after a hit, incremental mode finds the task up to date");
        refused(new String[] {"plan", cli.toString(), "m.tsv", "build", "--build-cache", cache.toString()});
        refused(new String[] {"run", cli.toString(), "m.tsv", "build", "--build-cache"});
        refused(new String[] {"run", cli.toString(), "m.tsv", "build", "--build-cache", "a", "--build-cache", "b"});
        refused(new String[] {"run", cli.toString(), "m.tsv", "build", "--build-cache", "--incremental"});
        passed++;
    }

    private static void combinesWithModuleStateFile(Path temp, Path cache) throws Exception {
        Path module = workspace(temp, "module-state", "one");
        String[] arguments = {"run", module.toString(), module.resolve("m3-build.tsv").toString(), "build",
                "--incremental", "--state-backend", "file", "--build-cache", cache.toString()};
        List<String> log = new ArrayList<>();
        PolyglotCli.execute(arguments, log::add);
        require(runs(module) == 0 && log.stream().anyMatch(row -> row.startsWith("FROM-CACHE build")),
                "the key does not depend on the state backend");
        log.clear();
        PolyglotCli.execute(arguments, log::add);
        require(runs(module) == 0 && log.stream().anyMatch(row -> row.startsWith("UP-TO-DATE build")),
                "a hit records the stamp in the module state file");
        passed++;
    }

    /** Decision 55: a hit under "m3db" records the verified generation in the owner M3IndexDB image. */
    private static void combinesWithOwnerM3db(Path temp, Path cache) throws Exception {
        Path module = workspace(temp, "module-m3db", "one");
        String[] arguments = {"run", module.toString(), module.resolve("m3-build.tsv").toString(), "build",
                "--incremental", "--state-backend", "m3db", "--build-cache", cache.toString()};
        List<String> log = new ArrayList<>();
        PolyglotCli.execute(arguments, log::add);
        require(runs(module) == 0 && log.stream().anyMatch(row -> row.startsWith("FROM-CACHE build")),
                "the key does not depend on the m3db state backend");
        require(Files.isRegularFile(module.resolve(M3DbModuleStore.FILE)), "a hit publishes the module M3DB image");
        log.clear();
        PolyglotCli.execute(arguments, log::add);
        require(runs(module) == 0 && log.stream().anyMatch(row -> row.startsWith("UP-TO-DATE build")),
                "after a hit, the m3db backend verifies inputs and outputs and finds the task up to date");
        passed++;
    }

    private static Path workspace(Path temp, String name, String input) throws IOException {
        Path root = Files.createDirectories(temp.resolve(name));
        Files.writeString(root.resolve("Tool.java"), TOOL, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("in.txt"), input, StandardCharsets.UTF_8);
        String body = "task\tbuild\t" + java() + "\t.\narg\tbuild\tTool.java\n"
                + "input\tbuild\tTool.java\ninput\tbuild\tin.txt\noutput\tbuild\tout\noutput\tbuild\tsingle.txt\n";
        Files.writeString(root.resolve("m3-build.tsv"), TaskManifest.MAGIC + "\n" + body, StandardCharsets.UTF_8);
        return root;
    }

    private static List<String> run(Path root, Path cache, boolean incremental) throws Exception {
        List<String> log = new ArrayList<>();
        TaskRunner.run(manifest(root), root, "build", false, Duration.ofSeconds(60), false, incremental, cache, log::add);
        return log;
    }

    private static TaskManifest manifest(Path root) throws IOException {
        return TaskManifest.load(root.resolve("m3-build.tsv"));
    }

    private static boolean sameOutputs(Path left, Path right) throws IOException {
        for (String path : List.of("out/result.txt", "out/sub/nested.txt", "single.txt")) {
            if (!InputStamps.fileSha256(left.resolve(path)).equals(InputStamps.fileSha256(right.resolve(path)))) {
                return false;
            }
        }
        return true;
    }

    private static int runs(Path root) throws IOException {
        Path log = root.resolve("runs.log");
        return Files.exists(log) ? Files.readAllLines(log, StandardCharsets.UTF_8).size() : 0;
    }

    private static String read(Path root, String path) throws IOException {
        return Files.readString(root.resolve(path), StandardCharsets.UTF_8);
    }

    /** The real launcher file name: the module-state backend fingerprints the tool file itself. */
    private static String java() {
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    private static void refused(String[] arguments) throws Exception {
        boolean failed = false;
        try {
            PolyglotCli.execute(arguments, line -> {});
        } catch (IllegalArgumentException expected) {
            failed = true;
        }
        require(failed, "invalid invocation must be refused: " + List.of(arguments));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void erase(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
