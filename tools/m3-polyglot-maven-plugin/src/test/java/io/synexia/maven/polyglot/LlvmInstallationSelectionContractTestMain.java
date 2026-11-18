/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Installation contracts use non-program tool bytes; the OS must reject a non-executable fixture. */
public final class LlvmInstallationSelectionContractTestMain {
    private static int checks;

    private LlvmInstallationSelectionContractTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length >= 3 && "--probe-lookup".equals(args[0])) {
            List<String> flags = List.of(args).subList(3, args.length);
            if ("REFUSE_UNSELECTED_PIN".equals(args[2])) {
                try {
                    M3ToolProviders.command(task("c", flags), Path.of(args[1]));
                } catch (IOException expected) {
                    if (!expected.getMessage().contains("requires a selected home or bin")) throw expected;
                    return;
                }
                throw new AssertionError("SHA pin must not search the task directory or PATH");
            }
            List<String> command = M3ToolProviders.command(task("c", flags), Path.of(args[1]));
            if (!command.get(0).equals(args[2])) throw new AssertionError("unexpected child lookup: " + command);
            return;
        }
        checks = 0;
        Path root = Files.createTempDirectory("m3 llvm provider ");
        String previous = System.getProperty(LlvmCompilerProvider.HOME_PROPERTY);
        try {
            Path home = fakeHome(root.resolve("LLVM installation"));
            System.setProperty(LlvmCompilerProvider.HOME_PROPERTY, home.toString());
            discoversCompilerFamily();
            preservesArguments(root, home);
            preservesLegacyBinSelection(root);
            selectsConfiguredHome(root, home);
            selectsEnvironmentAndRefusesUnselectedPins(root, home);
            validatesExecutablePin(root, home);
            rejectsMalformedOptions(root);
            preservesDriverSymlink(root);
            windowsDriverNames(root);
            plansWithoutExecutionOrState(root, home);
            System.out.println("PASS: " + checks + " LLVM installation selection assertions");
        } finally {
            restore(LlvmCompilerProvider.HOME_PROPERTY, previous);
            erase(root);
        }
    }

    private static void discoversCompilerFamily() throws Exception {
        var descriptor = M3ToolProviders.available().stream().filter(p -> p.id().equals("llvm"))
                .findFirst().orElseThrow(() -> new AssertionError("LLVM provider not registered"));
        require(descriptor.capabilities().equals(List.of("compiler")), "only the implemented compiler role");
        require(Set.copyOf(descriptor.languages()).equals(Set.of("c", "cpp", "objc", "objcpp", "objc++", "fortran", "llvm-ir")),
                "exact supported LLVM language dispatch");
    }

    private static void preservesArguments(Path root, Path home) throws Exception {
        List<String> args = List.of("--target=aarch64-linux-gnu", "--sysroot=/SDK with spaces", "-x", "c++",
                "-DVALUE=a b;$(not-a-shell)", "-Xclang", "-ast-dump=json", "@flags with spaces.rsp",
                "-MD", "-MF", "target/header dependencies.d", "-MJ", "target/compile fragment.json",
                "-fuse-ld=lld", "-Wl,--as-needed", "src/λ.c", "-o", "target/with spaces.o");
        for (String language : List.of("c", "cpp", "objc", "objcpp", "objc++", "fortran", "llvm-ir")) {
            String driver = switch (language) {
                case "cpp", "objcpp", "objc++" -> "clang++";
                case "fortran" -> "flang";
                case "llvm-ir" -> "llc";
                default -> "clang";
            };
            List<String> result = M3ToolProviders.command(task(language, args), root);
            require(result.get(0).equals(home.resolve("bin").resolve(filename(driver)).toString()),
                    "original driver selected for " + language);
            require(result.subList(1, result.size()).equals(args), "unaltered argv for " + language);
            try {
                result.add("mutation");
                throw new AssertionError("mutable command");
            } catch (UnsupportedOperationException expected) {
                checks++;
            }
        }
        String digest = InputStamps.fileSha256(home.resolve("bin").resolve(filename("clang")));
        List<String> directives = List.of("-c", "--", LlvmCompilerProvider.HOME_ARGUMENT + home,
                LlvmCompilerProvider.SHA256_ARGUMENT + digest, "src/file with spaces.c");
        require(M3ToolProviders.command(task("c", directives), root).equals(List.of(
                home.resolve("bin").resolve(filename("clang")).toString(), "-c", "--", "src/file with spaces.c")),
                "home and digest directives are consumed in every position while compiler argv stays ordered");
        refuses(() -> M3ToolProviders.command(task("c", List.of("--", "--m3-llvm-unknown=value")), root),
                "unknown adapter directives remain errors after the compiler delimiter");
    }

    private static void preservesLegacyBinSelection(Path root) throws Exception {
        String selector = LlvmCompilerProvider.BIN_ARGUMENT;
        Path absentBin = root.resolve("not installed/../future toolchain/bin").normalize();
        List<String> command = M3ToolProviders.command(task("c", List.of(selector
                + "not installed/../future toolchain/bin", "-c", "a.c")), root);
        require(command.equals(List.of(absentBin.resolve(filename("clang")).toString(), "-c", "a.c")),
                "legacy relative bin is normalized against module root without requiring an installation");
        require(!Files.exists(absentBin), "legacy bin planning creates no directory");
        require(M3ToolProviders.command(task("llvm-ir", List.of(selector + absentBin, "-filetype=obj", "a.ll")), root)
                        .equals(List.of(absentBin.resolve(filename("llc")).toString(), "-filetype=obj", "a.ll")),
                "legacy bin and llvm-ir retain llc dispatch");
        List<String> afterDelimiter = List.of("--", selector + absentBin, "a.c");
        require(M3ToolProviders.command(task("c", afterDelimiter), root).equals(List.of(
                absentBin.resolve(filename("clang")).toString(), "--", "a.c")),
                "legacy bin is consumed after -- without consuming the compiler delimiter");
        String saved = System.getProperty(LlvmCompilerProvider.HOME_PROPERTY);
        try {
            System.setProperty(LlvmCompilerProvider.HOME_PROPERTY, root.resolve("invalid home").toString());
            require(M3ToolProviders.command(task("c", List.of(selector + absentBin, "a.c")), root).get(0)
                            .equals(absentBin.resolve(filename("clang")).toString()),
                    "explicit bin overrides the shared home property without probing it");
        } finally {
            restore(LlvmCompilerProvider.HOME_PROPERTY, saved);
        }
        refuses(() -> M3ToolProviders.command(task("c", List.of(selector + absentBin,
                LlvmCompilerProvider.SHA256_ARGUMENT + "0".repeat(64), "a.c")), root),
                "a digest requires a real executable even with legacy bin planning");
    }

    private static void selectsConfiguredHome(Path root, Path home) throws Exception {
        Path other = fakeHome(root.resolve("another LLVM"));
        List<String> explicit = List.of(LlvmCompilerProvider.HOME_ARGUMENT + "another LLVM", "-c", "src/hello.c");
        List<String> command = M3ToolProviders.command(task("c", explicit), root);
        require(command.equals(List.of(other.resolve("bin").resolve(filename("clang")).toString(), "-c", "src/hello.c")),
                "task home overrides property and is relative to module, not working directory");
        require(M3ToolProviders.command(task("c", List.of("src/hello.c")), root).get(0)
                .equals(home.resolve("bin").resolve(filename("clang")).toString()), "property used without task option");
        System.setProperty(LlvmCompilerProvider.HOME_PROPERTY, "LLVM installation");
        try {
            require(M3ToolProviders.command(task("c", List.of("src/hello.c")), root).get(0)
                    .equals(home.resolve("bin").resolve(filename("clang")).toString()),
                    "relative property home has module-root semantics");
        } finally {
            System.setProperty(LlvmCompilerProvider.HOME_PROPERTY, home.toString());
        }
        refuses(() -> M3ToolProviders.command(task("c", List.of(LlvmCompilerProvider.HOME_ARGUMENT + "absent", "a.c")), root),
                "configured missing home must not fall back to PATH");
        Path emptyHome = Files.createDirectories(root.resolve("no compilers/bin")).getParent();
        refuses(() -> M3ToolProviders.command(task("fortran", List.of(LlvmCompilerProvider.HOME_ARGUMENT + emptyHome, "a.f90")), root),
                "missing Flang must not fall back to a different frontend");
        Path fallback = fakeHome(root.resolve("older Flang installation"));
        Path flangNew = fallback.resolve("bin").resolve(filename("flang-new"));
        Files.move(fallback.resolve("bin").resolve(filename("flang")), flangNew);
        String digest = InputStamps.fileSha256(flangNew);
        require(M3ToolProviders.command(task("fortran", List.of(LlvmCompilerProvider.HOME_ARGUMENT + fallback,
                LlvmCompilerProvider.SHA256_ARGUMENT + digest, "a.f90")), root).equals(List.of(flangNew.toString(), "a.f90")),
                "home selection pins and preserves the flang-new fallback driver");
        if (!windows()) {
            Path nonExecutable = Files.createDirectories(root.resolve("non executable/bin")).resolve("clang");
            Files.writeString(nonExecutable, "non-executable", StandardCharsets.UTF_8);
            require(!Files.isExecutable(nonExecutable), "fixture is not executable");
            String selected = LlvmCompilerProvider.HOME_ARGUMENT + nonExecutable.getParent().getParent();
            require(M3ToolProviders.command(task("c", List.of(selected, "a.c")), root).get(0)
                    .equals(nonExecutable.toString()), "regular-file selection does not execute the driver");
            Files.writeString(root.resolve("permission.c"), "int main(void) { return 0; }\n", StandardCharsets.UTF_8);
            Path manifest = root.resolve("permission.tsv");
            Files.writeString(manifest, TaskManifest.MAGIC + "\n"
                    + "task\tpermission\t@m3:llvm:compiler:c\t.\n"
                    + "arg\tpermission\t" + selected + "\n"
                    + "arg\tpermission\tpermission.c\n"
                    + "arg\tpermission\t-o\n"
                    + "arg\tpermission\tpermission.o\n"
                    + "input\tpermission\tpermission.c\n"
                    + "output\tpermission\tpermission.o\n", StandardCharsets.UTF_8);
            try {
                TaskRunner.run(TaskManifest.load(manifest), root, "permission", false,
                        Duration.ofSeconds(3), line -> { });
                throw new AssertionError("non-executable driver must fail at process launch");
            } catch (IOException expected) {
                require(expected.getMessage().startsWith("cannot start tool for target permission"),
                        "execute permission failure propagates through the actual runner");
            }
            require(!Files.exists(root.resolve("permission.o")), "refused driver creates no compiler output");
        }
    }

    private static void validatesExecutablePin(Path root, Path home) throws Exception {
        Path compiler = home.resolve("bin").resolve(filename("clang"));
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(compiler)));
        List<String> args = List.of(LlvmCompilerProvider.SHA256_ARGUMENT + expected.toUpperCase(Locale.ROOT), "-c", "a.c");
        require(M3ToolProviders.command(task("c", args), root).equals(List.of(compiler.toString(), "-c", "a.c")),
                "uppercase digest accepted and stripped from compiler argv");
        refuses(() -> M3ToolProviders.command(task("c", List.of(LlvmCompilerProvider.SHA256_ARGUMENT + "0".repeat(64), "a.c")), root),
                "wrong executable bytes");
        byte[] original = Files.readAllBytes(compiler);
        try {
            Files.writeString(compiler, "changed executable contents", StandardCharsets.UTF_8);
            refuses(() -> M3ToolProviders.command(task("c", args), root), "tool changed after pin creation");
        } finally {
            Files.write(compiler, original);
        }
    }

    /** Fresh JVMs own controlled environments; no reflective mutation of System.getenv. */
    private static void selectsEnvironmentAndRefusesUnselectedPins(Path root, Path propertyHome) throws Exception {
        Path module = Files.createDirectories(root.resolve("lookup module"));
        Path cwd = Files.createDirectories(module.resolve("nested build"));
        Path cwdDriver = fakeTool(cwd.resolve(filename("clang")));
        Path envHome = fakeHome(module.resolve("environment LLVM"));
        Path pathHome = fakeHome(module.resolve("PATH LLVM"));
        Path taskHome = fakeHome(module.resolve("task LLVM"));
        List<String> flags = List.of("a.c");
        lookupChild(module, null, "environment LLVM", pathHome.resolve("bin"),
                envHome.resolve("bin").resolve(filename("clang")).toString(), flags);
        lookupChild(module, propertyHome.toString(), envHome.toString(), pathHome.resolve("bin"),
                propertyHome.resolve("bin").resolve(filename("clang")).toString(), flags);
        lookupChild(module, " ", envHome.toString(), pathHome.resolve("bin"),
                envHome.resolve("bin").resolve(filename("clang")).toString(), flags);
        lookupChild(module, propertyHome.toString(), envHome.toString(), pathHome.resolve("bin"),
                taskHome.resolve("bin").resolve(filename("clang")).toString(),
                List.of(LlvmCompilerProvider.HOME_ARGUMENT + "task LLVM", "a.c"));
        lookupChild(module, propertyHome.toString(), envHome.toString(), pathHome.resolve("bin"),
                taskHome.resolve("bin").resolve(filename("clang")).toString(),
                List.of(LlvmCompilerProvider.BIN_ARGUMENT + "task LLVM/bin", "a.c"));
        lookupChild(module, null, null, pathHome.resolve("bin"), filename("clang"), flags);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(cwdDriver)));
        List<String> pinned = List.of(LlvmCompilerProvider.SHA256_ARGUMENT + digest, "a.c");
        lookupChild(module, null, null, pathHome.resolve("bin"), "REFUSE_UNSELECTED_PIN", pinned);
        lookupChild(module, null, null, pathHome.resolve("bin"), cwdDriver.toString(),
                List.of(LlvmCompilerProvider.BIN_ARGUMENT + "nested build", LlvmCompilerProvider.SHA256_ARGUMENT + digest, "a.c"));
        Files.delete(cwdDriver);
        lookupChild(module, null, null, pathHome.resolve("bin"), "REFUSE_UNSELECTED_PIN", pinned);
        lookupChild(module, null, null, Files.createDirectories(module.resolve("empty PATH")), filename("clang"), flags);
    }

    private static void lookupChild(Path workspace, String propertyHome, String envHome, Path path, String expected, List<String> flags)
            throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString());
        if (propertyHome != null) command.add("-D" + LlvmCompilerProvider.HOME_PROPERTY + "=" + propertyHome);
        command.add("-cp");
        var classpath = new ArrayList<String>();
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            classpath.add(Path.of(entry).toAbsolutePath().normalize().toString());
        }
        command.add(String.join(java.io.File.pathSeparator, classpath));
        command.add(LlvmInstallationSelectionContractTestMain.class.getName());
        command.addAll(List.of("--probe-lookup", workspace.toString(), expected));
        command.addAll(flags);
        ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile()).inheritIO();
        builder.environment().keySet().removeIf(key -> key.equalsIgnoreCase("M3_LLVM_HOME")
                || key.equalsIgnoreCase("LLVM_HOME") || key.equalsIgnoreCase("PATH")
                || key.equals("JAVA_TOOL_OPTIONS") || key.equals("JDK_JAVA_OPTIONS") || key.equals("_JAVA_OPTIONS"));
        builder.environment().put("PATH", path.toString());
        if (envHome != null) builder.environment().put("M3_LLVM_HOME", envHome);
        Process child = builder.start();
        try {
            require(child.waitFor(30, TimeUnit.SECONDS), "bounded fresh-JVM tool lookup");
            require(child.exitValue() == 0, "canonical installation precedence and explicit-directory pinning in fresh JVM");
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void rejectsMalformedOptions(Path root) throws Exception {
        for (List<String> args : List.of(
                List.<String>of(),
                List.of(LlvmCompilerProvider.BIN_ARGUMENT),
                List.of(LlvmCompilerProvider.BIN_ARGUMENT + " "),
                List.of(LlvmCompilerProvider.BIN_ARGUMENT + "a", LlvmCompilerProvider.BIN_ARGUMENT + "b", "a.c"),
                List.of(LlvmCompilerProvider.BIN_ARGUMENT + "a", LlvmCompilerProvider.HOME_ARGUMENT + "b", "a.c"),
                List.of(LlvmCompilerProvider.HOME_ARGUMENT + "a", LlvmCompilerProvider.BIN_ARGUMENT + "b", "a.c"),
                List.of(LlvmCompilerProvider.BIN_ARGUMENT + "a"),
                List.of(LlvmCompilerProvider.HOME_ARGUMENT),
                List.of(LlvmCompilerProvider.HOME_ARGUMENT + " "),
                List.of(LlvmCompilerProvider.HOME_ARGUMENT + "a", LlvmCompilerProvider.HOME_ARGUMENT + "b"),
                List.of(LlvmCompilerProvider.SHA256_ARGUMENT),
                List.of(LlvmCompilerProvider.SHA256_ARGUMENT + "abc"),
                List.of(LlvmCompilerProvider.SHA256_ARGUMENT + "g".repeat(64)),
                List.of(LlvmCompilerProvider.SHA256_ARGUMENT + "0".repeat(64), LlvmCompilerProvider.SHA256_ARGUMENT + "1".repeat(64)),
                List.of("--m3-llvm-download=latest"),
                List.of("--m3-llvm-home", "tools"),
                List.of("--", LlvmCompilerProvider.HOME_ARGUMENT + "a", LlvmCompilerProvider.BIN_ARGUMENT + "b", "a.c"),
                List.of("--", LlvmCompilerProvider.HOME_ARGUMENT + "a", LlvmCompilerProvider.HOME_ARGUMENT + "b", "a.c"),
                List.of("--", LlvmCompilerProvider.SHA256_ARGUMENT + "abc", "a.c"))) {
            refuses(() -> M3ToolProviders.command(task("c", args), root), "malformed provider options " + args);
        }
        refuses(() -> M3ToolProviders.command(task("java", List.of("A.java")), root), "unsupported Java frontend");
        refuses(() -> M3ToolProviders.command(task("objcxx", List.of("a.mm")), root), "unadvertised Objective-C++ spelling");
        var interpreter = new TaskManifest.Task("bad", "@m3:llvm:interpreter:c", ".", List.of(), List.of(),
                List.of("src/hello.c"), List.of("target/out"));
        refuses(() -> M3ToolProviders.command(interpreter, root), "unsupported interpreter role");
        var noOutput = new TaskManifest.Task("bad", "@m3:llvm:compiler:c", ".", List.of("a.c"), List.of(), List.of("a.c"), List.of());
        refuses(() -> M3ToolProviders.command(noOutput, root), "compiler requires declared outputs");
        var overlap = new TaskManifest.Task("bad", "@m3:llvm:compiler:c", ".", List.of("a.c"), List.of(),
                List.of("src"), List.of("src/out.o"));
        refuses(() -> M3ToolProviders.command(overlap, root), "existing source/output separation retained");
    }

    private static void preservesDriverSymlink(Path root) throws Exception {
        if (windows()) return; // Windows symlink privileges are independent of driver-name coverage below.
        Path home = Files.createDirectories(root.resolve("symlink LLVM/bin")).getParent();
        Path clang = fakeTool(home.resolve("bin/clang"));
        Files.createSymbolicLink(home.resolve("bin/clang++"), Path.of("clang"));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(clang)));
        List<String> command = M3ToolProviders.command(task("cpp", List.of(LlvmCompilerProvider.HOME_ARGUMENT + home,
                LlvmCompilerProvider.SHA256_ARGUMENT + digest, "a.cpp")), root);
        require(command.get(0).equals(home.resolve("bin/clang++").toString()), "clang++ argv[0] survives symlink validation");
    }

    private static void windowsDriverNames(Path root) throws Exception {
        String original = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows test");
            Path home = fakeHome(root.resolve("Windows LLVM"));
            require(M3ToolProviders.command(task("cpp", List.of(LlvmCompilerProvider.HOME_ARGUMENT + home, "a.cpp")), root)
                    .get(0).equals(home.resolve("bin/clang++.exe").toString()), "Windows executable suffix planning");
        } finally {
            restore("os.name", original);
        }
    }

    private static void plansWithoutExecutionOrState(Path root, Path home) throws Exception {
        Path planRoot = Files.createDirectories(root.resolve("read only plan"));
        Path manifest = planRoot.resolve("m3-build.tsv");
        Files.writeString(manifest, TaskManifest.MAGIC + "\n"
                + "task\tcompile\t@m3:llvm:compiler:c\t.\n"
                + "arg\tcompile\t" + LlvmCompilerProvider.HOME_ARGUMENT + home + "\n"
                + "arg\tcompile\tsrc/not-created-yet.c\n"
                + "input\tcompile\tsrc/not-created-yet.c\n"
                + "output\tcompile\ttarget/not-created.o\n", StandardCharsets.UTF_8);
        List<Path> before = paths(planRoot);
        var messages = new ArrayList<String>();
        List<String> planned = TaskRunner.run(TaskManifest.load(manifest), planRoot, "compile", true,
                Duration.ofSeconds(3), false, true, "embedded", planRoot.resolve("missing cache"), messages::add);
        require(planned.equals(List.of("compile")), "actual runner plans the LLVM task");
        require(messages.size() == 1 && messages.get(0).startsWith("PLAN compile"), "no execution or state reuse claimed");
        require(paths(planRoot).equals(before), "planning creates no source, output, cache or module-state file");
        Files.writeString(manifest, Files.readString(manifest, StandardCharsets.UTF_8).replace(
                LlvmCompilerProvider.HOME_ARGUMENT + home, LlvmCompilerProvider.BIN_ARGUMENT + "future LLVM/bin"),
                StandardCharsets.UTF_8);
        messages.clear();
        require(TaskRunner.run(TaskManifest.load(manifest), planRoot, "compile", true,
                        Duration.ofSeconds(3), false, true, "embedded", planRoot.resolve("missing cache"), messages::add)
                        .equals(List.of("compile")),
                "legacy bin plans through the actual runner without an installed tool");
        require(messages.size() == 1 && messages.get(0).startsWith("PLAN compile"),
                "missing legacy tool is never executed during planning");
        require(paths(planRoot).equals(before), "no-tool planning creates no tool, source, output, cache or module-state file");
    }

    private static TaskManifest.Task task(String language, List<String> args) {
        return new TaskManifest.Task("compile", "@m3:llvm:compiler:" + language, "nested build", args,
                List.of(), List.of("src/hello.c"), List.of("target/hello.o"));
    }

    private static Path fakeHome(Path home) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        for (String driver : List.of("clang", "clang++", "flang", "llc")) fakeTool(home.resolve("bin").resolve(filename(driver)));
        return home;
    }

    private static Path fakeTool(Path file) throws IOException {
        Files.writeString(file, "These bytes must never execute during planning.\n", StandardCharsets.UTF_8);
        if (!file.toFile().setExecutable(true, true) && !windows()) throw new IOException("cannot prepare fixture mode");
        return file;
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    private static String filename(String driver) {
        return driver + (windows() ? ".exe" : "");
    }

    @FunctionalInterface private interface Attempt { void run() throws Exception; }

    private static void refuses(Attempt attempt, String message) throws Exception {
        try {
            attempt.run();
        } catch (IOException expected) {
            checks++;
            return;
        }
        throw new AssertionError("must refuse " + message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }

    private static List<Path> paths(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.sorted().toList();
        }
    }

    private static void erase(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
}
