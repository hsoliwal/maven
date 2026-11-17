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
import java.util.Set;

/** No-dependency, Maven-verify contract suite for the additive M3 tool SPI. */
public final class M3ToolProvidersContractTestMain {
    private static int passed;

    private M3ToolProvidersContractTestMain() {}

    /** Test-scope third-party provider, registered only by the test ServiceLoader descriptor. */
    public static final class ProbeProvider implements M3ToolProvider {
        @Override public String id() { return "probe"; }
        @Override public Set<M3ToolCapability> capabilities() {
            return Set.of(M3ToolCapability.INTERPRETER, M3ToolCapability.TRANSPILER,
                    M3ToolCapability.ATOMIZER, M3ToolCapability.PATTERNIZER);
        }
        @Override public Set<String> languages() { return Set.of("java", "rust", "python", "cpp"); }
        @Override public List<String> command(M3ToolCapability role, String language,
                TaskManifest.Task task, Path workspace) {
            String java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java").toString();
            return List.of(java, "-version");
        }
    }

    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("m3-provider-contract-");
        try {
            supportsDeterministicCapabilityInventory();
            validatesEveryOperation(root);
            refusesUnknownAndOverlappingOperations(root);
            forwardsCompilerToSelectedJdk(root);
            System.out.println("PASS: " + passed + " M3 tool-provider SPI contract cases");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private static void supportsDeterministicCapabilityInventory() throws Exception {
        var descriptions = M3ToolProviders.available();
        require(descriptions.size() == 2, "built-in + test provider must both be loaded");
        require(descriptions.get(0).id().equals("jdk"), "sorted built-in provider");
        require(descriptions.get(0).capabilities().equals(List.of("compiler")), "jdk compiler only");
        require(descriptions.get(1).id().equals("probe"), "external SPI registration");
        require(descriptions.get(1).capabilities().equals(List.of(
                "atomizer", "interpreter", "patternizer", "transpiler")), "all external operations");
        require(descriptions.get(1).languages().equals(List.of("cpp", "java", "python", "rust")),
                "languages ordered without interpretation");
        var lines = new ArrayList<String>();
        var selected = PolyglotCli.execute(new String[]{"providers"}, lines::add);
        require(selected.equals(List.of("jdk", "probe")), "portable provider inventory");
        require(lines.get(0).equals("M3-POLYGLOT-PROVIDERS-V1") && lines.size() == 3,
                "versioned portable inventory format");
        passed++;
    }

    private static void validatesEveryOperation(Path root) throws Exception {
        Files.writeString(root.resolve("source.txt"), "facts", StandardCharsets.UTF_8);
        for (M3ToolCapability capability : List.of(M3ToolCapability.INTERPRETER,
                M3ToolCapability.TRANSPILER, M3ToolCapability.ATOMIZER, M3ToolCapability.PATTERNIZER)) {
            String name = capability.slug();
            TaskManifest.Task task = task(name, "@m3:probe:" + name + ":rust",
                    List.of("source.txt"), List.of("generated/" + name + ".txt"));
            var command = M3ToolProviders.command(task, root);
            require(command.size() == 2 && command.get(1).equals("-version"),
                    "external provider command planned for " + name);
            passed++;
        }
        // Interpreters may be read-only and need no declared output.
        require(M3ToolProviders.command(task("read", "@m3:probe:interpreter:python",
                List.of("source.txt"), List.of()), root).size() == 2, "read-only interpreter");
        passed++;
    }

    private static void refusesUnknownAndOverlappingOperations(Path root) throws Exception {
        for (String tool : List.of("@m3", "@m3:jdk", "@m3:jdk:optimizer:java",
                "@m3:unknown:compiler:java", "@m3:jdk:patternizer:java",
                "@m3:jdk:compiler:python", "@m3:probe:atomizer:JAVA")) {
            refuses(() -> M3ToolProviders.command(task("bad", tool,
                    List.of("source.txt"), List.of("generated/file.txt")), root), tool);
            passed++;
        }
        refuses(() -> M3ToolProviders.command(task("bad", "@m3:probe:atomizer:java",
                List.of(), List.of("generated/file.txt")), root), "missing input");
        passed++;
        refuses(() -> M3ToolProviders.command(task("bad", "@m3:probe:patternizer:java",
                List.of("source.txt"), List.of()), root), "missing output");
        passed++;
        refuses(() -> M3ToolProviders.command(task("bad", "@m3:probe:transpiler:java",
                List.of("source.txt"), List.of("source.txt")), root), "source overwritten");
        passed++;
        refuses(() -> M3ToolProviders.command(task("bad", "@m3:probe:atomizer:java",
                List.of("src"), List.of("src/generated.java")), root), "input directory overlap");
        passed++;
        // A dry-run must resolve even malformed registered tool requests.
        Path manifest = root.resolve("invalid.tsv");
        Files.writeString(manifest, TaskManifest.MAGIC + "\n"
                + "task\tbad\t@m3:missing:compiler:java\t.\n"
                + "input\tbad\tsource.txt\n"
                + "output\tbad\tgenerated/Hello.class\n", StandardCharsets.UTF_8);
        refuses(() -> TaskRunner.run(TaskManifest.load(manifest), root, "bad", true,
                Duration.ofSeconds(10), ignored -> {}), "dry-run unknown provider");
        passed++;
    }

    private static void forwardsCompilerToSelectedJdk(Path root) throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("classes"));
        Files.writeString(root.resolve("src/Hello.java"),
                "public class Hello { public static int answer() { return 42; } }\n", StandardCharsets.UTF_8);
        Path manifest = root.resolve("compile.tsv");
        Files.writeString(manifest, TaskManifest.MAGIC + "\n"
                + "task\tcompile\t@m3:jdk:compiler:java\t.\n"
                + "arg\tcompile\t-d\n"
                + "arg\tcompile\tclasses\n"
                + "arg\tcompile\tsrc/Hello.java\n"
                + "input\tcompile\tsrc/Hello.java\n"
                + "output\tcompile\tclasses/Hello.class\n", StandardCharsets.UTF_8);
        TaskManifest plan = TaskManifest.load(manifest);
        var command = M3ToolProviders.command(plan.order("compile").get(0), root);
        require(command.contains("jdk.compiler/com.sun.tools.javac.Main"), "original Java compiler module");
        require(command.contains("src/Hello.java"), "original compiler arguments");
        var result = TaskRunner.run(plan, root, "compile", true,
                Duration.ofSeconds(60), ignored -> {});
        require(result.equals(List.of("compile")) && !Files.exists(root.resolve("classes/Hello.class")),
                "dry run plans without compilation");
        passed++;
        TaskRunner.run(plan, root, "compile", false, Duration.ofSeconds(60), ignored -> {});
        require(Files.isRegularFile(root.resolve("classes/Hello.class")), "Java compilation generated expected class");
        passed++;
        // The cheap incremental cache cannot claim to model arbitrary provider toolchain state.
        Files.delete(root.resolve("classes/Hello.class"));
        TaskRunner.run(plan, root, "compile", false, Duration.ofSeconds(60), false, true,
                ignored -> {});
        require(Files.isRegularFile(root.resolve("classes/Hello.class")), "provider run cannot be stamp-skipped");
        passed++;
    }

    private static TaskManifest.Task task(String name, String tool, List<String> inputs, List<String> outputs) {
        return new TaskManifest.Task(name, tool, ".", List.of(), List.of(), inputs, outputs);
    }

    @FunctionalInterface private interface Attempt { void run() throws Exception; }

    private static void refuses(Attempt attempt, String label) throws Exception {
        try {
            attempt.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("must refuse " + label);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
