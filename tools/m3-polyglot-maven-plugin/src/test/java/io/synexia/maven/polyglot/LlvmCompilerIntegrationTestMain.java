/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Real LLVM compiler, linker and executable checks through the Maven task runner.
 * Optional when no toolchain is installed; --required makes its absence a failure.
 * A configured but incomplete toolchain always fails. This does not certify a
 * particular LLVM source revision, cross target, SDK or native platform.
 */
public final class LlvmCompilerIntegrationTestMain {
    private static final String TEST_HOME = "m3.polyglot.llvm.test.home";
    private static final String TEST_ENVIRONMENT = "M3_POLYGLOT_LLVM_TEST_HOME";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static int passed;
    private static String selectedHome;

    private LlvmCompilerIntegrationTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1 || (args.length == 1 && !"--required".equals(args[0]))) {
            throw new IllegalArgumentException("usage: LlvmCompilerIntegrationTestMain [--required]");
        }
        boolean required = args.length == 1;
        String home = firstNonblank(System.getProperty(TEST_HOME), System.getenv(TEST_ENVIRONMENT),
                System.getenv("M3_LLVM_REAL_TEST_HOME"));
        selectedHome = home == null ? null : Path.of(home).toAbsolutePath().normalize().toString();
        Path root = Files.createTempDirectory("m3 llvm integration ");
        try {
            plansLlcThenClangWithoutNative(root.resolve("offline IR plan"));
            refusesMalformedOptionalTool(root.resolve("broken optional tool"));
            System.out.println("PASS: " + passed + " offline LLVM graph and optional-tool refusal assertions");
            boolean configured = selectedHome != null || nonblank(LlvmCompilerProvider.configuredHome());
            if (!configured && (!available("clang", root) || !available("clang++", root))) {
                if (required) throw new IOException("required LLVM integration needs clang and clang++");
                System.out.println("NOT_RUN: LLVM native integration (set " + TEST_ENVIRONMENT
                        + " or install clang and clang++)");
                return;
            }
            // Resolve through the actual registry even before the first compile. A bad
            // explicit home or service registration is an error, never an optional skip.
            M3ToolProviders.command(probe("c"), root);
            M3ToolProviders.command(probe("cpp"), root);
            Files.createDirectories(root.resolve("src with spaces"));
            Files.createDirectories(root.resolve("include with spaces"));
            Files.createDirectories(root.resolve("target native"));
            nativeDependencies(root);
            nestedWorkingDirectory(root);
            llvmIr(root);
            objectiveC(root);
            fortran(root);
            System.out.println("PASS: " + passed + " LLVM native integration assertions"
                    + " (host toolchain; not an exact-fork or cross-platform qualification)");
        } finally {
            selectedHome = null;
            erase(root);
        }
    }

    private static void plansLlcThenClangWithoutNative(Path root) throws Exception {
        Path home = Files.createDirectories(root.resolve("fake LLVM installation/bin")).getParent();
        Files.writeString(home.resolve("bin/llc"), "offline-llc-fixture\n", StandardCharsets.UTF_8);
        Files.writeString(home.resolve("bin/clang"), "offline-clang-fixture\n", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("src with spaces"));
        write(root, "src with spaces/main.ll", "define i32 @main() { ret i32 0 }\n");
        String source = "src with spaces/main.ll";
        String object = "target native/main.o";
        String executable = "target native/main";
        String selection = LlvmCompilerProvider.HOME_ARGUMENT + home;
        String manifest = TaskManifest.MAGIC + "\n"
                + "task\tcodegen\t@m3:llvm:compiler:llvm-ir\t.\n"
                + "arg\tcodegen\t" + selection + "\n"
                + "arg\tcodegen\t-filetype=obj\n"
                + "arg\tcodegen\t" + source + "\n"
                + "arg\tcodegen\t-o\n"
                + "arg\tcodegen\t" + object + "\n"
                + "input\tcodegen\t" + source + "\n"
                + "output\tcodegen\t" + object + "\n"
                + "task\tlink\t@m3:llvm:compiler:c\t.\n"
                + "dep\tlink\tcodegen\n"
                + "arg\tlink\t" + selection + "\n"
                + "arg\tlink\t" + object + "\n"
                + "arg\tlink\t-o\n"
                + "arg\tlink\t" + executable + "\n"
                + "input\tlink\t" + object + "\n"
                + "output\tlink\t" + executable + "\n";
        TaskManifest plan = TaskManifest.load(write(root, "ir-native.tsv", manifest));
        var tasks = plan.order("link");
        check(tasks.stream().map(TaskManifest.Task::name).toList().equals(List.of("codegen", "link")),
                "IR codegen must precede its native link task");
        List<String> codegen = M3ToolProviders.command(tasks.get(0), root);
        List<String> link = M3ToolProviders.command(tasks.get(1), root);
        check(codegen.get(0).equals(home.resolve("bin/llc").toString())
                && codegen.contains("-filetype=obj"), "LLVM IR is dispatched to original llc");
        check(link.get(0).equals(home.resolve("bin/clang").toString()),
                "original clang links llc's declared object in a separate task");
        TaskRunner.run(plan, root, "link", true, TIMEOUT, line -> { });
        check(!Files.exists(root.resolve(object)) && !Files.exists(root.resolve(executable)),
                "dry-run never executes the fake compiler or creates native outputs");
    }

    private static void refusesMalformedOptionalTool(Path root) throws Exception {
        Path bin = Files.createDirectories(root.resolve("llvm installation/bin"));
        Path llc = Files.createDirectories(bin.resolve("llc" + (windows() ? ".exe" : "")));
        String previous = selectedHome;
        try {
            selectedHome = bin.getParent().toString();
            try {
                available("llc", root);
                throw new AssertionError("present but malformed llc must not be treated as an optional skip");
            } catch (IOException expected) {
                check(expected.getMessage().contains("not an executable"),
                        "configured optional LLVM driver must fail closed");
            }
        } finally {
            selectedHome = previous;
        }
        Files.delete(llc);
        String old = selectedHome;
        try {
            selectedHome = bin.getParent().toString();
            check(!available("llc", root), "truly missing optional llc remains an explicit NOT_RUN");
        } finally {
            selectedHome = old;
        }
    }

    private static void nativeDependencies(Path root) throws Exception {
        String common = "#include \"value.h\"\n"
                + "#if __has_include(\"optional.h\")\n#include \"optional.h\"\n"
                + "#else\n#define OPTIONAL 0\n#endif\n";
        String c = common + "#include <stdio.h>\n"
                + "int main(void) { printf(\"%d\\n\", VALUE + OPTIONAL + MODE); return 0; }\n";
        String cpp = common + "#include <iostream>\n"
                + "int main() { std::cout << VALUE + OPTIONAL + MODE << '\\n'; return 0; }\n";
        write(root, "src with spaces/main.c", c);
        write(root, "src with spaces/main.cpp", cpp);
        write(root, "include with spaces/value.h", "#define VALUE 1\n");
        StringBuilder manifest = new StringBuilder(TaskManifest.MAGIC).append('\n');
        nativeTask(manifest, "c-debug", "c", "main.c", "c17", 10, "-O0");
        nativeTask(manifest, "c-release", "c", "main.c", "c17", 100, "-O2");
        nativeTask(manifest, "cpp", "cpp", "main.cpp", "c++20", 20, "-O2");
        manifest.append("task\tall\t-\t.\n")
                .append("dep\tall\tc-debug\n")
                .append("dep\tall\tc-release\n")
                .append("dep\tall\tcpp\n");
        Path file = write(root, "native.tsv", manifest.toString());
        TaskManifest plan = TaskManifest.load(file);
        Path cache = Files.createDirectories(root.resolve("explicit output cache"));

        runUncached(plan, root, "all", cache);
        results(root, 11, 101, 21);
        check(Files.readString(root.resolve("target native/c-debug.d")).contains("value.h"),
                "Clang emitted the real header dependency");
        check(!Files.readString(root.resolve("target native/c-debug.d")).contains("optional.h"),
                "the first depfile cannot describe a nonexistent optional header");

        // Even unchanged provider work must run; partial header knowledge is not a
        // sufficient identity for the generic output cache or per-module receipts.
        runUncached(plan, root, "all", cache);
        results(root, 11, 101, 21);
        write(root, "include with spaces/value.h", "#define VALUE 2\n");
        runUncached(plan, root, "all", cache);
        results(root, 12, 102, 22);

        // This new file is absent from both the manifest and prior positive depfile.
        // An unsafe success cache would retain the original __has_include result.
        write(root, "include with spaces/optional.h", "#define OPTIONAL 5\n");
        runUncached(plan, root, "all", cache);
        results(root, 17, 107, 27);
        check(Files.readString(root.resolve("target native/c-debug.d")).contains("optional.h"),
                "the next real compile observes the new optional header");

        Files.delete(root.resolve(output("cpp")));
        runUncached(plan, root, "all", cache);
        results(root, 17, 107, 27);
        write(root, "src with spaces/main.c", "int main(void) { this is not valid C; }\n");
        try {
            runUncached(plan, root, "c-debug", cache);
            throw new AssertionError("invalid C must fail the task");
        } catch (IOException expected) {
            check(expected.getMessage().contains("polyglot tool failed"),
                    "native compiler failure reaches Maven: " + expected.getMessage());
        }
        write(root, "src with spaces/main.c", c);
        runUncached(plan, root, "all", cache);
        results(root, 17, 107, 27);
        try (Stream<Path> files = Files.walk(cache)) {
            check(files.noneMatch(Files::isRegularFile), "provider output is never stored in the generic build cache");
        }
        System.out.println("PASS: LLVM C17/C++20 compile/link/run, two configurations, depfiles,"
                + " header edits/appearance, missing output, failure/repair and cache exclusion");
    }

    private static void nativeTask(StringBuilder manifest, String name, String language, String source,
            String standard, int mode, String optimization) throws IOException {
        List<String> arguments = List.of("-std=" + standard, optimization, "-Wall", "-Wextra", "-Werror",
                "-DMODE=" + mode, "-I", "include with spaces", "-MD", "-MF", "target native/" + name + ".d",
                "src with spaces/" + source, "-o", output(name));
        appendTask(manifest, name, language, arguments,
                List.of("src with spaces/" + source, "include with spaces/value.h"),
                List.of(output(name), "target native/" + name + ".d"));
    }

    private static void runUncached(TaskManifest plan, Path root, String target, Path cache) throws Exception {
        List<String> log = new ArrayList<>();
        TaskRunner.run(plan, root, target, false, TIMEOUT, false, true, "embedded", cache, log::add);
        check(log.stream().noneMatch(line -> line.startsWith("UP-TO-DATE ")
                        || line.startsWith("FROM-CACHE ") || line.startsWith("CACHED ")),
                "LLVM compiler tasks execute despite incremental and output-cache opt-ins: " + log);
    }

    private static void results(Path root, int debug, int release, int cpp) throws Exception {
        check(execute(root, root.resolve(output("c-debug"))).equals(Integer.toString(debug)), "C debug executable result");
        check(execute(root, root.resolve(output("c-release"))).equals(Integer.toString(release)), "C release executable result");
        check(execute(root, root.resolve(output("cpp"))).equals(Integer.toString(cpp)), "C++ executable result");
    }

    private static void llvmIr(Path root) throws Exception {
        if (!available("llc", root)) {
            System.out.println("NOT_RUN: LLVM IR codegen/link/run (llc unavailable)");
            return;
        }
        String source = "src with spaces/main.ll";
        String object = "target native/ir.o";
        String output = output("ir");
        write(root, source, "define i32 @main() {\nentry:\n  ret i32 0\n}\n");
        StringBuilder manifest = new StringBuilder(TaskManifest.MAGIC).append('\n');
        appendTask(manifest, "ir-codegen", "llvm-ir", List.of("-filetype=obj", source, "-o", object),
                List.of(source), List.of(object));
        appendTask(manifest, "ir-link", "c", List.of(object, "-o", output),
                List.of(object), List.of(output));
        manifest.append("dep\tir-link\tir-codegen\n");
        TaskManifest plan = TaskManifest.load(write(root, "ir-native.tsv", manifest.toString()));
        TaskRunner.run(plan, root, "ir-link", false, TIMEOUT, line -> { });
        check(Files.size(root.resolve(object)) > 0, "original llc emits a nonempty native object");
        check(execute(root, root.resolve(output)).isEmpty(), "llc object linked by clang executes");
        System.out.println("PASS: LLVM IR llc object, clang link and native run");
    }

    private static void nestedWorkingDirectory(Path root) throws Exception {
        Files.createDirectories(root.resolve("nested working dir"));
        write(root, "nested working dir/main.c",
                "#include <stdio.h>\nint main(void) { puts(\"31\"); return 0; }\n");
        String executable = "nested" + (windows() ? ".exe" : "");
        var manifest = new StringBuilder(TaskManifest.MAGIC).append('\n')
                .append("task\tnested\t@m3:llvm:compiler:c\tnested working dir\n");
        for (String argument : selected("c", List.of("-std=c17", "main.c", "-o", executable))) {
            manifest.append("arg\tnested\t").append(argument).append('\n');
        }
        manifest.append("input\tnested\tnested working dir/main.c\n")
                .append("output\tnested\tnested working dir/").append(executable).append('\n');
        TaskManifest plan = TaskManifest.load(write(root, "nested.tsv", manifest.toString()));
        TaskRunner.run(plan, root, "nested", false, TIMEOUT, line -> { });
        check(execute(root, root.resolve("nested working dir/" + executable)).equals("31"),
                "task-relative argv and module-relative input/output declarations preserve the selected cwd");
        System.out.println("PASS: LLVM nested task working directory with module-relative declarations");
    }

    private static void objectiveC(Path root) throws Exception {
        String body = "@interface M3Root\n+ (int)value;\n@end\n"
                + "@implementation M3Root\n+ (int)value { return 7; }\n@end\n";
        String objc = "src with spaces/probe.m";
        write(root, objc, body);
        single(root, "objc", "objc", List.of("-Wno-objc-root-class", "-x", "objective-c", "-c",
                objc, "-o", "target native/objc.o"), objc, "target native/objc.o");
        check(Files.size(root.resolve("target native/objc.o")) > 0, "Objective-C frontend emits an object");
        String objcpp = "src with spaces/probe.mm";
        write(root, objcpp, "template <class T> T twice(T v) { return v + v; }\n"
                + body.replace("return 7;", "return twice(7);"));
        single(root, "objcpp", "objcpp", List.of("-std=c++20", "-Wno-objc-root-class", "-x", "objective-c++",
                "-c", objcpp, "-o", "target native/objcpp.o"), objcpp, "target native/objcpp.o");
        check(Files.size(root.resolve("target native/objcpp.o")) > 0, "Objective-C++ frontend emits an object");
        System.out.println("PASS: Objective-C/Objective-C++ compile-only (no SDK or Objective-C runtime qualification)");
    }

    private static void fortran(Path root) throws Exception {
        if (!available("flang", root)) {
            System.out.println("NOT_RUN: Fortran compile/link/run (flang driver unavailable)");
            return;
        }
        String source = "src with spaces/probe.f90";
        String output = output("fortran");
        write(root, source, "program probe\n  print '(I0)', 42\nend program probe\n");
        single(root, "fortran", "fortran", List.of(source, "-o", output), source, output);
        check(execute(root, root.resolve(output)).equals("42"), "Flang compiles, links and executes with its runtime");
        System.out.println("PASS: Fortran compile/link/run with installed Flang runtime");
    }

    private static void single(Path root, String name, String language, List<String> args,
            String input, String output) throws Exception {
        StringBuilder manifest = new StringBuilder(TaskManifest.MAGIC).append('\n');
        appendTask(manifest, name, language, args, List.of(input), List.of(output));
        TaskManifest plan = TaskManifest.load(write(root, name + ".tsv", manifest.toString()));
        TaskRunner.run(plan, root, name, false, TIMEOUT, line -> { });
    }

    private static void appendTask(StringBuilder manifest, String name, String language, List<String> args,
            List<String> inputs, List<String> outputs) throws IOException {
        manifest.append("task\t").append(name).append("\t@m3:llvm:compiler:").append(language).append("\t.\n");
        for (String arg : selected(language, args)) {
            manifest.append("arg\t").append(name).append('\t').append(arg).append('\n');
        }
        for (String input : inputs) manifest.append("input\t").append(name).append('\t').append(input).append('\n');
        for (String output : outputs) manifest.append("output\t").append(name).append('\t').append(output).append('\n');
    }

    private static TaskManifest.Task probe(String language) throws IOException {
        return new TaskManifest.Task("probe", "@m3:llvm:compiler:" + language, ".",
                selected(language, List.of("--version")),
                List.of(), List.of("source"), List.of("output"));
    }

    /** A pinned home is opt-in. Unconfigured tasks retain the merged PATH contract. */
    private static List<String> selected(String language, List<String> flags) throws IOException {
        if (selectedHome == null) return flags;
        String driver = switch (language) {
            case "cpp", "objcpp" -> "clang++";
            case "fortran" -> "flang";
            case "llvm-ir" -> "llc";
            default -> "clang";
        };
        Path executable = LlvmCompilerProvider.resolve(selectedHome, driver, Path.of("."));
        String sha = InputStamps.fileSha256(executable.toRealPath());
        var arguments = new ArrayList<String>(flags.size() + 2);
        arguments.add(LlvmCompilerProvider.HOME_ARGUMENT + selectedHome);
        arguments.add(LlvmCompilerProvider.SHA256_ARGUMENT + sha);
        arguments.addAll(flags);
        return List.copyOf(arguments);
    }

    private static boolean available(String driver, Path root) throws IOException {
        String home = selectedHome;
        if (!nonblank(home)) home = LlvmCompilerProvider.configuredHome();
        Path executable;
        if (nonblank(home)) {
            Path folder = Path.of(home);
            if (!folder.isAbsolute()) folder = root.resolve(folder);
            executable = folder.resolve("bin").resolve(driver + (windows() ? ".exe" : ""));
            if ("flang".equals(driver) && Files.notExists(executable, LinkOption.NOFOLLOW_LINKS)) {
                executable = folder.resolve("bin").resolve("flang-new" + (windows() ? ".exe" : ""));
            }
            if (Files.exists(executable, LinkOption.NOFOLLOW_LINKS)
                    && (!Files.isRegularFile(executable) || (!windows() && !Files.isExecutable(executable)))) {
                throw new IOException("configured LLVM test driver is not an executable file: " + executable);
            }
        } else {
            executable = Path.of(TaskRunner.resolveTool(driver, root));
            if (!executable.isAbsolute()) executable = root.resolve(executable);
        }
        return Files.isRegularFile(executable) && (windows() || Files.isExecutable(executable));
    }

    private static String execute(Path root, Path executable) throws Exception {
        Path capture = Files.createTempFile(root, "native-output-", ".txt");
        Process process = new ProcessBuilder(executable.toString()).directory(root.toFile())
                .redirectErrorStream(true).redirectOutput(capture.toFile()).start();
        try {
            if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IOException("native probe timed out: " + executable.getFileName());
            }
            if (Files.size(capture) > 65536) throw new IOException("native probe exceeded output budget");
            String output = Files.readString(capture, StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new IOException("native probe exited " + process.exitValue() + ": " + output);
            }
            return output;
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(capture);
        }
    }

    private static String output(String name) {
        return "target native/" + name + (windows() ? ".exe" : "");
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    private static boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstNonblank(String... candidates) {
        for (String candidate : candidates) {
            if (nonblank(candidate)) return candidate;
        }
        return null;
    }

    private static Path write(Path root, String relative, String data) throws IOException {
        return Files.writeString(root.resolve(relative), data, StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }

    private static void erase(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
