/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.stream.Stream;

/** Deterministic compiler database contracts without Maven CLI or native LLVM. */
public final class LlvmCompilationDatabaseContractTestMain {
    private static int passed;

    private LlvmCompilationDatabaseContractTestMain() { }

    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("m3 llvm compdb ");
        try {
            Files.createDirectories(root.resolve("src"));
            Files.createDirectories(root.resolve("other"));
            Files.writeString(root.resolve("src/hello.c"), "int x = 42;\n", StandardCharsets.UTF_8);
            Files.writeString(root.resolve("src/feature with spaces.cpp"),
                    "int feature() { return 1; }\n", StandardCharsets.UTF_8);
            String valid = fixture();
            TaskManifest graph = load(root, valid);
            String json = LlvmCompilationDatabase.render(graph, root, "all");
            check(json.startsWith("[\n") && json.endsWith("\n]\n"),
                    "valid JSON array envelope and deterministic LF output");
            check(count(json, "\"directory\"") == 2 && count(json, "\"arguments\"") == 2,
                    "one command per Clang translation unit, no linker row");
            check(json.contains("\"file\":\"" + jsonPath(root.resolve("src/hello.c")) + "\""),
                    "C source key is absolute and correctly JSON escaped");
            check(json.contains("\"file\":\"" + jsonPath(root.resolve("src/feature with spaces.cpp")) + "\""),
                    "C++ path with spaces preserved without shell escaping");
            check(json.contains("-DMSG=\\\"hello\\\""), "quote in argv is JSON escaped");
            check(json.contains("\"arguments\":[") && !json.contains("\"command\":"),
                    "original argv array, not a synthesized shell command");
            check(json.indexOf("feature with spaces.cpp") < json.indexOf("hello.c"),
                    "stable compilation database sort by translation-unit filename");
            check(!Files.exists(root.resolve("target")), "render is read-only");

            Path out = LlvmCompilationDatabase.publish(graph, root, "all");
            check(out.equals(root.resolve("target/m3-llvm/compile_commands.json")),
                    "emits only the isolated Maven-owned compilation database");
            check(json.equals(Files.readString(out, StandardCharsets.UTF_8)),
                    "published bytes match read-only rendering");
            FileTime before = Files.getLastModifiedTime(out);
            check(out.equals(LlvmCompilationDatabase.publish(graph, root, "all"))
                    && before.equals(Files.getLastModifiedTime(out)),
                    "same inputs preserve the database timestamp and bytes");
            check(!Files.exists(root.resolve("build/cmake/compile_commands.json")),
                    "never overwrite the original CMake compilation database");
            check(!Files.exists(root.resolve(".m3")), "never create M3DB state");

            refuses(() -> LlvmCompilationDatabase.render(
                    load(root, valid.replace("input\tclang-c\tsrc\n", "input\tclang-c\tother\n")),
                    root, "all"), "missing declared input authority");
            refuses(() -> LlvmCompilationDatabase.render(
                    load(root, valid.replace("arg\tclang-c\tsrc/hello.c\n",
                            "arg\tclang-c\tsrc/hello.c\narg\tclang-c\tsrc/feature with spaces.cpp\n")),
                    root, "all"), "ambiguous multiple translation units");
            refuses(() -> LlvmCompilationDatabase.render(
                    load(root, valid.replace("arg\tclang-c\t-c\n", "arg\tclang-c\t-c\narg\tclang-c\t@flags.rsp\n")),
                    root, "all"), "opaque response file cannot fabricate a compdb");
            refuses(() -> LlvmCompilationDatabase.render(
                    load(root, valid.replace("arg\tclang-c\t-std=c17\n",
                            "arg\tclang-c\t-std=c17\n"
                            + "arg\tclang-c\t-Xclang\narg\tclang-c\t@hidden.rsp\n")),
                    root, "all"), "nested response-file operand must not bypass the parser");
            refuses(() -> LlvmCompilationDatabase.render(
                    load(root, valid.replace("arg\tclang-c\ttarget/hello.o\n", "")),
                    root, "all"), "dangling -o operand cannot produce a trusted IDE command");
            refuses(() -> LlvmCompilationDatabase.render(
                    load(root, valid.replace("arg\tclang-c\ttarget/hello.o\n",
                            "arg\tclang-c\ttarget/other.o\n")),
                    root, "all"), "actual Clang -o must match an M3 declared output");
            refuses(() -> LlvmCompilationDatabase.render(
                    load(root, valid.replace("arg\tclang-c\ttarget/hello.o\n",
                            "arg\tclang-c\ttarget/hello.o\n"
                            + "arg\tclang-c\t-o\narg\tclang-c\ttarget/other.o\n")),
                    root, "all"), "duplicate -o must not fabricate one output identity");
            refuses(() -> LlvmCompilationDatabase.render(
                    load(root, valid.replace("arg\tclang-c\t-c\n",
                            "arg\tclang-c\t-Xclang\narg\tclang-c\t-c\n")),
                    root, "all"), "Clang -c consumed as a frontend operand is not compilation");
            refuses(() -> LlvmCompilationDatabase.render(
                    load(root, valid.replace("arg\tclang-c\t-c\n",
                            "arg\tclang-c\t-c\narg\tclang-c\t-E\n")),
                    root, "all"), "preprocess-only output cannot appear as an object compile");
            refuses(() -> LlvmCompilationDatabase.render(graph, root, "standalone"),
                    "link-only closure must not produce fake compile entries");
            Files.delete(root.resolve("src/hello.c"));
            refuses(() -> LlvmCompilationDatabase.render(graph, root, "all"),
                    "missing source must fail instead of emitting stale command");
            Files.writeString(root.resolve("src/hello.c"), "int x = 42;\n", StandardCharsets.UTF_8);

            if (!System.getProperty("os.name", "").startsWith("Windows")) {
                Path inputAlias = root.resolve("src/source-link.c");
                Files.createSymbolicLink(inputAlias, root.resolve("src/hello.c"));
                refuses(() -> LlvmCompilationDatabase.render(
                        load(root, valid.replace("arg\tclang-c\tsrc/hello.c\n",
                                "arg\tclang-c\tsrc/source-link.c\n")),
                        root, "all"), "source symlink is refused even under a declared input directory");
                Files.delete(inputAlias);
                Files.delete(out);
                Files.createSymbolicLink(out, root.resolve("src/hello.c"));
                refuses(() -> LlvmCompilationDatabase.publish(graph, root, "all"),
                        "symlink output must never overwrite unrelated source");
                check(Files.isSymbolicLink(out), "refused output symlink remains intact");
            }
            System.out.println("PASS: " + passed + " LLVM compilation database contracts");
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static String fixture() {
        return TaskManifest.MAGIC + "\n"
                + "task\tclang-c\t@m3:llvm:compiler:c\t.\n"
                + "arg\tclang-c\t--m3-llvm-bin=pretend LLVM/bin\n"
                + "arg\tclang-c\t-std=c17\n"
                + "arg\tclang-c\t-c\n"
                + "arg\tclang-c\tsrc/hello.c\n"
                + "arg\tclang-c\t-o\n"
                + "arg\tclang-c\ttarget/hello.o\n"
                + "input\tclang-c\tsrc\n"
                + "output\tclang-c\ttarget/hello.o\n"
                + "task\tcpp\t@m3:llvm:compiler:cpp\t.\n"
                + "arg\tcpp\t--m3-llvm-bin=pretend LLVM/bin\n"
                + "arg\tcpp\t-DMSG=\"hello\"\n"
                + "arg\tcpp\t-c\n"
                + "arg\tcpp\tsrc/feature with spaces.cpp\n"
                + "arg\tcpp\t-o\n"
                + "arg\tcpp\ttarget/feature.o\n"
                + "input\tcpp\tsrc/feature with spaces.cpp\n"
                + "output\tcpp\ttarget/feature.o\n"
                + "task\tlink\t@m3:llvm:compiler:c\t.\n"
                + "arg\tlink\t--m3-llvm-bin=pretend LLVM/bin\n"
                + "arg\tlink\ttarget/hello.o\n"
                + "arg\tlink\t-o\n"
                + "arg\tlink\ttarget/executable\n"
                + "input\tlink\ttarget/hello.o\n"
                + "output\tlink\ttarget/executable\n"
                + "task\tall\t-\t.\n"
                + "dep\tall\tclang-c\n"
                + "dep\tall\tcpp\n"
                + "dep\tall\tlink\n"
                + "dep\tlink\tclang-c\n"
                + "task\tstandalone\t@m3:llvm:compiler:c\t.\n"
                + "arg\tstandalone\t--m3-llvm-bin=pretend LLVM/bin\n"
                + "arg\tstandalone\ttarget/hello.o\n"
                + "arg\tstandalone\t-o\n"
                + "arg\tstandalone\ttarget/standalone\n"
                + "input\tstandalone\ttarget/hello.o\n"
                + "output\tstandalone\ttarget/standalone\n";
    }

    private static TaskManifest load(Path root, String manifest) throws IOException {
        Path file = root.resolve("m3-build.tsv");
        Files.writeString(file, manifest, StandardCharsets.UTF_8);
        return TaskManifest.load(file);
    }

    /** Match JSON string escaping on Windows paths without a JSON dependency. */
    private static String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static int count(String value, String token) {
        int count = 0;
        for (int start = 0; (start = value.indexOf(token, start)) >= 0; start += token.length()) count++;
        return count;
    }

    @FunctionalInterface
    private interface Checked { void run() throws Exception; }

    private static void refuses(Checked action, String reason) throws Exception {
        try {
            action.run();
        } catch (IOException expected) {
            passed++;
            return;
        }
        throw new AssertionError("expected LLVM database refusal: " + reason);
    }

    private static void check(boolean okay, String assertion) {
        if (!okay) throw new AssertionError(assertion);
        passed++;
    }
}
