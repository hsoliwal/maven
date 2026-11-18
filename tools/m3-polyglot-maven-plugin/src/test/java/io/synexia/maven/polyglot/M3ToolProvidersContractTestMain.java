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
            plansLlvmCompilerDrivers(root);
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
        require(descriptions.size() == 6, "built-in providers + test provider must all be loaded");
        builtInCargoEcjAndJdk(descriptions);
        probeAndSurefire(descriptions);
        portableInventory();
        passed++;
    }

    private static void builtInCargoEcjAndJdk(List<M3ToolProviders.Descriptor> descriptions) {
        require(descriptions.get(0).id().equals("cargo"), "sorted built-in cargo test provider");
        require(descriptions.get(0).capabilities().equals(List.of("interpreter"))
                && descriptions.get(0).languages().equals(List.of("rust")), "cargo runs Rust tests only");
        require(descriptions.get(1).id().equals("ecj"), "sorted built-in ECJ provider");
        require(descriptions.get(1).capabilities().equals(List.of("compiler"))
                && descriptions.get(1).languages().equals(List.of("java")), "ecj compiles Java only");
        require(descriptions.get(2).id().equals("jdk"), "sorted built-in provider");
        require(descriptions.get(2).capabilities().equals(List.of("compiler")), "jdk compiler only");
        require(descriptions.get(3).id().equals("llvm"), "LLVM provider registered");
        require(descriptions.get(3).capabilities().equals(List.of("compiler"))
                && descriptions.get(3).languages().equals(List.of("c", "cpp", "fortran", "llvm-ir", "objc", "objcpp")),
                "LLVM original C-family, Fortran and IR compiler drivers");
    }

    private static void probeAndSurefire(List<M3ToolProviders.Descriptor> descriptions) {
        require(descriptions.get(4).id().equals("probe"), "external SPI registration");
        require(descriptions.get(4).capabilities().equals(List.of(
                "atomizer", "interpreter", "patternizer", "transpiler")), "all external operations");
        require(descriptions.get(4).languages().equals(List.of("cpp", "java", "python", "rust")),
                "languages ordered without interpretation");
        require(descriptions.get(5).id().equals("surefire")
                && descriptions.get(5).capabilities().equals(List.of("interpreter", "transpiler"))
                && descriptions.get(5).languages().equals(List.of("junit", "libtest", "trx")),
                "built-in report provider: TRX files, CTest JUnit XML and libtest output");
    }

    private static void portableInventory() throws Exception {
        var lines = new ArrayList<String>();
        var selected = PolyglotCli.execute(new String[]{"providers"}, lines::add);
        require(selected.equals(List.of("cargo", "ecj", "jdk", "llvm", "probe", "surefire")),
                "portable provider inventory");
        require(lines.get(0).equals("M3-POLYGLOT-PROVIDERS-V1") && lines.size() == 7,
                "versioned portable inventory format");
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
        Path generated = root.resolve("classes/Hello.class");
        java.nio.file.attribute.FileTime oldTime = java.nio.file.attribute.FileTime.fromMillis(1);
        Files.setLastModifiedTime(generated, oldTime);
        TaskRunner.run(plan, root, "compile", false, Duration.ofSeconds(60), false, true, ignored -> {});
        require(!oldTime.equals(Files.getLastModifiedTime(generated)),
                "provider executes again even with unchanged inputs and present outputs");
        passed++;
    }


    private static void plansLlvmCompilerDrivers(Path root) throws Exception {
        String suffix = System.getProperty("os.name", "").startsWith("Windows") ? ".exe" : "";
        String[][] languages = {
            {"c", "clang"}, {"cpp", "clang++"}, {"objc", "clang"},
            {"objcpp", "clang++"}, {"fortran", "flang"}, {"llvm-ir", "llc"}
        };
        for (String[] language : languages) {
            var task = llvmTask(language[0], List.of("-c", "src/hello.c", "-o", "target/hello.o"));
            List<String> command = M3ToolProviders.command(task, root);
            require(command.equals(List.of(language[1] + suffix, "-c", "src/hello.c",
                    "-o", "target/hello.o")), "LLVM original compiler argv: " + language[0]);
            passed++;
        }
        var selected = llvmTask("c", List.of("--m3-llvm-bin=llvm installation/bin",
                "-c", "src/hello.c", "-o", "target/hello.o"));
        List<String> argv = M3ToolProviders.command(selected, root);
        require(argv.get(0).equals(root.resolve("llvm installation/bin")
                .resolve("clang" + suffix).toString()), "workspace-relative LLVM toolchain selector");
        require(argv.subList(1, argv.size()).equals(List.of("-c", "src/hello.c",
                "-o", "target/hello.o")), "provider consumes selector and preserves all compiler flags");
        passed++;

        refuses(() -> M3ToolProviders.command(llvmTask("c",
                List.of("--m3-llvm-bin=", "-c", "src/hello.c")), root), "empty LLVM bin directory");
        refuses(() -> M3ToolProviders.command(llvmTask("c",
                List.of("--m3-llvm-bin=a", "--m3-llvm-bin=b", "-c", "src/hello.c")), root),
                "duplicate LLVM bin directory");
        refuses(() -> M3ToolProviders.command(llvmTask("c", List.of()), root),
                "missing compiler arguments");
        refuses(() -> M3ToolProviders.command(
                new TaskManifest.Task("llvm", "@m3:llvm:transpiler:c", ".", List.of("-c", "src/hello.c"),
                        List.of(), List.of("src/hello.c"), List.of("target/hello.o")), root),
                "LLVM compiler cannot pretend to be a transpiler");
        passed += 4;

        Path manifest = root.resolve("llvm-plan.tsv");
        Files.writeString(manifest, TaskManifest.MAGIC + "\n"
                + "task\tcompile\t@m3:llvm:compiler:c\t.\n"
                + "arg\tcompile\t-c\n"
                + "arg\tcompile\tsrc/hello.c\n"
                + "arg\tcompile\t-o\n"
                + "arg\tcompile\ttarget/hello.o\n"
                + "input\tcompile\tsrc/hello.c\n"
                + "output\tcompile\ttarget/hello.o\n", StandardCharsets.UTF_8);
        require(TaskRunner.run(TaskManifest.load(manifest), root, "compile", true,
                Duration.ofSeconds(10), ignored -> {}).equals(List.of("compile")),
                "LLVM dry run validates command without needing clang installed");
        require(!Files.exists(root.resolve("target/hello.o")), "LLVM planning creates no native output");
        passed++;
    }

    private static TaskManifest.Task llvmTask(String language, List<String> arguments) {
        return new TaskManifest.Task("llvm", "@m3:llvm:compiler:" + language, ".",
                arguments, List.of(), List.of("src/hello.c"), List.of("target/hello.o"));
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
