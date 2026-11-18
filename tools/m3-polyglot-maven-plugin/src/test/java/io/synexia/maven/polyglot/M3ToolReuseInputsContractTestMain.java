/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Dependency-free provider admission tests; successful compiler execution is qualified separately. */
public final class M3ToolReuseInputsContractTestMain {
    private static int passed;

    private M3ToolReuseInputsContractTestMain() {}

    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("m3-provider-reuse-contract-");
        try {
            Files.createDirectories(root.resolve("src"));
            Files.createDirectories(root.resolve("classpath"));
            Files.writeString(root.resolve("src/Hello.java"), "public class Hello {}\n");
            Path toolchain = fakeToolchain(root.resolve("toolchain"));
            immutableBoundedContract(root);
            defaultProviderStillRuns(root);
            llvmDeclinesReuseEvenWhenRequested(root);
            qualifiesExplicitJdkInputs(root, toolchain);
            rejectsUnmodelledCompilerState(root, toolchain);
            rejectsUnmodelledPaths(root, toolchain);
            rejectsInjectedEnvironment(root, toolchain);
            validatesRegistryContract(root);
            refusesSymbolicLinks(root, toolchain);
            System.out.println("PASS: " + passed + " provider reuse-input contract assertions");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void immutableBoundedContract(Path root) throws Exception {
        var paths = new ArrayList<>(List.of(root.resolve("src"), root.resolve("classpath")));
        var outputs = new ArrayList<>(List.of("classes"));
        M3ToolReuseInputs contract = new M3ToolReuseInputs("probe.v1", paths, outputs, false);
        paths.clear();
        outputs.clear();
        require(contract.additionalInputs().size() == 2 && contract.cleanOutputs().equals(List.of("classes")),
                "contract copies caller collections");
        require(contract.additionalInputs().get(0).equals(root.resolve("classpath")), "stable additional input order");
        rejectsRuntime(() -> contract.additionalInputs().clear(), "immutable additional inputs");
        rejectsRuntime(() -> contract.cleanOutputs().clear(), "immutable clean outputs");
        rejectsRuntime(() -> new M3ToolReuseInputs("", List.of(), List.of(), false), "empty contract ID");
        rejectsRuntime(() -> new M3ToolReuseInputs("probe.v1", List.of(Path.of("relative")), List.of(), false),
                "relative additional input");
        rejectsRuntime(() -> new M3ToolReuseInputs("probe.v1", List.of(root.resolve("a/../b")), List.of(), false),
                "unnormalized additional input");
        rejectsRuntime(() -> new M3ToolReuseInputs("probe.v1", List.of(root, root), List.of(), false),
                "duplicate additional input");
        rejectsRuntime(() -> new M3ToolReuseInputs("probe.v1", List.of(), List.of("../classes"), false),
                "traversal clean output");
        rejectsRuntime(() -> new M3ToolReuseInputs("probe.v1", List.of(), List.of("classes", "classes"), false),
                "duplicate clean output");
        rejectsRuntime(() -> new M3ToolReuseInputs("probe.v1", List.of(), List.of("classes", "classes/nested"), false),
                "overlapping clean outputs");
        var excessive = new ArrayList<Path>();
        for (int i = 0; i < 257; i++) excessive.add(root.resolve("input-" + i));
        rejectsRuntime(() -> new M3ToolReuseInputs("probe.v1", excessive, List.of(), false), "bounded contract");
    }

    private static void defaultProviderStillRuns(Path root) throws Exception {
        M3ToolProvider oldProvider = new M3ToolProvider() {
            @Override public String id() { return "old-provider"; }
            @Override public Set<M3ToolCapability> capabilities() { return Set.of(M3ToolCapability.COMPILER); }
            @Override public Set<String> languages() { return Set.of("java"); }
            @Override public List<String> command(M3ToolCapability role, String language,
                    TaskManifest.Task task, Path workspace) { return List.of("compiler"); }
        };
        require(oldProvider.reuseInputs(M3ToolCapability.COMPILER, "java", task(arguments()), root).isEmpty(),
                "unmodified provider inherits ALWAYS_RUN");
        var planned = M3ToolProviders.plan(task(arguments()), root, false);
        require(planned.reuseInputs().isEmpty(), "existing command path never requests reuse");
        require(planned.command().equals(M3ToolProviders.command(task(arguments()), root)), "original argv preserved");
        require(planned.command().contains("jdk.compiler/com.sun.tools.javac.Main"), "original compiler owner preserved");
    }

    /** LLVM's original driver commands never grant an implicit native cache hit. */
    private static void llvmDeclinesReuseEvenWhenRequested(Path root) throws Exception {
        for (String language : List.of("c", "cpp", "objc", "objcpp", "objc++", "fortran", "llvm-ir")) {
            var task = new TaskManifest.Task("compile", "@m3:llvm:compiler:" + language, ".",
                    List.of("--m3-llvm-bin=not-installed/bin", "-c", "source.c", "-o", "output.o"),
                    List.of(), List.of("source.c"), List.of("output.o"));
            var original = M3ToolProviders.plan(task, root, false);
            var requested = M3ToolProviders.plan(task, root, true);
            require(original.reuseInputs().isEmpty() && requested.reuseInputs().isEmpty(),
                    "unqualified LLVM provider must not produce native reuse evidence: " + language);
            require(original.command().equals(requested.command())
                    && requested.command().equals(M3ToolProviders.command(task, root)),
                    "provider reuse opt-in must preserve original LLVM argv: " + language);
            passed++;
        }
    }

    private static void qualifiesExplicitJdkInputs(Path root, Path home) throws Exception {
        M3ToolReuseInputs contract = qualified(root, home, task(arguments()), Map.of()).orElseThrow();
        require(contract.contractId().equals("m3.jdk.javac.directory-inputs.v1"), "versioned JDK input contract");
        require(contract.additionalInputs().equals(List.of(home.resolve("bin"), home.resolve("conf"),
                home.resolve("lib"), home.resolve("release"))), "all executable JDK inputs included");
        require(contract.cleanOutputs().equals(List.of("classes")), "dedicated javac output is replaced");
        require(!contract.relocatable(), "local reuse does not claim relocatable debug metadata");
        var flags = new ArrayList<>(arguments());
        flags.addAll(0, List.of("--release", "17", "-g:none", "-parameters"));
        require(qualified(root, home, task(flags), Map.of()).isPresent(), "controlled release/debug options");
        Path nested = root.resolve("nested");
        Files.createDirectories(nested.resolve("src"));
        Files.createDirectories(nested.resolve("classpath"));
        Files.writeString(nested.resolve("src/Hello.java"), "public class Hello {}\n");
        TaskManifest.Task task = new TaskManifest.Task("nested", "@m3:jdk:compiler:java", "nested", arguments(),
                List.of(), List.of("nested/src", "nested/classpath"), List.of("nested/classes"));
        require(qualified(root, home, task, Map.of()).isPresent(), "argv paths resolve against declared working directory");
    }

    private static void rejectsUnmodelledCompilerState(Path root, Path home) throws Exception {
        for (String required : List.of("-proc:none", "-implicit:none", "-Xprefer:source")) {
            var values = new ArrayList<>(arguments());
            values.remove(required);
            absent(root, home, task(values), "missing " + required);
        }
        for (String flag : List.of("-processor", "-processorpath", "--processor-module-path", "-Xplugin:Probe",
                "-J-javaagent:probe.jar", "@options.txt", "--module-path", "--patch-module", "--system", "-h",
                "--enable-preview", "-Xbootclasspath:outside", "--release=17", "-Xprefer:newer")) {
            var values = new ArrayList<>(arguments());
            values.add(0, flag);
            absent(root, home, task(values), "unmodelled compiler flag " + flag);
        }
        for (String option : List.of("-classpath", "-sourcepath", "-encoding")) {
            var values = new ArrayList<>(arguments());
            int index = values.indexOf(option);
            values.remove(index + 1);
            values.remove(index);
            absent(root, home, task(values), "missing explicit " + option);
        }
        absent(root, home, task(replace("UTF-8", "windows-1252")), "nonqualified source encoding");
        absent(root, home, task(replace("-Xprefer:source", "-Xprefer:newer")),
                "timestamp-sensitive implicit source/class preference");
        var duplicatePreference = new ArrayList<>(arguments());
        duplicatePreference.add("-Xprefer:source");
        absent(root, home, task(duplicatePreference), "duplicate implicit source preference");
        var repeated = new ArrayList<>(arguments());
        repeated.addAll(0, List.of("-cp", "classpath"));
        absent(root, home, task(repeated), "duplicate classpath alias");
        var conflicting = new ArrayList<>(arguments());
        conflicting.addAll(0, List.of("--release", "17", "-source", "17"));
        absent(root, home, task(conflicting), "incompatible release/source options");
    }

    private static void rejectsUnmodelledPaths(Path root, Path home) throws Exception {
        absent(root, home, task(replace("classpath", ".")), "implicit current-directory classpath");
        absent(root, home, task(replace("classpath", "classpath/*")), "wildcard classpath");
        absent(root, home, task(replace("classpath", "classpath" + java.io.File.pathSeparator)), "empty classpath element");
        Files.writeString(root.resolve("dependency.jar"), "not a qualified JAR");
        absent(root, home, task(replace("classpath", "dependency.jar")), "JAR Class-Path closure is unqualified");
        absent(root, home, task(replace("classpath", "missing")), "absent search directory");
        absent(root, home, task(replace("src/Hello.java", root.resolve("src/Hello.java").toString())),
                "absolute source argv");
        absent(root, home, task(replace("src/Hello.java", "../outside.java")), "escaping source argv");
        Files.writeString(root.resolve("src/module-info.java"), "module sample {}\n");
        absent(root, home, task(replace("src/Hello.java", "src/module-info.java")), "module compilation is unqualified");
        TaskManifest.Task original = task(arguments());
        TaskManifest.Task missingClasspath = new TaskManifest.Task(original.name(), original.tool(), ".", arguments(),
                List.of(), List.of("src"), original.outputs());
        absent(root, home, missingClasspath, "undeclared classpath directory");
        TaskManifest.Task fileOutput = new TaskManifest.Task(original.name(), original.tool(), ".", arguments(),
                List.of(), original.inputs(), List.of("classes/Hello.class"));
        absent(root, home, fileOutput, "output ownership must cover complete -d directory");
        absent(root, home, task(replace("classes", "src")), "output overlaps declared sources");
        Path incomplete = fakeToolchain(root.resolve("incomplete-toolchain"));
        Files.delete(incomplete.resolve("lib/modules"));
        absent(root, incomplete, original, "missing compiler module image");
    }

    private static void rejectsInjectedEnvironment(Path root, Path home) throws Exception {
        for (String key : List.of("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVAC_OPTIONS",
                "LD_PRELOAD", "LD_LIBRARY_PATH", "LD_AUDIT", "LD_UNKNOWN_EXTENSION",
                "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH", "DYLD_FALLBACK_LIBRARY_PATH")) {
            require(qualified(root, home, task(arguments()), Map.of(key, "injected-file-or-option")).isEmpty(),
                    "untracked environment injection " + key);
        }
    }

    private static void validatesRegistryContract(Path root) throws Exception {
        TaskManifest.Task task = task(arguments());
        M3ToolReuseInputs raw = new M3ToolReuseInputs("probe.v1", List.of(root.resolve("src")), List.of("classes"), false);
        var bound = M3ToolProviders.reuseInputs(provider(Optional.of(raw)), M3ToolCapability.COMPILER,
                "java", task, root).orElseThrow();
        Path implementation = Path.of(M3ToolReuseInputsContractTestMain.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        require(bound.additionalInputs().contains(implementation), "registry binds actual provider implementation bytes");
        rejectsIo(() -> M3ToolProviders.reuseInputs(provider(null), M3ToolCapability.COMPILER, "java", task, root),
                "null reuse Optional");
        M3ToolReuseInputs unrelated = new M3ToolReuseInputs("probe.v1", List.of(), List.of("unowned"), false);
        rejectsIo(() -> M3ToolProviders.reuseInputs(provider(Optional.of(unrelated)),
                M3ToolCapability.COMPILER, "java", task, root), "undeclared cleanup target");
        TaskManifest.Task overlap = new TaskManifest.Task("overlap", task.tool(), ".", arguments(),
                List.of(), List.of("classes/source.java"), List.of("classes"));
        rejectsIo(() -> M3ToolProviders.reuseInputs(provider(Optional.of(raw)),
                M3ToolCapability.COMPILER, "java", overlap, root), "cleanup overlaps source input");
        M3ToolReuseInputs hiddenOverlap = new M3ToolReuseInputs("probe.v1",
                List.of(root.resolve("classes/dependency")), List.of("classes"), false);
        rejectsIo(() -> M3ToolProviders.reuseInputs(provider(Optional.of(hiddenOverlap)),
                M3ToolCapability.COMPILER, "java", task, root), "cleanup overlaps additional provider input");
    }

    private static void refusesSymbolicLinks(Path root, Path home) throws Exception {
        Path sourceLink = root.resolve("src/link.java");
        try {
            Files.createSymbolicLink(sourceLink, root.resolve("src/Hello.java"));
        } catch (UnsupportedOperationException | IOException unavailable) {
            System.out.println("NOT_RUN: symbolic-link fixture unavailable: " + unavailable.getClass().getSimpleName());
            return;
        }
        absent(root, home, task(arguments()), "sourcepath symlinks leave reuse disabled");
        Files.delete(sourceLink);
        Path toolLink = home.resolve("lib/linked-module");
        Files.createSymbolicLink(toolLink, home.resolve("lib/modules"));
        absent(root, home, task(arguments()), "toolchain symlinks leave reuse disabled");
        Files.delete(toolLink);
    }

    private static M3ToolProvider provider(Optional<M3ToolReuseInputs> inputs) {
        return new M3ToolProvider() {
            @Override public String id() { return "reuse-probe"; }
            @Override public Set<M3ToolCapability> capabilities() { return Set.of(M3ToolCapability.COMPILER); }
            @Override public Set<String> languages() { return Set.of("java"); }
            @Override public List<String> command(M3ToolCapability role, String language,
                    TaskManifest.Task task, Path workspace) { return List.of("compiler"); }
            @Override public Optional<M3ToolReuseInputs> reuseInputs(M3ToolCapability role, String language,
                    TaskManifest.Task task, Path workspace) { return inputs; }
        };
    }

    private static List<String> arguments() {
        return List.of("-proc:none", "-implicit:none", "-Xprefer:source", "-encoding", "UTF-8", "-classpath", "classpath",
                "-sourcepath", "src", "-d", "classes", "src/Hello.java");
    }

    private static List<String> replace(String from, String to) {
        var result = new ArrayList<>(arguments());
        result.set(result.indexOf(from), to);
        return result;
    }

    private static TaskManifest.Task task(List<String> arguments) {
        return new TaskManifest.Task("compile", "@m3:jdk:compiler:java", ".", arguments,
                List.of(), List.of("src", "classpath"), List.of("classes"));
    }

    private static Path fakeToolchain(Path home) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.createDirectories(home.resolve("lib"));
        Files.createDirectories(home.resolve("conf/security"));
        String java = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        Files.writeString(home.resolve("bin").resolve(java), "launcher fixture");
        if (!home.resolve("bin").resolve(java).toFile().setExecutable(true)) {
            throw new IOException("cannot prepare executable launcher fixture");
        }
        Files.writeString(home.resolve("lib/modules"), "module image fixture");
        Files.writeString(home.resolve("conf/security/java.security"), "policy fixture");
        Files.writeString(home.resolve("release"), "JAVA_VERSION=21\n");
        return home.toAbsolutePath().normalize();
    }

    private static Optional<M3ToolReuseInputs> qualified(Path root, Path home,
            TaskManifest.Task task, Map<String, String> environment) throws IOException {
        return JdkReuseInputs.inspect(task, root, environment, home);
    }

    private static void absent(Path root, Path home, TaskManifest.Task task, String label) throws IOException {
        require(qualified(root, home, task, Map.of()).isEmpty(), label);
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }

    private static void rejectsIo(Checked operation, String message) throws Exception {
        try { operation.run(); } catch (IOException expected) { passed++; return; }
        throw new AssertionError("expected IOException: " + message);
    }

    private static void rejectsRuntime(Checked operation, String message) throws Exception {
        try { operation.run(); } catch (RuntimeException expected) { passed++; return; }
        throw new AssertionError("expected runtime refusal: " + message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }
}
