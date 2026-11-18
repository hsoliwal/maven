/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Additional LLVM planning and real compiler contracts for the existing provider.
 * The existing SPI suite owns driver inventory and selector validation. This
 * suite executes installed tools only when the test-only
 * {@code m3.polyglot.llvm.test.home} property names an LLVM installation root.
 * Its {@code bin} directory is passed through the existing task selector.
 * Missing optional llc and Flang tools are reported separately as NOT_RUN.
 */
public final class LlvmCompilerProviderContractTestMain {
    private static final String TEST_HOME = "m3.polyglot.llvm.test.home";
    private static final String BIN_ARGUMENT = "--m3-llvm-bin=";
    private static int assertions;

    private LlvmCompilerProviderContractTestMain() { }

    public static void main(String[] ignored) throws Exception {
        assertions = 0;
        Path root = Files.createTempDirectory("m3-llvm-native-contract-");
        try {
            preservesRawArguments(root);
            incrementalPlanningIsReadOnly(root.resolve("dry run"));
            String nativeResult = nativeContracts(root.resolve("native"));
            System.out.println("PASS: " + assertions
                    + " LLVM provider contract assertions; native " + nativeResult);
        } finally {
            erase(root);
        }
    }

    private static void preservesRawArguments(Path root) throws Exception {
        Path bin = root.resolve("tool chain/bin");
        List<String> flags = List.of("-x", "c++", "-DNAME=\"two words\"", "$(literal)", "semi;colon",
                "`literal`", "&&", "source with spaces.c", "unicode-\u03bb.c", "@flags.rsp", "--", "-operand");
        var task = new TaskManifest.Task("compile", "@m3:llvm:compiler:cpp", ".",
                configured(bin, flags), List.of(), List.of("source"), List.of("out/result.o"));
        Map<String, String> before = snapshot(root);
        List<String> command = M3ToolProviders.command(task, root);
        require(command.subList(1, command.size()).equals(flags), "raw ordered argv is not shell interpreted");
        require(snapshot(root).equals(before), "planning without an installed tool neither writes nor executes");
    }

    private static void incrementalPlanningIsReadOnly(Path root) throws Exception {
        Files.createDirectories(root);
        var text = new StringBuilder(TaskManifest.MAGIC).append('\n');
        appendTask(text, "compile", "c", configured(root.resolve("uninstalled/bin"),
                List.of("-c", "missing.c", "-o", "out/result.o")), List.of("missing.c"), "out/result.o");
        TaskManifest manifest = loadManifest(root, text);
        Map<String, String> before = snapshot(root);
        var log = new ArrayList<String>();
        TaskRunner.run(manifest, root, "compile", true, Duration.ofSeconds(30), false, true,
                "embedded", root.resolve("artifact-cache"), log::add);
        require(log.stream().anyMatch(line -> line.startsWith("PLAN compile")), "dry-run reports the task");
        require(snapshot(root).equals(before), "incremental dry-run with cache opt-in remains read-only");
        require(!Files.exists(root.resolve(".m3")) && !Files.exists(root.resolve("out")),
                "dry-run creates no module state or compiler output");
    }

