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
import java.util.List;

/** Standalone no-dependency verification, also run by the Maven verify goal. */
public final class TaskEngineContractTestMain {
    private static int passed;

    private TaskEngineContractTestMain() {}

    public static void main(String[] ignored) throws Exception {
        Path temp = Files.createTempDirectory("m3-polyglot-test-");
        try {
            validatesDeterministicGraph(temp);
            refusesCyclesAndUndefinedTasks(temp);
            refusesDuplicateOutputsAndTraversal(temp);
            preservesArgumentsAndNoShell(temp);
            checksInputsOutputsWithoutSkipping(temp);
            skipsOnlyUnchangedDeclaredInputsWhenIncremental(temp);
            checksInvalidUTF8(temp);
            checksSymlinkRefusal(temp);
            System.out.println("PASS: " + passed + " M3 polyglot contract cases");
        } finally {
            // A temporary directory owned by this test; CI can inspect failures before cleanup.
            erase(temp);
        }
    }

    private static void validatesDeterministicGraph(Path workspace) throws Exception {
        String suffix = "task\tall\t-\t.\n"
                + "dep\tall\ta\n"
                + "dep\tall\tb\n"
                + "task\ta\t-\t.\n"
                + "dep\ta\tshared\n"
                + "task\tb\t-\t.\n"
                + "dep\tb\tshared\n"
                + "task\tshared\t-\t.\n";
        TaskManifest manifest = load(workspace, "ordered", suffix);
        require(names(manifest.order("all")).equals(List.of("shared", "a", "b", "all")), "ordered graph");
        require(manifest.orderAll().size() == 4, "all graph nodes");
        require(names(manifest.order("b")).equals(List.of("shared", "b")), "prerequisite subset");
        passed++;
    }

    private static void refusesCyclesAndUndefinedTasks(Path workspace) throws Exception {
        reject(workspace, "selfcycle", "task\ta\t-\t.\ndep\ta\ta\n");
        reject(workspace, "cycle", "task\ta\t-\t.\ntask\tb\t-\t.\ndep\ta\tb\ndep\tb\ta\n");
        reject(workspace, "missing", "task\ta\t-\t.\ndep\ta\tmissing\n");
        reject(workspace, "arg-without-tool", "task\ta\t-\t.\narg\ta\tmessage\n");
        passed++;
    }

    private static void refusesDuplicateOutputsAndTraversal(Path workspace) throws Exception {
        reject(workspace, "outputs", "task\ta\t-\t.\ntask\tb\t-\t.\noutput\ta\ttarget/file\noutput\tb\ttarget/file\n");
        reject(workspace, "output-traversal", "task\ta\t-\t.\noutput\ta\t../other\n");
        reject(workspace, "workdir-traversal", "task\ta\t-\t../outside\n");
        reject(workspace, "invalid-duplicate", "task\ta\t-\t.\ntask\ta\t-\t.\n");
        reject(workspace, "arg-newline", "task\ta\t-\t.\narg\ta\tone\ttwo\n");
        passed++;
    }

    private static void preservesArgumentsAndNoShell(Path workspace) throws Exception {
        String jdk = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String suffix = "task\tversion\t" + jdk + "\t.\n"
                + "arg\tversion\t-version\n"
                + "task\tall\t-\t.\n"
                + "dep\tall\tversion\n";
        TaskManifest manifest = load(workspace, "literal-tool", suffix);
        List<String> observed = new ArrayList<>();
        List<String> planned = TaskRunner.run(manifest, workspace, "all", true,
                Duration.ofSeconds(5), observed::add);
        require(planned.equals(List.of("version", "all")), "planned order");
        require(observed.stream().allMatch(row -> row.startsWith("PLAN ")), "plan must not start tools");
        List<String> ran = TaskRunner.run(manifest, workspace, "all", false,
                Duration.ofSeconds(10), ignored -> {});
        require(ran.equals(planned), "real process executed in graph order");
        passed++;
    }

    private static void checksInputsOutputsWithoutSkipping(Path workspace) throws Exception {
        String jdk = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String suffix = "task\tfirst\t" + jdk + "\t.\n"
                + "arg\tfirst\t-version\n"
                + "input\tfirst\tinput.txt\n"
                + "output\tfirst\toutput.txt\n";
        TaskManifest manifest = load(workspace, "with-io", suffix);
        Files.writeString(workspace.resolve("input.txt"), "input", StandardCharsets.UTF_8);
        expectFailure(() -> TaskRunner.run(manifest, workspace, "first", false,
                Duration.ofSeconds(10), ignored -> {}));
        Files.writeString(workspace.resolve("output.txt"), "output", StandardCharsets.UTF_8);
        require(TaskRunner.run(manifest, workspace, "first", false, Duration.ofSeconds(10), ignored -> {})
                .equals(List.of("first")), "output presence checked");
        Files.delete(workspace.resolve("input.txt"));
        expectFailure(() -> TaskRunner.run(manifest, workspace, "first", false,
                Duration.ofSeconds(10), ignored -> {}));
        passed++;
    }

