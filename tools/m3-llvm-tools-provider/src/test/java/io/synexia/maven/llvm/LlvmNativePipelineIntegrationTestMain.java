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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Native LLVM IR pipeline qualification through the original Maven-owned task
 * runner and optional LLVM tools SPI. The first dry-run contract needs no LLVM
 * installation. Real execution requires opt, llvm-as and llvm-link on PATH or
 * under an explicitly selected LLVM home. An unqualified host reports NOT_RUN,
 * not PASS, unless --required was specified.
 *
 * <p>Verifies only host-native LLVM execution and cache exclusion; no supplied
 * toolchain is presumed to be built from the audited LLVM fork source SHA.</p>
 */
public final class LlvmNativePipelineIntegrationTestMain {
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final List<String> DRIVERS = List.of("opt", "llvm-as", "llvm-link");
    private static final AtomicInteger PASSED = new AtomicInteger();

    private LlvmNativePipelineIntegrationTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1 || (args.length == 1 && !"--required".equals(args[0]))) {
            throw new IllegalArgumentException("usage: LlvmNativePipelineIntegrationTestMain [--required]");
        }
        boolean required = args.length == 1;
        Path root = Files.createTempDirectory("m3-llvm-ir-native-");
        try {
            Files.createDirectories(root.resolve("target"));
            write(root.resolve("source.ll"), source());
            write(root.resolve("helper.ll"), helper(42));
            String home = home();
            // Planning without an explicit toolchain remains possible even on
            // hosts where no LLVM tool is installed. A configured installation
            // is never silently replaced by PATH binaries.
            if (home == null || available(home)) {
                TaskManifest offline = TaskManifest.load(
                        write(root.resolve("m3-build.tsv"), manifest(null)));
                dryRun(offline, root);
            }
            if (!available(home)) {
                if (required || home != null) {
                    throw new IOException("required or explicitly selected LLVM IR tools are unavailable: "
                            + String.join(", ", DRIVERS));
                }
                System.out.println("NOT_RUN: native opt/llvm-as/llvm-link execution "
                        + "(install LLVM or set M3_LLVM_PIPELINE_HOME); offline plan PASS");
                return;
            }
            TaskManifest manifest = TaskManifest.load(
                    write(root.resolve("m3-build.tsv"), manifest(home)));
            // The installed ServiceLoader implementation, not an independent
            // command builder, decides what the original LLVM tools will run.
            for (TaskManifest.Task task : manifest.order("inspect")) {
                List<String> argv = M3ToolProviders.command(task, root);
                check(!argv.isEmpty(), "real LLVM provider must plan a command: " + task.name());
            }

            Path cache = root.resolve("independent-output-cache");
            execute(manifest, root, cache);
            inspect(root, 42);
            execute(manifest, root, cache);
            inspect(root, 42);

            write(root.resolve("helper.ll"), helper(43));
            execute(manifest, root, cache);
            inspect(root, 43);

            // Compiler/assembler failure must reach the Maven owner. Even a
            // previously successful output cannot make this task up to date.
            write(root.resolve("helper.ll"), "define i32 @m3_value() { ret i64 44 }\n");
            try {
                execute(manifest, root, cache);
                throw new AssertionError("invalid LLVM IR must fail the native task");
            } catch (IOException expected) {
                check(true, "invalid LLVM IR failure propagates");
            }
            write(root.resolve("helper.ll"), helper(44));
            execute(manifest, root, cache);
            inspect(root, 44);

            if (Files.exists(cache)) {
                try (Stream<Path> entries = Files.walk(cache)) {
                    check(entries.noneMatch(Files::isRegularFile),
                            "provider-only artifact outputs must not enter generic output cache");
                }
            }
            System.out.println("PASS: " + PASSED.get() + " LLVM opt -> llvm-as -> llvm-link "
                    + "-> opt host-native assertions (not exact LLVM fork qualification)");
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static String source() {
        return "; M3 LLVM IR integration, Apache-2.0\n"
                + "declare i32 @m3_value()\n"
                + "define i32 @m3_answer() {\n"
                + "entry:\n"
                + "  %value = call i32 @m3_value()\n"
                + "  ret i32 %value\n"
                + "}\n";
    }

    private static String helper(int answer) {
        return "; M3 LLVM IR integration, Apache-2.0\n"
                + "define i32 @m3_value() {\n"
                + "entry:\n"
                + "  ret i32 " + answer + "\n"
                + "}\n";
    }

    /** Strict manifest v1: original LLVM binaries; disjoint named outputs. */
    private static String manifest(String home) {
        StringBuilder out = new StringBuilder(TaskManifest.MAGIC).append('\n');
        add(out, "opt-source", "optimizer", "llvm-ir", home,
                List.of("-S", "-passes=instcombine", "source.ll", "-o", "target/source.opt.ll"),
                List.of(), List.of("source.ll"), List.of("target/source.opt.ll"));
        add(out, "as-source", "assembler", "llvm-ir", home,
                List.of("target/source.opt.ll", "-o", "target/source.bc"),
                List.of("opt-source"), List.of("target/source.opt.ll"), List.of("target/source.bc"));
        add(out, "as-helper", "assembler", "llvm-ir", home,
                List.of("helper.ll", "-o", "target/helper.bc"),
                List.of(), List.of("helper.ll"), List.of("target/helper.bc"));
        add(out, "link", "linker", "llvm-ir", home,
                List.of("target/source.bc", "target/helper.bc", "-o", "target/linked.bc"),
                List.of("as-source", "as-helper"), List.of("target/source.bc", "target/helper.bc"),
                List.of("target/linked.bc"));
        add(out, "inspect", "optimizer", "llvm-ir", home,
                List.of("-S", "target/linked.bc", "-o", "target/linked.ll"),
                List.of("link"), List.of("target/linked.bc"), List.of("target/linked.ll"));
        return out.toString();
    }

    private static void add(StringBuilder manifest, String name, String capability,
            String language, String home, List<String> argv,
            List<String> dependencies, List<String> inputs, List<String> outputs) {
        manifest.append("task\t").append(name).append("\t@m3:llvm-tools:")
                .append(capability).append(':').append(language).append("\t.\n");
        if (home != null) manifest.append("arg\t").append(name)
                .append("\t--m3-llvm-home=").append(home).append('\n');
        for (String arg : argv) manifest.append("arg\t").append(name).append('\t').append(arg).append('\n');
        for (String dep : dependencies) manifest.append("dep\t").append(name).append('\t').append(dep).append('\n');
        for (String input : inputs) manifest.append("input\t").append(name).append('\t').append(input).append('\n');
        for (String output : outputs) manifest.append("output\t").append(name).append('\t').append(output).append('\n');
    }

    private static void dryRun(TaskManifest manifest, Path root) throws Exception {
        List<String> planned = TaskRunner.run(manifest, root, "inspect", true, TIMEOUT,
                line -> { });
        check(planned.size() == 5 && Set.copyOf(planned)
                        .equals(Set.of("opt-source", "as-source", "as-helper", "link", "inspect")),
                "all five native tasks are ordered and reachable");
        check(!Files.exists(root.resolve("target/linked.ll"))
                        && !Files.exists(root.resolve(".m3")),
                "planning must not produce outputs or module-state files");
    }

    private static void execute(TaskManifest manifest, Path root, Path cache) throws Exception {
        List<String> logs = new ArrayList<>();
        TaskRunner.run(manifest, root, "inspect", false, TIMEOUT, false,
                true, "embedded", cache, logs::add);
        check(logs.stream().noneMatch(line -> line.startsWith("UP-TO-DATE ")
                        || line.startsWith("FROM-CACHE ") || line.startsWith("CACHED ")),
                "provider-owned LLVM stages must always execute, even with incremental/cache enabled");
        for (String path : List.of("target/source.opt.ll", "target/source.bc",
                "target/helper.bc", "target/linked.bc", "target/linked.ll")) {
            check(Files.isRegularFile(root.resolve(path)) && Files.size(root.resolve(path)) > 0,
                    "complete, nonempty native stage output: " + path);
        }
    }

    private static void inspect(Path root, int answer) throws IOException {
        String linked = Files.readString(root.resolve("target/linked.ll"), StandardCharsets.UTF_8);
        check(linked.contains("define i32 @m3_answer("),
                "linked module retains source function");
        check(linked.contains("define i32 @m3_value("),
                "linked module contains helper definition");
        check(linked.contains("ret i32 " + answer),
                "changed helper value is visible in linked LLVM IR: " + answer);
    }

    /** Explicit selection takes precedence and never falls back to PATH. */
    private static String home() {
        for (String name : List.of("M3_LLVM_PIPELINE_HOME", "M3_LLVM_HOME", "LLVM_HOME")) {
            String selected = System.getenv(name);
            if (selected != null && !selected.isBlank()) return Path.of(selected).toAbsolutePath().normalize().toString();
        }
        return null;
    }

    private static boolean available(String home) {
        for (String tool : DRIVERS) {
            String name = tool + (System.getProperty("os.name", "").startsWith("Windows") ? ".exe" : "");
            if (home != null) {
                if (!Files.isRegularFile(Path.of(home, "bin", name)) || !Files.isExecutable(Path.of(home, "bin", name))) return false;
            } else if (!pathExecutable(name)) {
                return false;
            }
        }
        return true;
    }

    private static boolean pathExecutable(String name) {
        String value = System.getenv("PATH");
        if (value == null) return false;
        for (String directory : value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (directory.isBlank()) continue;
            try {
                Path candidate = Path.of(directory).resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return true;
            } catch (java.nio.file.InvalidPathException ignored) {
                // Ignore a malformed PATH entry, never silently fall back to another toolchain root.
            }
        }
        return false;
    }

    private static Path write(Path path, String body) throws IOException {
        return Files.writeString(path, body, StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        PASSED.incrementAndGet();
    }
}
