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
import java.util.Comparator;
import java.util.List;

/**
 * An explicit real-LLVM gate for all six advertised languages. No compiler is bundled or downloaded:
 * {@code M3_LLVM_REAL_TEST_HOME} must name a built/installed toolchain, preferably from the pinned fork commit.
 * This test verifies actual object outputs through the Maven runner, not just a planned command vector.
 */
public final class LlvmRealTestsMain {
    private LlvmRealTestsMain() {}

    public static void main(String[] ignored) throws Exception {
        String home = System.getenv("M3_LLVM_REAL_TEST_HOME");
        if (home == null || home.isBlank()) {
            System.out.println("NOT RUN: real LLVM compilation (set M3_LLVM_REAL_TEST_HOME to the fork toolchain root)");
            return;
        }
        Path root = Files.createTempDirectory("m3-llvm-real-");
        try {
            Files.createDirectories(root.resolve("src"));
            Files.createDirectories(root.resolve("target"));
            compile(root, home, "c", "c", "int m3_llvm_c(void) { return 42; }\n", List.of("-c"));
            compile(root, home, "cpp", "cpp", "extern \"C\" int m3_llvm_cpp() { return 42; }\n", List.of("-c"));
            compile(root, home, "objc", "m", "@interface M3Obj @end\n@implementation M3Obj @end\n", List.of("-c"));
            compile(root, home, "objcpp", "mm", "@interface M3ObjCpp @end\n@implementation M3ObjCpp @end\n",
                    List.of("-c"));
            compile(root, home, "fortran", "f90", "subroutine m3_llvm_f()\nend subroutine m3_llvm_f\n",
                    List.of("-c"));
            compile(root, home, "llvm-ir", "ll", "define i32 @m3_llvm_ir() { ret i32 42 }\n",
                    List.of("-filetype=obj"));
            System.out.println("PASS: six real LLVM compiler/IR object outputs through Maven TaskRunner");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static void compile(Path root, String home, String language, String extension, String source,
            List<String> flags) throws Exception {
        String name = language.replace('-', '_');
        String input = "src/" + name + "." + extension;
        String output = "target/" + name + ".o";
        Files.writeString(root.resolve(input), source, StandardCharsets.UTF_8);
        String driver = switch (language) {
            case "cpp", "objcpp" -> "clang++";
            case "fortran" -> "flang";
            case "llvm-ir" -> "llc";
            default -> "clang";
        };
        Path executable = LlvmCompilerProvider.resolve(home, driver, root);
        String sha = InputStamps.fileSha256(executable.toRealPath());
        Path manifest = root.resolve(name + "-build.tsv");
        StringBuilder rows = new StringBuilder(TaskManifest.MAGIC).append('\n')
                .append("task\tcompile\t@m3:llvm:compiler:").append(language).append("\t.\n")
                .append("arg\tcompile\t--m3-llvm-home=").append(home).append('\n')
                .append("arg\tcompile\t--m3-llvm-sha256=").append(sha).append('\n');
        for (String flag : flags) rows.append("arg\tcompile\t").append(flag).append('\n');
        rows.append("arg\tcompile\t").append(input).append('\n')
                .append("arg\tcompile\t-o\n")
                .append("arg\tcompile\t").append(output).append('\n')
                .append("input\tcompile\t").append(input).append('\n')
                .append("output\tcompile\t").append(output).append('\n');
        Files.writeString(manifest, rows, StandardCharsets.UTF_8);
        TaskManifest plan = TaskManifest.load(manifest);
        TaskRunner.run(plan, root, "compile", true, Duration.ofSeconds(60), ignored -> {});
        if (Files.exists(root.resolve(output))) throw new IOException("LLVM dry-run wrote output: " + output);
        TaskRunner.run(plan, root, "compile", false, Duration.ofSeconds(120), ignored -> {});
        if (!Files.isRegularFile(root.resolve(output)) || Files.size(root.resolve(output)) == 0) {
            throw new IOException("LLVM did not compile the declared output for " + language + ": " + output);
        }
    }
}
