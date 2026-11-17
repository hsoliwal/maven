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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The ECJ compiler provider: javac-to-ECJ argument parity with plexus-compiler-eclipse, refusals for what ECJ
 * cannot honour (including the inline -Xplugin:M3AST atomizer unless explicitly skipped), jar selection and SHA-256
 * pinning, and, when {@code -Dm3.polyglot.ecj.jar=<ecj.jar>} names a real ECJ, compiles through the task runner.
 */
public final class EcjCompilerProviderContractTestMain {
    private static int passed;

    private EcjCompilerProviderContractTestMain() { }

    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("m3-ecj-contract-");
        try {
            mapsJavacArgumentsLikePlexusCompilerEclipse(root);
            refusesWhatEcjCannotHonour(root);
            selectsAndPinsTheJar(root);
            String live = liveCompile(root.resolve("live"));
            System.out.println("PASS: " + passed + " ECJ provider contract cases; live ECJ compile " + live);
        } finally {
            erase(root);
        }
    }

    private static void mapsJavacArgumentsLikePlexusCompilerEclipse(Path root) throws Exception {
        var mapped = EcjArguments.map(List.of("--release", "17", "-encoding", "UTF-8", "-g", "-Werror", "-nowarn",
                "-Xlint:deprecation,-unchecked,rawtypes,cast", "-Xlint:none", "-Xlint", "-Xlint:all",
                "-proceedOnError", "-Xmaxerrs", "5", "-Xdoclint:all", "-Xdiags:verbose", "-Xmaxwarns", "7",
                "-parameters", "-proc:none", "--class-path", "lib", "--source-path", "src", "-d", "out",
                "-log", "target/ecj.xml", "A.java"), root);
        require(mapped.args().equals(List.of("--release", "17", "-encoding", "UTF-8", "-g", "-preserveAllLocals",
                "-failOnWarning", "-warn:none", "-warn:+deprecation,-unchecked,+raw", "-warn:none",
                "-proceedOnError:Fatal", "-maxProblems", "5", "-parameters", "-proc:none", "-classpath", "lib",
                "-sourcepath", "src", "-d", "out", "-log", "target/ecj.xml", "A.java")), "mapped " + mapped.args());
        require(mapped.jar() == null && mapped.sha256() == null && !mapped.atomizerSkipped(), "no provider settings");
        Files.writeString(root.resolve("jdt.prefs"), "eclipse.preferences.version=1\n", StandardCharsets.UTF_8);
        require(EcjArguments.map(List.of("-properties", "jdt.prefs", "-source", "1.8", "-target", "1.8"), root)
                .args().equals(List.of("-properties", "jdt.prefs", "-source", "1.8", "-target", "1.8")),
                "JDT preferences pass to ECJ's -properties, source and target unchanged");
        passed++;
    }

    private static void refusesWhatEcjCannotHonour(Path root) throws Exception {
        String message = refuses(() -> EcjArguments.map(List.of("-Xplugin:M3AST", "A.java"), root), "javac plugin");
        require(message.contains("-Xplugin:M3AST") && message.contains(EcjArguments.ATOMIZER_SKIP),
                "the refusal names the plugin and the explicit skip: " + message);
        var skipped = EcjArguments.map(List.of("-Xplugin:M3AST mode=inline", EcjArguments.ATOMIZER_SKIP, "A.java"), root);
        require(skipped.args().equals(List.of("A.java")) && skipped.atomizerSkipped(), "explicit atomizer skip");
        for (String option : List.of("-XDrawDiagnostics", "-implicit:none", "-J-Xmx1g", "-Xprefer:newer")) {
            refuses(() -> EcjArguments.map(List.of(option, "A.java"), root), option);
        }
        refuses(() -> EcjArguments.map(List.of("-properties", "missing.prefs"), root), "missing JDT preferences");
        refuses(() -> EcjArguments.map(List.of("-properties"), root), "-properties without a value");
        refuses(() -> M3ToolProviders.command(task("@m3:ecj:compiler:python", List.of()), root), "not Java");
        refuses(() -> M3ToolProviders.command(task("@m3:ecj:transpiler:java", List.of()), root), "not a compiler");
        passed++;
    }

    private static void selectsAndPinsTheJar(Path root) throws Exception {
        Path jar = Files.createDirectories(root.resolve("tools")).resolve("ecj-fake.jar");
        Files.writeString(jar, "not really ECJ", StandardCharsets.UTF_8);
        String sha = EcjCompilerProvider.sha256(jar);
        var command = M3ToolProviders.command(task("@m3:ecj:compiler:java",
                List.of(EcjArguments.JAR + "tools/ecj-fake.jar", EcjArguments.SHA256 + sha, "-Werror", "A.java")), root);
        require(command.equals(List.of(EcjCompilerProvider.java(), "-cp", jar.toAbsolutePath().normalize().toString(),
                EcjCompilerProvider.MAIN, "-failOnWarning", "A.java")), "ECJ batch compiler argv " + command);
        refuses(() -> M3ToolProviders.command(task("@m3:ecj:compiler:java", List.of(EcjArguments.JAR
                + "tools/ecj-fake.jar", EcjArguments.SHA256 + "00" + sha.substring(2), "A.java")), root), "pin mismatch");
        Files.writeString(root.resolve("tools/ecj.zip"), "x", StandardCharsets.UTF_8);
        refuses(() -> EcjCompilerProvider.verified(root.resolve("tools/ecj.zip"), null), "not a jar");
        refuses(() -> EcjCompilerProvider.verified(root.resolve("tools/none.jar"), null), "missing jar");
        String saved = System.getProperty(EcjCompilerProvider.JAR_PROPERTY);
        try {
            System.clearProperty(EcjCompilerProvider.JAR_PROPERTY);
            String message = refuses(() -> EcjCompilerProvider.jar(null, root), "unconfigured jar");
            require(message.contains("org.eclipse.jdt:ecj"), "the refusal says how to configure ECJ: " + message);
            System.setProperty(EcjCompilerProvider.JAR_PROPERTY, jar.toString());
            require(EcjCompilerProvider.jar(null, root).equals(jar), "system property selects the jar");
        } finally {
            if (saved == null) System.clearProperty(EcjCompilerProvider.JAR_PROPERTY);
            else System.setProperty(EcjCompilerProvider.JAR_PROPERTY, saved);
        }
        passed++;
    }

    /** A real ECJ compile through the runner, when a jar is given; otherwise reports NOT_RUN. */
    private static String liveCompile(Path root) throws Exception {
        String jar = System.getProperty(EcjCompilerProvider.JAR_PROPERTY, "");
        if (jar.isBlank()) return "NOT_RUN (set -D" + EcjCompilerProvider.JAR_PROPERTY + "=<ecj.jar>)";
        Files.createDirectories(root.resolve("src/demo"));
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("src/demo/Ok.java"), "package demo;\npublic class Ok { int one() { return 1; } }\n",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/demo/Bad.java"), "package demo;\npublic class Bad { int n = \"s\"; }\n",
                StandardCharsets.UTF_8);
        TaskManifest plan = TaskManifest.load(manifest(root));
        TaskRunner.run(plan, root, "ok", false, Duration.ofSeconds(300), line -> { });
        require(Files.isRegularFile(root.resolve("target/classes/demo/Ok.class")), "ECJ wrote the class file");
        String log = Files.readString(root.resolve("target/ecj-ok.xml"), StandardCharsets.UTF_8);
        require(log.contains("Eclipse Compiler for Java"), "ECJ wrote its XML log");
        String failure = refuses(() -> TaskRunner.run(plan, root, "bad", false, Duration.ofSeconds(300), line -> { }),
                "a type error");
        require(failure.contains("polyglot tool failed: bad"), "a compile error fails the task: " + failure);
        require(Files.readString(root.resolve("target/ecj-bad.xml"), StandardCharsets.UTF_8).contains("TypeMismatch"),
                "the failing compile still leaves ECJ's diagnostics");
        passed++;
        return "PASS (" + log.replaceAll("(?s).*name=\"Eclipse Compiler for Java\\(TM\\)\" version=\"([^\"]+)\".*", "$1") + ")";
    }

    private static Path manifest(Path root) throws IOException {
        StringBuilder text = new StringBuilder(TaskManifest.MAGIC).append('\n');
        for (String name : List.of("ok", "bad")) {
            String upper = name.equals("ok") ? "Ok" : "Bad";
            text.append("task\t").append(name).append("\t@m3:ecj:compiler:java\t.\n");
            for (String arg : List.of("-Xplugin:M3AST", EcjArguments.ATOMIZER_SKIP, "--release", "17", "-d",
                    "target/classes", "-log", "target/ecj-" + name + ".xml", "src/demo/" + upper + ".java")) {
                text.append("arg\t").append(name).append('\t').append(arg).append('\n');
            }
            text.append("input\t").append(name).append("\tsrc/demo/").append(upper).append(".java\n");
            text.append("output\t").append(name).append("\ttarget/classes/demo/").append(upper).append(".class\n");
        }
        Path file = root.resolve("m3-build.tsv");
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static TaskManifest.Task task(String tool, List<String> args) {
        return new TaskManifest.Task("compile", tool, ".", args, List.of(), List.of("A.java"), List.of("out"));
    }

    @FunctionalInterface private interface Attempt { void run() throws Exception; }

    private static String refuses(Attempt attempt, String label) throws Exception {
        try {
            attempt.run();
        } catch (IOException expected) {
            return String.valueOf(expected.getMessage());
        }
        throw new AssertionError("must refuse " + label);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void erase(Path dir) throws IOException {
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(paths::add);
        }
        for (Path path : paths) Files.deleteIfExists(path);
    }
}
