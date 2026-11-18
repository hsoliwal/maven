/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.llvm;

import io.synexia.maven.polyglot.M3ToolCapability;
import io.synexia.maven.polyglot.M3ToolProviders;
import io.synexia.maven.polyglot.TaskManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** No external LLVM installation or JUnit dependency is required for these contracts. */
public final class LlvmNativeToolsContractTestMain {
    private static int passed;

    private LlvmNativeToolsContractTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("no arguments accepted");
        Path root = Files.createTempDirectory("m3 llvm tools contract ");
        try {
            Path bin = Files.createDirectories(root.resolve("llvm install/bin"));
            matrix(root, bin);
            optionsAndPins(root, bin);
            registryAndDeclarationGate(root, bin);
            sharedProperties(root, bin);
            System.out.println("PASS: " + passed + " LLVM tools provider contract assertions");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void matrix(Path root, Path bin) throws Exception {
        String[][] matrix = {
            {"optimizer", "llvm-ir", "opt"},
            {"optimizer", "mlir", "mlir-opt"},
            {"assembler", "llvm-ir", "llvm-as"},
            {"transpiler", "mlir", "mlir-translate"},
            {"linker", "llvm-ir", "llvm-link"},
            {"linker", "elf", "ld.lld"},
            {"linker", "coff", "lld-link"},
            {"linker", "macho", "ld64.lld"},
            {"linker", "wasm", "wasm-ld"},
            {"interpreter", "lit", "llvm-lit"},
            {"interpreter", "filecheck", "FileCheck"}
        };
        for (String[] row : matrix) {
            var role = M3ToolCapability.parse(row[0]);
            var provider = new LlvmNativeToolsProvider();
            var task = task(row[0], row[1], List.of(
                    "--m3-llvm-bin=llvm install/bin", "--version"));
            Path tool = fakeExecutable(bin, row[2]);
            List<String> command = provider.command(role, row[1], task, root);
            require(command.equals(List.of(tool.toString(), "--version")),
                    "original argv selected for " + row[0] + "/" + row[1]);
            passed++;
        }
        var provider = new LlvmNativeToolsProvider();
        require(provider.capabilities().containsAll(List.of(M3ToolCapability.LINKER,
                M3ToolCapability.OPTIMIZER, M3ToolCapability.ASSEMBLER)),
                "additive native tool roles are discoverable");
        passed++;
    }

    private static void optionsAndPins(Path root, Path bin) throws Exception {
        Path opt = fakeExecutable(bin, "opt");
        String hash = LlvmNativeToolsProvider.sha256(opt);
        var provider = new LlvmNativeToolsProvider();
        var selected = task("optimizer", "llvm-ir", List.of(
                "--m3-llvm-home=llvm install",
                "--m3-llvm-sha256=" + hash.toUpperCase(java.util.Locale.ROOT),
                "-passes=instcombine", "--", "--m3-llvm-unknown=literal"));
        List<String> argv = provider.command(M3ToolCapability.OPTIMIZER, "llvm-ir", selected, root);
        require(argv.equals(List.of(opt.toString(), "-passes=instcombine",
                "--", "--m3-llvm-unknown=literal")),
                "toolchain root and SHA options consumed while '--' retains literal args");
        passed++;

        Map<String, List<String>> refused = new TreeMap<>();
        refused.put("duplicate bin", List.of("--m3-llvm-bin=llvm install/bin",
                "--m3-llvm-bin=llvm install/bin", "--version"));
        refused.put("bin and home", List.of("--m3-llvm-bin=llvm install/bin",
                "--m3-llvm-home=llvm install", "--version"));
        refused.put("duplicate SHA", List.of("--m3-llvm-bin=llvm install/bin",
                "--m3-llvm-sha256=" + hash, "--m3-llvm-sha256=" + hash, "--version"));
        refused.put("bad SHA", List.of("--m3-llvm-bin=llvm install/bin",
                "--m3-llvm-sha256=not-a-digest", "--version"));
        refused.put("bad digest", List.of("--m3-llvm-bin=llvm install/bin",
                "--m3-llvm-sha256=" + "0".repeat(64), "--version"));
        refused.put("unknown selector", List.of("--m3-llvm-typo=llvm install", "--version"));
        refused.put("missing tool", List.of("--m3-llvm-bin=missing/bin", "--version"));
        refused.put("empty selector", List.of("--m3-llvm-bin=", "--version"));
        refused.put("no executable args", List.of("--m3-llvm-bin=llvm install/bin"));
        for (var entry : refused.entrySet()) {
            expectIOException(() -> provider.command(M3ToolCapability.OPTIMIZER, "llvm-ir",
                    task("optimizer", "llvm-ir", entry.getValue()), root), entry.getKey());
            passed++;
        }

        expectIOException(() -> provider.command(M3ToolCapability.LINKER, "mlir",
                task("linker", "mlir", List.of("--m3-llvm-bin=llvm install/bin", "--version")), root),
                "unsupported role/language pair");
        passed++;

        // Explicit installed-tool selection must remain deterministic despite an unrelated PATH.
        var direct = provider.command(M3ToolCapability.OPTIMIZER, "llvm-ir",
                task("optimizer", "llvm-ir", List.of("--m3-llvm-bin=llvm install/bin", "--help")), root);
        require(direct.get(0).equals(opt.toString()), "explicit pin never falls back to PATH");
        passed++;
    }

    private static void registryAndDeclarationGate(Path root, Path bin) throws Exception {
        var descriptors = M3ToolProviders.available();
        require(descriptors.stream().anyMatch(d -> d.id().equals("llvm-tools")
                && d.capabilities().contains("optimizer") && d.languages().contains("mlir")),
                "registered provider is available through shared M3 SPI");
        passed++;
        List<String> command = M3ToolProviders.command(task("linker", "elf",
                List.of("--m3-llvm-bin=llvm install/bin", "--version")), root);
        require(command.get(0).equals(fakeExecutable(bin, "ld.lld").toString()),
                "global M3 SPI dispatch selects original LLD");
        passed++;
        expectIOException(() -> M3ToolProviders.command(
                new TaskManifest.Task("probe", "@m3:llvm-tools:linker:elf", ".",
                        List.of("--m3-llvm-bin=llvm install/bin", "--version"),
                        List.of(), List.of("input.o"), List.of()), root),
                "linker task must declare output");
        passed++;
        expectIOException(() -> M3ToolProviders.command(
                new TaskManifest.Task("probe", "@m3:llvm-tools:optimizer:llvm-ir", ".",
                        List.of("--m3-llvm-bin=llvm install/bin", "--version"),
                        List.of(), List.of("source.ll"), List.of("source.ll")), root),
                "optimizer may not overwrite source");
        passed++;
        List<String> readonly = M3ToolProviders.command(
                new TaskManifest.Task("probe", "@m3:llvm-tools:interpreter:lit", ".",
                        List.of("--m3-llvm-bin=llvm install/bin", "--version"),
                        List.of(), List.of("lit.local.cfg"), List.of()), root);
        require(readonly.get(0).equals(fakeExecutable(bin, "llvm-lit").toString()),
                "lit test-runner uses the existing read-only interpreter policy");
        passed++;
    }

    private static void sharedProperties(Path root, Path bin) throws Exception {
        var provider = new LlvmNativeToolsProvider();
        String old = System.getProperty("m3.polyglot.llvm.home");
        String oldOther = System.getProperty("m3.llvm.home");
        try {
            System.setProperty("m3.polyglot.llvm.home", root.resolve("llvm install").toString());
            System.clearProperty("m3.llvm.home");
            List<String> selected = provider.command(M3ToolCapability.LINKER, "elf",
                    task("linker", "elf", List.of("--version")), root);
            require(selected.get(0).equals(fakeExecutable(bin, "ld.lld").toString()),
                    "same shared Maven LLVM root selects LLD");
            passed++;
            System.clearProperty("m3.polyglot.llvm.home");
            System.setProperty("m3.llvm.home", root.resolve("llvm install").toString());
            List<String> alt = provider.command(M3ToolCapability.LINKER, "elf",
                    task("linker", "elf", List.of("--version")), root);
            require(alt.get(0).equals(fakeExecutable(bin, "ld.lld").toString()),
                    "shared Clang AST LLVM root also selects LLD");
            passed++;
        } finally {
            restore("m3.polyglot.llvm.home", old);
            restore("m3.llvm.home", oldOther);
        }
    }

    private static void restore(String name, String old) {
        if (old == null) System.clearProperty(name);
        else System.setProperty(name, old);
    }

    private static TaskManifest.Task task(String role, String language, List<String> arguments) {
        return new TaskManifest.Task("probe", "@m3:llvm-tools:" + role + ":" + language,
                ".", arguments, List.of(), List.of("source.ll"), List.of("out/target.bc"));
    }

    private static Path fakeExecutable(Path bin, String name) throws IOException {
        String suffix = System.getProperty("os.name", "").startsWith("Windows") ? ".exe" : "";
        Path path = bin.resolve(name + suffix);
        if (!Files.exists(path)) {
            Files.writeString(path, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
            path.toFile().setExecutable(true);
        }
        return path.toAbsolutePath().normalize();
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }

    private static void expectIOException(Action action, String label) throws Exception {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("expected refusal: " + label);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
