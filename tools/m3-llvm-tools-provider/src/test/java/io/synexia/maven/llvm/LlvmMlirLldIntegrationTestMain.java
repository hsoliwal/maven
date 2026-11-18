/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.llvm;

import io.synexia.maven.polyglot.M3ToolProviders;
import io.synexia.maven.polyglot.TaskManifest;
import io.synexia.maven.polyglot.TaskRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Separate real-tool qualification of LLVM-dialect MLIR -> LLVM bitcode and
 * Clang C object -> LLD ELF shared object, both through the Maven-owned
 * TaskRunner and optional original LLVM tool provider. No compiler/linker,
 * parser, native cache or task graph is reimplemented here.
 *
 * <p>Offline planning always runs without an installed toolchain. Native
 * execution is opt-in on ordinary hosts, mandatory with --required. An
 * explicitly selected but incomplete installation is a failure, not a skip.
 * This test establishes neither target ABI portability nor source provenance
 * for the separately maintained hsoliwal/llvm-project fork.</p>
 */
public final class LlvmMlirLldIntegrationTestMain {
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final List<String> DRIVERS =
            List.of("mlir-opt", "mlir-translate", "llvm-as", "clang", "ld.lld");
    private static int passed;

    private LlvmMlirLldIntegrationTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1 || (args.length == 1 && !"--required".equals(args[0]))) {
            throw new IllegalArgumentException("usage: LlvmMlirLldIntegrationTestMain [--required]");
        }
        boolean required = args.length == 1;
        Path root = Files.createTempDirectory("m3-mlir-lld-");
        try {
            Files.createDirectories(root.resolve("target"));
            write(root, "value.mlir", mlir(42));
            write(root, "native.c", source(42));

            // The unselected plan is valid without LLVM tools, because the
            // registry validates real provider syntax without execution.
            TaskManifest offline = TaskManifest.load(write(root, "offline.tsv", manifest(null)));
            List<String> planned = TaskRunner.run(offline, root, "all", true, TIMEOUT, line -> { });
            require(planned.size() == 6
                    && Set.copyOf(planned.stream().map(String::valueOf).toList()).equals(
                            Set.of("mlir-opt", "mlir-translate", "llvm-as",
                                    "clang-c", "lld-elf", "all")),
                    "six dependency-ordered M3 native DAG tasks");
            require(!Files.exists(root.resolve(".m3")) && !Files.exists(root.resolve("target/value.bc"))
                    && !Files.exists(root.resolve("target/libm3.so")),
                    "plan creates no build outputs or M3DB state");

            String home = selectedHome();
            if (!available(home)) {
                if (home != null || required) {
                    throw new IOException("selected or required MLIR/LLD toolchain incomplete: "
                            + String.join(", ", DRIVERS));
                }
                System.out.println("NOT_RUN: MLIR/LLD native execution "
                        + "(set M3_LLVM_PIPELINE_HOME or install mlir-opt/mlir-translate/llvm-as/clang/ld.lld)");
                return;
            }

            TaskManifest plan = TaskManifest.load(write(root, "m3-build.tsv", manifest(home)));
            for (TaskManifest.Task task : plan.order("all")) {
                if (!"-".equals(task.tool())) {
                    require(!M3ToolProviders.command(task, root).isEmpty(),
                            "original native command selected for " + task.name());
                }
            }
            Path cache = root.resolve("independent-artifact-cache");
            execute(plan, root, cache);
            verifyOutputs(root, 42);
            String firstLibrary = sha256(root.resolve("target/libm3.so"));

            execute(plan, root, cache);
            verifyOutputs(root, 42);

            write(root, "value.mlir", mlir(43));
            write(root, "native.c", source(43));
            execute(plan, root, cache);
            verifyOutputs(root, 43);
            require(!firstLibrary.equals(sha256(root.resolve("target/libm3.so"))),
                    "changed C implementation alters the linked ELF artifact");

            write(root, "value.mlir", "module { llvm.func @broken() -> i32 {\n");
            try {
                execute(plan, root, cache);
                throw new AssertionError("malformed MLIR must fail the original mlir-opt task");
            } catch (IOException expected) {
                require(true, "failed native optimization propagates to Maven");
            }
            write(root, "value.mlir", mlir(44));
            write(root, "native.c", source(44));
            execute(plan, root, cache);
            verifyOutputs(root, 44);

            if (Files.exists(cache)) {
                try (Stream<Path> entries = Files.walk(cache)) {
                    require(entries.noneMatch(Files::isRegularFile),
                            "LLVM provider outputs never enter generic artifact cache");
                }
            }
            System.out.println("PASS: " + passed
                    + " real MLIR dialect/LLD ELF Maven TaskRunner assertions "
                    + "(host LLVM distribution, not pinned fork source build)");
        } finally {
            try (Stream<Path> entries = Files.walk(root)) {
                for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    private static String mlir(int n) {
        return "// Apache-2.0 M3 LLVM dialect test fixture\n"
                + "module {\n"
                + "  llvm.func @m3_answer() -> i32 {\n"
                + "    %value = llvm.mlir.constant(" + n + " : i32) : i32\n"
                + "    llvm.return %value : i32\n"
                + "  }\n"
                + "}\n";
    }

    private static String source(int n) {
        return "/* Apache-2.0 M3 ELF test fixture */\n"
                + "int m3_shared_answer(void) { return " + n + "; }\n";
    }

    private static String manifest(String home) {
        StringBuilder out = new StringBuilder(TaskManifest.MAGIC).append('\n');
        task(out, "mlir-opt", "@m3:llvm-tools:optimizer:mlir", home,
                List.of("--canonicalize", "value.mlir", "-o", "target/value.opt.mlir"),
                List.of(), List.of("value.mlir"), List.of("target/value.opt.mlir"));
        task(out, "mlir-translate", "@m3:llvm-tools:transpiler:mlir", home,
                List.of("--mlir-to-llvmir", "target/value.opt.mlir", "-o", "target/value.ll"),
                List.of("mlir-opt"), List.of("target/value.opt.mlir"), List.of("target/value.ll"));
        task(out, "llvm-as", "@m3:llvm-tools:assembler:llvm-ir", home,
                List.of("target/value.ll", "-o", "target/value.bc"),
                List.of("mlir-translate"), List.of("target/value.ll"), List.of("target/value.bc"));
        task(out, "clang-c", "@m3:llvm:compiler:c", home,
                List.of("-std=c17", "-fPIC", "-c", "native.c", "-o", "target/native.o"),
                List.of(), List.of("native.c"), List.of("target/native.o"));
        task(out, "lld-elf", "@m3:llvm-tools:linker:elf", home,
                List.of("-shared", "target/native.o", "-o", "target/libm3.so"),
                List.of("clang-c"), List.of("target/native.o"), List.of("target/libm3.so"));
        out.append("task\tall\t-\t.\n")
                .append("dep\tall\tllvm-as\n")
                .append("dep\tall\tlld-elf\n");
        return out.toString();
    }

    private static void task(StringBuilder out, String name, String provider, String home,
            List<String> args, List<String> prerequisites, List<String> inputs, List<String> outputs) {
        out.append("task\t").append(name).append('\t').append(provider).append("\t.\n");
        if (home != null) {
            out.append("arg\t").append(name).append("\t--m3-llvm-home=").append(home).append('\n');
        }
        for (String arg : args) out.append("arg\t").append(name).append('\t').append(arg).append('\n');
        for (String dep : prerequisites) out.append("dep\t").append(name).append('\t').append(dep).append('\n');
        for (String input : inputs) out.append("input\t").append(name).append('\t').append(input).append('\n');
        for (String output : outputs) out.append("output\t").append(name).append('\t').append(output).append('\n');
    }

    private static void execute(TaskManifest plan, Path root, Path cache) throws Exception {
        List<String> log = new ArrayList<>();
        TaskRunner.run(plan, root, "all", false, TIMEOUT,
                false, true, "embedded", cache, log::add);
        require(log.stream().noneMatch(line -> line.startsWith("UP-TO-DATE ")
                        || line.startsWith("FROM-CACHE ") || line.startsWith("CACHED ")),
                "unqualified MLIR/LLVM/LLD providers may not reuse cached outputs");
    }

    private static void verifyOutputs(Path root, int expected) throws Exception {
        for (String name : List.of("target/value.opt.mlir", "target/value.ll",
                "target/value.bc", "target/native.o", "target/libm3.so")) {
            Path file = root.resolve(name);
            require(Files.isRegularFile(file) && Files.size(file) > 0,
                    "original LLVM tool emitted nonempty declared output " + name);
        }
        String optimized = Files.readString(root.resolve("target/value.opt.mlir"), StandardCharsets.UTF_8);
        require(optimized.contains("llvm.func @m3_answer"),
                "MLIR optimizer preserved the LLVM-dialect symbol");
        String translated = Files.readString(root.resolve("target/value.ll"), StandardCharsets.UTF_8);
        require(translated.contains("@m3_answer"), "MLIR-to-LLVMIR translation preserved function name");
        require(translated.contains("ret i32 " + expected),
                "MLIR constant mutation changes actual translated LLVM IR");
        byte[] bytes = Files.readAllBytes(root.resolve("target/libm3.so"));
        require(bytes.length > 16 && Arrays.equals(Arrays.copyOf(bytes, 4),
                        new byte[]{0x7f, 'E', 'L', 'F'}),
                "LLD generated an ELF shared object (not just a file named .so)");
    }

    private static String selectedHome() {
        for (String key : List.of("M3_LLVM_PIPELINE_HOME", "M3_LLVM_HOME", "LLVM_HOME")) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) return Path.of(value).toAbsolutePath().normalize().toString();
        }
        return null;
    }

    private static boolean available(String home) {
        if (!System.getProperty("os.name", "").startsWith("Linux")) return false;
        for (String tool : DRIVERS) {
            String name = tool;
            if (home != null) {
                Path actual = Path.of(home, "bin", name);
                if (!Files.isRegularFile(actual) || !Files.isExecutable(actual)) return false;
            } else {
                boolean found = false;
                String path = System.getenv("PATH");
                if (path != null) {
                    for (String part : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                        if (part.isBlank()) continue;
                        try {
                            Path actual = Path.of(part).resolve(name);
                            if (Files.isRegularFile(actual) && Files.isExecutable(actual)) {
                                found = true;
                                break;
                            }
                        } catch (java.nio.file.InvalidPathException ignored) {
                            // Malformed PATH entries cannot authorize this native gate.
                        }
                    }
                }
                if (!found) return false;
            }
        }
        return true;
    }

    private static Path write(Path root, String relative, String content) throws IOException {
        return Files.writeString(root.resolve(relative), content, StandardCharsets.UTF_8);
    }

    private static void require(boolean ok, String assertion) {
        if (!ok) throw new AssertionError(assertion);
        passed++;
    }

    private static String sha256(Path file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        return java.util.HexFormat.of().formatHex(digest);
    }
}
