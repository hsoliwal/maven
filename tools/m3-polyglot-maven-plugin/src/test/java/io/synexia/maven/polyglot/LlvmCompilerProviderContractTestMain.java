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

/** Dependency-free contract tests: only fake local driver files are needed, never LLVM downloads. */
public final class LlvmCompilerProviderContractTestMain {
    private static int passed;

    private LlvmCompilerProviderContractTestMain() {}

    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("m3-llvm-provider-");
        try {
            Path bin = Files.createDirectories(root.resolve("toolchain/bin"));
            for (String driver : List.of("clang", "clang++", "flang", "llc")) {
                Files.writeString(bin.resolve(name(driver)), "fixture-" + driver, StandardCharsets.UTF_8);
            }
            correctDriverAndArgumentBoundary(root);
            configuredHomeAndFlangFallback(root);
            pinsSelectedExecutable(root);
            refusesBadConfigurations(root);
            plansWithoutRunningCompiler(root);
            System.out.println("PASS: " + passed + " LLVM provider contract cases");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static void correctDriverAndArgumentBoundary(Path root) throws Exception {
        for (String language : List.of("c", "cpp", "objc", "objcpp", "fortran", "llvm-ir")) {
            var argv = command(root, language, List.of("--m3-llvm-home=toolchain", "-O2", "-c", "src/file"));
            String expected = switch (language) {
                case "cpp", "objcpp" -> "clang++";
                case "fortran" -> "flang";
                case "llvm-ir" -> "llc";
                default -> "clang";
            };
            check(argv.get(0).equals(root.resolve("toolchain/bin/" + name(expected)).toRealPath().toString()),
                    "uses the declared LLVM driver for " + language);
            check(argv.subList(argv.size() - 3, argv.size()).equals(List.of("-O2", "-c", "src/file")),
                    "passes compiler argv unchanged for " + language);
            check(argv.stream().noneMatch(s -> s.startsWith("--m3-llvm-")), "consumes adapter directives");
            if ("objc".equals(language) || "objcpp".equals(language)) {
                check(argv.subList(1, 3).equals(List.of("-x", "objc".equals(language)
                        ? "objective-c" : "objective-c++")), "explicit Objective-C language mode");
            }
            passed++;
        }
    }

    private static void pinsSelectedExecutable(Path root) throws Exception {
        String hash = InputStamps.fileSha256(root.resolve("toolchain/bin/" + name("clang")));
        var argv = command(root, "c", List.of("--m3-llvm-home=toolchain", "--m3-llvm-sha256="
                + hash.toUpperCase(java.util.Locale.ROOT), "-c", "src/file"));
        check(argv.size() == 3 && argv.get(1).equals("-c"), "pinned driver, not digest forwarded");
        passed++;
        refuses(() -> command(root, "c", List.of("--m3-llvm-home=toolchain",
                "--m3-llvm-sha256=" + "0".repeat(64), "-c", "src/file")), "changed binary SHA");
        passed++;
    }

    private static void configuredHomeAndFlangFallback(Path root) throws Exception {
        String prior = System.getProperty(LlvmCompilerProvider.HOME_PROPERTY);
        try {
            System.setProperty(LlvmCompilerProvider.HOME_PROPERTY, root.resolve("toolchain").toString());
            var argv = command(root, "c", List.of("-c", "src/file"));
            check(argv.get(0).equals(root.resolve("toolchain/bin/" + name("clang")).toRealPath().toString()),
                    "shared LLVM home property selects the original clang");
            passed++;
        } finally {
            if (prior == null) System.clearProperty(LlvmCompilerProvider.HOME_PROPERTY);
            else System.setProperty(LlvmCompilerProvider.HOME_PROPERTY, prior);
        }

        Path flang = root.resolve("toolchain/bin/" + name("flang"));
        Path flangNew = root.resolve("toolchain/bin/" + name("flang-new"));
        Files.move(flang, flangNew);
        try {
            var argv = command(root, "fortran", List.of("--m3-llvm-home=toolchain", "-c", "src/file"));
            check(argv.get(0).equals(flangNew.toRealPath().toString()), "older LLVM flang-new spelling");
            passed++;
        } finally {
            Files.move(flangNew, flang);
        }
    }

    private static void refusesBadConfigurations(Path root) throws Exception {
        for (List<String> arguments : List.of(
                List.of("--m3-llvm-home=absent", "-c", "src/file"),
                List.of("--m3-llvm-home=toolchain", "--m3-llvm-home=toolchain", "-c", "src/file"),
                List.of("--m3-llvm-home=", "-c", "src/file"),
                List.of("--m3-llvm-home=toolchain", "--m3-llvm-sha256=abc", "-c", "src/file"),
                List.of("--m3-llvm-home=toolchain", "--m3-llvm-sha256=" + "0".repeat(64),
                        "--m3-llvm-sha256=" + "0".repeat(64), "-c", "src/file"),
                List.of("--m3-llvm-home=toolchain", "--m3-llvm-unrecognized=yes", "-c", "src/file"),
                List.of("--m3-llvm-home=toolchain"))) {
            refuses(() -> command(root, "c", arguments), "invalid LLVM options " + arguments);
            passed++;
        }
        refuses(() -> new LlvmCompilerProvider().command(M3ToolCapability.ATOMIZER, "c",
                task("c", List.of("--m3-llvm-home=toolchain", "-c", "src/file")), root),
                "LLVM must not claim to be an atomizer");
        passed++;
    }

    private static void plansWithoutRunningCompiler(Path root) throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/file.c"), "int x = 1;\n", StandardCharsets.UTF_8);
        Path manifest = root.resolve("llvm-build.tsv");
        Files.writeString(manifest, TaskManifest.MAGIC + "\n"
                + "task\tcompile\t@m3:llvm:compiler:c\t.\n"
                + "arg\tcompile\t--m3-llvm-home=toolchain\n"
                + "arg\tcompile\t-c\n"
                + "arg\tcompile\tsrc/file.c\n"
                + "arg\tcompile\t-o\n"
                + "arg\tcompile\ttarget/file.o\n"
                + "input\tcompile\tsrc/file.c\n"
                + "output\tcompile\ttarget/file.o\n", StandardCharsets.UTF_8);
        var output = new ArrayList<String>();
        var result = TaskRunner.run(TaskManifest.load(manifest), root, "compile", true,
                Duration.ofSeconds(5), output::add);
        check(result.equals(List.of("compile")), "dry-run plans LLVM task");
        check(!Files.exists(root.resolve("target/file.o")), "plan never executes LLVM");
        passed++;
        String contents = Files.readString(manifest, StandardCharsets.UTF_8);
        Files.writeString(manifest, contents.replace("toolchain", "absent"), StandardCharsets.UTF_8);
        refuses(() -> TaskRunner.run(TaskManifest.load(manifest), root, "compile", true,
                Duration.ofSeconds(5), output::add), "dry-run must reject missing LLVM compiler");
        passed++;
    }

    private static List<String> command(Path root, String language, List<String> arguments) throws IOException {
        return M3ToolProviders.command(task(language, arguments), root);
    }

    private static TaskManifest.Task task(String language, List<String> arguments) {
        return new TaskManifest.Task("compile", "@m3:llvm:compiler:" + language, ".", arguments,
                List.of(), List.of("src/file"), List.of("target/file.o"));
    }

    private static String name(String driver) {
        return driver + (System.getProperty("os.name", "").startsWith("Windows") ? ".exe" : "");
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }

    private static void refuses(Checked action, String message) throws Exception {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("must refuse " + message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