    private static String nativeContracts(Path root) throws Exception {
        String configuredHome = System.getProperty(TEST_HOME, "");
        if (configuredHome.isBlank()) {
            return "NOT_RUN (set -D" + TEST_HOME + "=<LLVM installation>)";
        }
        Path bin = Path.of(configuredHome).toAbsolutePath().normalize().resolve("bin");
        requireTool(bin.resolve("clang" + executableSuffix()));
        requireTool(bin.resolve("clang++" + executableSuffix()));
        boolean llc = optionalTool(bin, "llc");
        boolean flang = optionalTool(bin, "flang");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("out"));
        writeNativeSources(root);
        TaskManifest manifest = nativeManifest(root, bin, llc, flang);
        noReuse(execute(manifest, root, "all"));
        var outputs = new ArrayList<>(List.of("c", "cpp", "objc", "objcpp"));
        if (llc) outputs.add("ir");
        if (flang) outputs.add("fortran");
        for (String name : outputs) {
            require(Files.size(root.resolve("out/" + name + ".o")) > 0, "real " + name + " object exists");
        }
        repeatedCompilationAndHeaders(manifest, root);
        failedCompilation(manifest, root);
        return "PASS (C, C++, Objective-C, Objective-C++, repeat/header changes, compile failure); "
                + "llc " + (llc ? "PASS" : "NOT_RUN") + "; Fortran " + (flang ? "PASS" : "NOT_RUN");
    }

    private static void requireTool(Path tool) throws IOException {
        if (!Files.isRegularFile(tool) || !Files.isExecutable(tool)) {
            throw new IOException("configured LLVM test driver is not a regular executable: " + tool);
        }
    }

    private static boolean optionalTool(Path bin, String name) throws IOException {
        Path tool = bin.resolve(name + executableSuffix());
        if (Files.notExists(tool, LinkOption.NOFOLLOW_LINKS)) {
            System.out.println("LLVM " + name + " native contract NOT_RUN: " + tool + " is not installed");
            return false;
        }
        // A present but broken symlink, directory or non-executable file is a configuration failure.
        requireTool(tool);
        return true;
    }

    private static void writeNativeSources(Path root) throws IOException {
        write(root, "src/value.h", "#define VALUE 7\n");
        write(root, "src/c.c", "#include \"value.h\"\nint answer(void) { return VALUE; }\n");
        write(root, "src/cpp.cpp", "#include \"value.h\"\ntemplate<class T> T twice(T x) { return x+x; }\n"
                + "int answer() { return twice(VALUE); }\n");
        String objc = "__attribute__((objc_root_class)) @interface Root\n+ (int)value;\n@end\n"
                + "@implementation Root\n+ (int)value { return 7; }\n@end\n";
        write(root, "src/objc.m", objc);
        write(root, "src/objcpp.mm", "template<class T> T twice(T x) { return x+x; }\n"
                + objc.replace("return 7;", "return twice(7);"));
        write(root, "src/ir.ll", "define i32 @answer() {\nentry:\n  ret i32 42\n}\n");
        write(root, "src/fortran.f90", "integer function answer()\n  answer = 42\nend function answer\n");
        write(root, "src/bad.c", "int broken = ;\n");
    }

    private static TaskManifest nativeManifest(Path root, Path bin, boolean llc, boolean flang) throws IOException {
        String[][] tasks = {{"c", "c", "c"}, {"cpp", "cpp", "cpp"}, {"objc", "objc", "m"},
                {"objcpp", "objcpp", "mm"}, {"ir", "llvm-ir", "ll"},
                {"fortran", "fortran", "f90"}, {"bad", "c", "c"}};
        var text = new StringBuilder(TaskManifest.MAGIC).append('\n').append("task\tall\t-\t.\n");
        for (String[] row : tasks) {
            String name = row[0], language = row[1], source = "src/" + name + "." + row[2];
            if ((name.equals("ir") && !llc) || (name.equals("fortran") && !flang)) continue;
            String output = "out/" + name + ".o";
            List<String> inputs = name.equals("c") || name.equals("cpp")
                    ? List.of(source, "src/value.h") : List.of(source);
            String compileFlag = name.equals("ir") ? "-filetype=obj" : "-c";
            appendTask(text, name, language, configured(bin, List.of(compileFlag, source, "-o", output)),
                    inputs, output);
            if (!name.equals("bad")) text.append("dep\tall\t").append(name).append('\n');
        }
        return loadManifest(root, text);
    }

    private static void repeatedCompilationAndHeaders(TaskManifest manifest, Path root) throws Exception {
        Path object = root.resolve("out/c.o");
        String original = sha256(object);
        FileTime oldTime = FileTime.fromMillis(946684800000L);
        Files.setLastModifiedTime(object, oldTime);
        require(Files.getLastModifiedTime(object).equals(oldTime), "repeat witness timestamp established");
        noReuse(execute(manifest, root, "c"));
        require(!Files.getLastModifiedTime(object).equals(oldTime), "unchanged provider task rewrites its object");
        require(sha256(object).equals(original), "unchanged fixture retains the same compiled object bytes");
        write(root, "src/value.h", "#define VALUE 49\n");
        noReuse(execute(manifest, root, "c"));
        require(!sha256(object).equals(original), "changed header produces a different compiled object");
    }

    private static void failedCompilation(TaskManifest manifest, Path root) throws Exception {
        try {
            execute(manifest, root, "bad");
        } catch (IOException expected) {
            require(String.valueOf(expected.getMessage()).contains("polyglot tool failed: bad"),
                    "Clang failure propagates through TaskRunner: " + expected.getMessage());
            require(!Files.exists(root.resolve("out/bad.o")), "failed compilation has no successful object");
            return;
        }
        throw new AssertionError("invalid C source must fail compilation");
    }

    private static List<String> execute(TaskManifest manifest, Path root, String target) throws Exception {
        var log = new ArrayList<String>();
        TaskRunner.run(manifest, root, target, false, Duration.ofSeconds(120), false, true,
                "embedded", root.resolve("artifact-cache"), log::add);
        return log;
    }

    private static void noReuse(List<String> log) {
        require(log.stream().noneMatch(line -> line.startsWith("UP-TO-DATE ") || line.startsWith("FROM-CACHE ")),
                "LLVM provider retains the existing no-reuse policy: " + log);
    }

    private static List<String> configured(Path bin, List<String> flags) {
        var all = new ArrayList<String>(flags.size() + 1);
        all.add(BIN_ARGUMENT + bin);
        all.addAll(flags);
        return all;
    }

    private static String executableSuffix() {
        return System.getProperty("os.name", "").startsWith("Windows") ? ".exe" : "";
    }

    private static void appendTask(StringBuilder text, String name, String language, List<String> args,
            List<String> inputs, String output) {
        text.append("task\t").append(name).append("\t@m3:llvm:compiler:").append(language).append("\t.\n");
        for (String arg : args) text.append("arg\t").append(name).append('\t').append(arg).append('\n');
        for (String input : inputs) text.append("input\t").append(name).append('\t').append(input).append('\n');
        text.append("output\t").append(name).append('\t').append(output).append('\n');
    }

    private static TaskManifest loadManifest(Path root, StringBuilder text) throws IOException {
        Path manifest = root.resolve("m3-build.tsv");
        Files.writeString(manifest, text.toString(), StandardCharsets.UTF_8);
        return TaskManifest.load(manifest);
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = stream.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.toList()) {
                String value = Files.isSymbolicLink(path) ? "link:" + Files.readSymbolicLink(path)
                        : Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "directory" : sha256(path);
                result.put(root.relativize(path).toString(), value);
            }
        }
        return result;
    }

    private static void write(Path root, String path, String text) throws IOException {
        Files.writeString(root.resolve(path), text, StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }

    private static void erase(Path root) throws IOException {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) Files.deleteIfExists(path);
    }
}
