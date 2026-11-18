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
 * Real cross-object-format linkage through the original LLVM/Clang executables
 * and Maven's existing TaskRunner. No Windows/WASM sysroot or runtime is
 * needed for these freestanding fixtures. Successful emission is not proof
 * of target runtime compatibility, SDK integration or upstream LLVM provenance.
 */
public final class LlvmWasmCoffIntegrationTestMain {
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final List<String> DRIVERS = List.of("clang", "wasm-ld", "lld-link");
    private static int passed;

    private LlvmWasmCoffIntegrationTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1 || (args.length == 1 && !"--required".equals(args[0]))) {
            throw new IllegalArgumentException("usage: LlvmWasmCoffIntegrationTestMain [--required]");
        }
        boolean required = args.length == 1;
        Path workspace = Files.createTempDirectory("m3-llvm-wasm-coff-");
        try {
            Files.createDirectories(workspace.resolve("target"));
            write(workspace, "wasm.c", wasmSource(42));
            write(workspace, "coff.c", coffSource(42));

            // No native tools or cache artifacts are needed to validate this
            // M3 task graph and original provider command vocabulary.
            TaskManifest offline = TaskManifest.load(write(workspace, "offline.tsv", manifest(null)));
            List<String> order = TaskRunner.run(offline, workspace, "all", true, TIMEOUT, line -> { });
            require(order.size() == 5
                    && Set.copyOf(order).equals(Set.of("wasm-c", "wasm-link", "coff-c", "coff-link", "all")),
                    "both native target pipelines are planned through the shared Maven DAG");
            require(!Files.exists(workspace.resolve("target/m3.wasm"))
                    && !Files.exists(workspace.resolve("target/m3.dll"))
                    && !Files.exists(workspace.resolve(".m3")),
                    "native planning is side-effect free");

            String home = home();
            if (!available(home)) {
                if (required || home != null) {
                    throw new IOException("required or selected LLVM WASM/COFF toolchain incomplete: "
                            + String.join(", ", DRIVERS));
                }
                System.out.println("NOT_RUN: wasm-ld/lld-link native execution "
                        + "(install original LLVM drivers or select M3_LLVM_LINKER_HOME)");
                return;
            }

            TaskManifest graph = TaskManifest.load(write(workspace, "m3-build.tsv", manifest(home)));
            for (TaskManifest.Task task : graph.order("all")) {
                if (!"-".equals(task.tool())) {
                    require(!M3ToolProviders.command(task, workspace).isEmpty(),
                            "original LLVM tool selected for " + task.name());
                }
            }

            Path cache = workspace.resolve("provider-artifact-cache");
            run(graph, workspace, cache);
            verify(workspace);
            String wasmBefore = hash(workspace.resolve("target/m3.wasm"));
            String coffBefore = hash(workspace.resolve("target/m3.dll"));

            run(graph, workspace, cache);
            verify(workspace);

            write(workspace, "wasm.c", wasmSource(43));
            write(workspace, "coff.c", coffSource(43));
            run(graph, workspace, cache);
            verify(workspace);
            require(!wasmBefore.equals(hash(workspace.resolve("target/m3.wasm"))),
                    "WASM output bytes change after the C source changes");
            require(!coffBefore.equals(hash(workspace.resolve("target/m3.dll"))),
                    "COFF output bytes change after the C source changes");

            write(workspace, "wasm.c", "int m3_answer(void) { broken wasm compilation; }\n");
            try {
                run(graph, workspace, cache);
                throw new AssertionError("invalid target C must fail despite old output files");
            } catch (IOException expected) {
                require(true, "real Clang failure reaches Maven's task runner");
            }
            write(workspace, "wasm.c", wasmSource(44));
            write(workspace, "coff.c", coffSource(44));
            run(graph, workspace, cache);
            verify(workspace);

            // A Windows DLL export also creates an import library. Both files
            // belong to this task and must be declared, validated and rebuilt.
            Files.delete(workspace.resolve("target/m3.lib"));
            run(graph, workspace, cache);
            verify(workspace);
            Files.delete(workspace.resolve("target/m3.dll"));
            run(graph, workspace, cache);
            verify(workspace);
            if (Files.exists(cache)) {
                try (Stream<Path> entries = Files.walk(cache)) {
                    require(entries.noneMatch(Files::isRegularFile),
                            "unqualified WASM/COFF provider outputs are not cached");
                }
            }
            System.out.println("PASS: " + passed
                    + " LLVM WASM and PE/COFF native output, invalidation and cache-exclusion assertions"
                    + " (host LLVM, not source-fork or target-runtime qualification)");
        } finally {
            try (Stream<Path> entries = Files.walk(workspace)) {
                for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    private static String wasmSource(int value) {
        return "/* SPDX-License-Identifier: Apache-2.0 */\n"
                + "int m3_answer(void) { return " + value + "; }\n";
    }

    private static String coffSource(int value) {
        return "/* SPDX-License-Identifier: Apache-2.0 */\n"
                + "__declspec(dllexport) int m3_answer(void) { return " + value + "; }\n";
    }

    private static String manifest(String home) {
        StringBuilder out = new StringBuilder(TaskManifest.MAGIC).append('\n');
        task(out, "wasm-c", "@m3:llvm:compiler:c", home,
                List.of("--target=wasm32-unknown-unknown", "-ffreestanding", "-O2",
                        "-c", "wasm.c", "-o", "target/m3.wasm.o"),
                List.of(), List.of("wasm.c"), List.of("target/m3.wasm.o"));
        task(out, "wasm-link", "@m3:llvm-tools:linker:wasm", home,
                List.of("--no-entry", "--export=m3_answer", "target/m3.wasm.o",
                        "-o", "target/m3.wasm"),
                List.of("wasm-c"), List.of("target/m3.wasm.o"), List.of("target/m3.wasm"));
        task(out, "coff-c", "@m3:llvm:compiler:c", home,
                List.of("--target=x86_64-pc-windows-msvc", "-ffreestanding", "-O2",
                        "-c", "coff.c", "-o", "target/m3.obj"),
                List.of(), List.of("coff.c"), List.of("target/m3.obj"));
        task(out, "coff-link", "@m3:llvm-tools:linker:coff", home,
                List.of("/dll", "/noentry", "/nodefaultlib", "/out:target/m3.dll",
                        "/implib:target/m3.lib", "target/m3.obj"),
                List.of("coff-c"), List.of("target/m3.obj"),
                List.of("target/m3.dll", "target/m3.lib"));
        out.append("task\tall\t-\t.\n")
                .append("dep\tall\twasm-link\n")
                .append("dep\tall\tcoff-link\n");
        return out.toString();
    }

    private static void task(StringBuilder manifest, String name, String provider, String home,
            List<String> argv, List<String> dependencies, List<String> inputs, List<String> outputs) {
        manifest.append("task\t").append(name).append('\t').append(provider).append("\t.\n");
        if (home != null) {
            manifest.append("arg\t").append(name).append("\t--m3-llvm-home=").append(home).append('\n');
        }
        for (String arg : argv) manifest.append("arg\t").append(name).append('\t').append(arg).append('\n');
        for (String dep : dependencies) manifest.append("dep\t").append(name).append('\t').append(dep).append('\n');
        for (String input : inputs) manifest.append("input\t").append(name).append('\t').append(input).append('\n');
        for (String output : outputs) manifest.append("output\t").append(name).append('\t').append(output).append('\n');
    }

    private static void run(TaskManifest graph, Path workspace, Path cache) throws Exception {
        List<String> log = new ArrayList<>();
        TaskRunner.run(graph, workspace, "all", false, TIMEOUT, false, true, "embedded", cache, log::add);
        require(log.stream().noneMatch(line -> line.startsWith("UP-TO-DATE ")
                        || line.startsWith("FROM-CACHE ") || line.startsWith("CACHED ")),
                "native provider work remains noncacheable without an independent qualified input closure");
    }

    private static void verify(Path workspace) throws Exception {
        for (String name : List.of("target/m3.wasm.o", "target/m3.wasm",
                "target/m3.obj", "target/m3.dll", "target/m3.lib")) {
            Path file = workspace.resolve(name);
            require(Files.isRegularFile(file) && Files.size(file) > 0,
                    "LLVM produced nonempty declared output " + name);
        }
        byte[] wasm = Files.readAllBytes(workspace.resolve("target/m3.wasm"));
        require(wasm.length >= 8 && Arrays.equals(Arrays.copyOf(wasm, 8),
                        new byte[] {0, 'a', 's', 'm', 1, 0, 0, 0}),
                "WASM magic and module version 1 are valid");
        require(contains(wasm, "m3_answer"),
                "WASM module retains an exported m3_answer symbol");

        byte[] coff = Files.readAllBytes(workspace.resolve("target/m3.dll"));
        require(coff.length > 0x40 && coff[0] == 'M' && coff[1] == 'Z',
                "COFF DLL retains DOS MZ stub");
        int offset = little32(coff, 0x3c);
        require(offset >= 0x40 && offset <= coff.length - 24
                        && coff[offset] == 'P' && coff[offset + 1] == 'E'
                        && coff[offset + 2] == 0 && coff[offset + 3] == 0,
                "COFF DLL contains bounded PE signature");
        int machine = (coff[offset + 4] & 0xff) | ((coff[offset + 5] & 0xff) << 8);
        int characteristics = (coff[offset + 22] & 0xff) | ((coff[offset + 23] & 0xff) << 8);
        require(machine == 0x8664 && (characteristics & 0x2000) != 0,
                "COFF DLL is x86-64, with IMAGE_FILE_DLL characteristic");
        require(contains(coff, "m3_answer"),
                "COFF DLL retains exported m3_answer");
        byte[] importLibrary = Files.readAllBytes(workspace.resolve("target/m3.lib"));
        require(importLibrary.length >= 8 && Arrays.equals(Arrays.copyOf(importLibrary, 8),
                        new byte[] {'!', '<', 'a', 'r', 'c', 'h', '>', '\n'}),
                "COFF import library is a valid archive with its own declared output");
        require(contains(importLibrary, "m3_answer"),
                "COFF import library contains the exported function name");
    }

    private static boolean contains(byte[] bytes, String needle) {
        byte[] chars = needle.getBytes(StandardCharsets.US_ASCII);
        outer: for (int i = 0; i <= bytes.length - chars.length; i++) {
            for (int j = 0; j < chars.length; j++) {
                if (bytes[i + j] != chars[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static int little32(byte[] bytes, int index) {
        return (bytes[index] & 0xff)
                | ((bytes[index + 1] & 0xff) << 8)
                | ((bytes[index + 2] & 0xff) << 16)
                | ((bytes[index + 3] & 0xff) << 24);
    }

    private static String home() {
        for (String name : List.of("M3_LLVM_LINKER_HOME", "M3_LLVM_PIPELINE_HOME",
                "M3_LLVM_HOME", "LLVM_HOME")) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) return Path.of(value).toAbsolutePath().normalize().toString();
        }
        return null;
    }

    private static boolean available(String home) {
        if (!System.getProperty("os.name", "").startsWith("Linux")) return false;
        for (String tool : DRIVERS) {
            if (home != null) {
                Path candidate = Path.of(home, "bin", tool);
                if (!Files.isRegularFile(candidate) || !Files.isExecutable(candidate)) return false;
            } else {
                String path = System.getenv("PATH");
                boolean found = false;
                if (path != null) {
                    for (String directory : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                        if (directory.isBlank()) continue;
                        try {
                            Path candidate = Path.of(directory).resolve(tool);
                            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                                found = true;
                                break;
                            }
                        } catch (java.nio.file.InvalidPathException ignored) {
                            // Ignore invalid PATH entry; no substitute installation is inferred.
                        }
                    }
                }
                if (!found) return false;
            }
        }
        return true;
    }

    private static Path write(Path root, String file, String data) throws IOException {
        return Files.writeString(root.resolve(file), data, StandardCharsets.UTF_8);
    }

    private static String hash(Path file) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                Files.readAllBytes(file)));
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        passed++;
    }
}
