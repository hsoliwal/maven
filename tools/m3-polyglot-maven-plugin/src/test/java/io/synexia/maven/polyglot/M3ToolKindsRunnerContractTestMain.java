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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * End-to-end runner hooks with a real original tool (git): a differ's exit code 1 is a
 * declared success, and a printing tool's standard output lands in a declared output.
 * Registered only by src/test/resources-kinds, so the v1 inventory suite is unaffected.
 */
public final class M3ToolKindsRunnerContractTestMain {
    private M3ToolKindsRunnerContractTestMain() {}

    /** Plans `git diff --no-index` (DIFFER/text) and `git --version` (stdout capture probe). */
    public static final class GitProbe implements M3ToolProvider {
        @Override public String id() { return "gitprobe"; }
        @Override public Set<M3ToolCapability> capabilities() {
            return Set.of(M3ToolCapability.DIFFER, M3ToolCapability.LINTER);
        }
        @Override public Set<String> languages() { return Set.of("text"); }
        @Override public List<String> command(M3ToolCapability role, String language,
                TaskManifest.Task task, Path workspace) {
            List<String> a = task.arguments();
            if (role == M3ToolCapability.LINTER) return List.of(a.get(0), "--version");
            // User config must not alter bytes: core.autocrlf=true (the Windows default) turns CRLF into LF.
            return List.of(a.get(0), "-c", "core.autocrlf=false", "-c", "core.quotePath=true", "-c", "diff.noprefix=false",
                    "-c", "diff.mnemonicPrefix=false", "diff", "--no-index", "--no-color", "--no-ext-diff",
                    "--no-textconv", "--binary", "--output=" + a.get(3), "--", a.get(1), a.get(2));
        }
        @Override public Set<Integer> successExitCodes(M3ToolCapability role, String language, TaskManifest.Task task) {
            return role == M3ToolCapability.DIFFER ? Set.of(0, 1) : Set.of(0);
        }
        @Override public Optional<String> stdoutOutput(M3ToolCapability role, String language, TaskManifest.Task task) {
            return role == M3ToolCapability.LINTER ? Optional.of(task.outputs().get(0)) : Optional.empty();
        }
        @Override public List<M3Report> reports(M3ToolCapability role, String language, TaskManifest.Task task) {
            return List.of(new M3Report(task.outputs().get(0), M3ReportFormat.UNIFIED_DIFF));
        }
        @Override public M3AstStatus astStatus(M3ToolCapability role, String language) {
            return M3AstStatus.NO_AST;
        }
    }

    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("m3-tool-kinds-run-");
        try {
            differExitOneIsSuccess(root);
            stdoutLandsInDeclaredOutput(root);
            outputThroughLinkIsRefused(root);
            System.out.println("PASS: 3 M3 tool-kind runner cases (git)");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static void differExitOneIsSuccess(Path root) throws Exception {
        Files.createDirectories(root.resolve("before"));
        Files.createDirectories(root.resolve("after"));
        Files.createDirectories(root.resolve("out"));
        Files.writeString(root.resolve("before/f.txt"), "one\r\ntwo\r\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("after/f.txt"), "one\r\nTWO\r\n", StandardCharsets.UTF_8);
        TaskRunner.run(load(root, "diff", "@m3:gitprobe:differ:text",
                List.of("git", "before", "after", "out/f.patch"), List.of("before", "after"), List.of("out/f.patch")),
                root, "diff", false, Duration.ofSeconds(60), ignored -> {});
        String patch = Files.readString(root.resolve("out/f.patch"), StandardCharsets.UTF_8);
        require(patch.contains("-two\r\n+TWO\r\n"), "byte-exact CRLF unified diff from exit-1 differ: "
                + patch.replace("\r", "\\r").replace("\n", "\\n"));
    }

    private static void stdoutLandsInDeclaredOutput(Path root) throws Exception {
        TaskRunner.run(load(root, "version", "@m3:gitprobe:linter:text", List.of("git"),
                List.of("before"), List.of("out/version.txt")),
                root, "version", false, Duration.ofSeconds(60), ignored -> {});
        require(Files.readString(root.resolve("out/version.txt")).startsWith("git version"),
                "stdout redirected into the declared output");
    }

    /** A declared output folder that is a junction (Windows) or symlink out of the workspace: refused before the run. */
    private static void outputThroughLinkIsRefused(Path root) throws Exception {
        Path outside = Files.createTempDirectory("tool-kinds-outside-");
        Path link = root.resolve("linked");
        try {
            if (link(link, outside)) {
                linkedOutputsAreRefused(root, outside);
            }
        } finally {
            Files.deleteIfExists(link);
            try (var paths = Files.walk(outside)) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    private static void linkedOutputsAreRefused(Path root, Path outside) throws Exception {
        refused(() -> TaskRunner.run(load(root, "linkdiff", "@m3:gitprobe:differ:text", List.of("git", "before",
                "after", "linked/f.patch"), List.of("before", "after"), List.of("linked/f.patch")), root, "linkdiff",
                false, Duration.ofSeconds(60), ignored -> {}), "a differ output through a link");
        refused(() -> TaskRunner.run(load(root, "linkversion", "@m3:gitprobe:linter:text", List.of("git"),
                List.of("before"), List.of("linked/version.txt")), root, "linkversion", false, Duration.ofSeconds(60),
                ignored -> {}), "a stdout file through a link");
        try (var written = Files.list(outside)) {
            require(written.findAny().isEmpty(), "nothing written outside the workspace");
        }
    }

    /** A junction on Windows (no privilege needed; NOT RUN printed when mklink fails), a symbolic link elsewhere. */
    private static boolean link(Path link, Path target) throws Exception {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            Files.createSymbolicLink(link, target);
            return true;
        }
        Process mklink = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        mklink.getInputStream().readAllBytes();
        boolean made = mklink.waitFor() == 0 && Files.isDirectory(link);
        if (!made) System.out.println("NOT RUN: output through a junction (cannot create one here)");
        return made;
    }

    @FunctionalInterface private interface Run { void run() throws Exception; }

    private static void refused(Run run, String label) throws Exception {
        try {
            run.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("must refuse " + label);
    }

    private static TaskManifest load(Path root, String name, String tool, List<String> args,
            List<String> inputs, List<String> outputs) throws IOException {
        StringBuilder text = new StringBuilder(TaskManifest.MAGIC).append('\n');
        text.append("task\t").append(name).append('\t').append(tool).append("\t.\n");
        args.forEach(value -> text.append("arg\t").append(name).append('\t').append(value).append('\n'));
        inputs.forEach(value -> text.append("input\t").append(name).append('\t').append(value).append('\n'));
        outputs.forEach(value -> text.append("output\t").append(name).append('\t').append(value).append('\n'));
        Path file = root.resolve(name + ".tsv");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return TaskManifest.load(file);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