    private static void skipsOnlyUnchangedDeclaredInputsWhenIncremental(Path workspace) throws Exception {
        Path project = Files.createDirectories(workspace.resolve("incremental"));
        String jdk = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // The "native tool": copies its input file and every file of its input folder, and logs each start.
        Files.writeString(project.resolve("Copy.java"), "import java.nio.file.*;\n"
                + "public class Copy { public static void main(String[] a) throws Exception {\n"
                + "  StringBuilder all = new StringBuilder(Files.readString(Path.of(a[0])));\n"
                + "  try (var s = Files.list(Path.of(a[1]))) { for (Path p : s.sorted().toList()) all.append(Files.readString(p)); }\n"
                + "  Files.writeString(Path.of(a[2]), all);\n"
                + "  Files.writeString(Path.of(a[3]), \"run\\n\", StandardOpenOption.CREATE, StandardOpenOption.APPEND); } }\n",
                StandardCharsets.UTF_8);
        Files.writeString(project.resolve("in.txt"), "one", StandardCharsets.UTF_8);
        Files.createDirectories(project.resolve("src/nested"));
        Files.writeString(project.resolve("src/nested/b.txt"), "b", StandardCharsets.UTF_8);
        String definition = "task\tcopy\t" + jdk + "\t.\n" + "arg\tcopy\tCopy.java\n" + "arg\tcopy\tin.txt\n"
                + "arg\tcopy\tsrc/nested\n" + "arg\tcopy\tout.txt\n" + "arg\tcopy\truns.log\n"
                + "input\tcopy\tCopy.java\n" + "input\tcopy\tin.txt\n" + "input\tcopy\tsrc\n" + "output\tcopy\tout.txt\n";
        TaskManifest manifest = TaskManifest.load(manifestFile(project, definition));
        List<String> log = new ArrayList<>();
        incremental(manifest, project, log);
        require(runs(project) == 1, "first incremental run starts the tool");
        require(Files.isRegularFile(project.resolve(".m3/module.m3db")), "module state recorded");
        log.clear();
        require(incremental(manifest, project, log).equals(List.of("copy")), "an up-to-date task still counts as satisfied");
        require(runs(project) == 1 && log.stream().anyMatch(row -> row.startsWith("UP-TO-DATE copy")),
                "unchanged declared inputs skip the tool");
        Files.writeString(project.resolve("in.txt"), "two", StandardCharsets.UTF_8);
        incremental(manifest, project, log);
        require(runs(project) == 2, "a changed input file runs the tool again");
        Files.writeString(project.resolve("src/nested/b.txt"), "B", StandardCharsets.UTF_8);
        incremental(manifest, project, log);
        require(runs(project) == 3 && Files.readString(project.resolve("out.txt")).equals("twoB"),
                "a changed file inside an input folder runs the tool again");
        Files.delete(project.resolve("out.txt"));
        incremental(manifest, project, log);
        require(runs(project) == 4, "a missing declared output runs the tool again");
        TaskManifest changed = TaskManifest.load(manifestFile(project, definition.replace("arg\tcopy\truns.log\n",
                "arg\tcopy\truns.log\narg\tcopy\textra\n")));
        incremental(changed, project, log);
        require(runs(project) == 5, "a changed task definition runs the tool again");
        TaskRunner.run(manifest, project, "copy", false, Duration.ofSeconds(60), false, ignored -> {});
        require(runs(project) == 6, "without incremental mode the tool always runs");
        passed++;
    }

    private static List<String> incremental(TaskManifest manifest, Path project, List<String> log) throws Exception {
        return TaskRunner.run(manifest, project, "copy", false, Duration.ofSeconds(60), false, true, log::add);
    }

    private static Path manifestFile(Path project, String body) throws IOException {
        return Files.writeString(project.resolve("m3-build.tsv"), TaskManifest.MAGIC + "\n" + body, StandardCharsets.UTF_8);
    }

    private static int runs(Path project) throws IOException {
        Path log = project.resolve("runs.log");
        return Files.exists(log) ? Files.readAllLines(log, StandardCharsets.UTF_8).size() : 0;
    }

    private static void checksInvalidUTF8(Path workspace) throws Exception {
        Path invalid = workspace.resolve("invalid-utf8.tsv");
        Files.write(invalid, new byte[] {'M', '3', '-', (byte) 0xc3, 0x28, '\n'});
        expectFailure(() -> TaskManifest.load(invalid));
        reject(workspace, "missing-final-lf", "task\ta\t-\t.");
        reject(workspace, "crlf", "task\ta\t-\t.\r\n");
        passed++;
    }

    private static void checksSymlinkRefusal(Path workspace) throws Exception {
        Path outside = Files.createTempDirectory("m3-polyglot-outside-");
        try {
            Path link = workspace.resolve("linked");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
                // Some Windows or sandbox hosts cannot create symlinks.
                return;
            }
            String jdk = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            TaskManifest manifest = load(workspace, "link-test", "task\tt\t" + jdk + "\t.\n"
                    + "arg\tt\t-version\ninput\tt\tlinked/file.txt\n");
            expectFailure(() -> TaskRunner.run(manifest, workspace, "t", false,
                    Duration.ofSeconds(5), ignored -> {}));
            passed++;
        } finally {
            erase(outside);
        }
    }

    private static TaskManifest load(Path root, String name, String body) throws IOException {
        Path file = root.resolve(name + ".tsv");
        Files.writeString(file, TaskManifest.MAGIC + "\n" + body, StandardCharsets.UTF_8);
        return TaskManifest.load(file);
    }

    private static void reject(Path root, String name, String body) throws IOException {
        expectFailure(() -> load(root, name, body));
    }

    private static List<String> names(List<TaskManifest.Task> tasks) {
        return tasks.stream().map(TaskManifest.Task::name).toList();
    }

    @FunctionalInterface
    private interface Checked {
        void execute() throws Exception;
    }

    private static void expectFailure(Checked command) {
        boolean rejected = false;
        try {
            command.execute();
        } catch (IOException | IllegalArgumentException expected) {
            rejected = true;
        } catch (Exception unexpected) {
            throw new AssertionError("unexpected exception type", unexpected);
        }
        require(rejected, "expected strict failure");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void erase(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
