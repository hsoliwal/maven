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

/** Dependency-free tests; real CodeQL/Rewrite executables remain separately installed. */
public final class QualityProviderContractTestMain {
    private static int passed;

    private QualityProviderContractTestMain() {}

    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("m3-quality-contract-");
        try {
            Files.writeString(root.resolve("pom.xml"), "<project/>\n", StandardCharsets.UTF_8);
            createsCodeqlDatabaseWithExplicitMode(root);
            analyzesWithStableSarif(root);
            inventoriesAndDecodesSemanticEvidence(root);
            validatesOpenRewriteModesAndPins(root);
            rejectsMalformedOrEscapingProviderPaths(root);
            graphIncludesProviderChecksBeforeToolExecution(root);
            System.out.println("PASS: " + passed + " CodeQL/OpenRewrite provider cases");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void createsCodeqlDatabaseWithExplicitMode(Path root) throws Exception {
        var task = task("ql-create", "@codeql", "create", "codeql", "java-kotlin", "db/java", "none");
        var cmd = QualityTaskCommands.command(task, root, false);
        check(cmd.equals(List.of("codeql", "database", "create", root.resolve("db/java").toString(),
                "--source-root=" + root, "--language=java-kotlin", "--build-mode=none")), "CodeQL create argv");
        expectFailure(() -> QualityTaskCommands.command(
                task("invalid", "@codeql", "create", "codeql", "java-kotlin", "db/java", "manual"), root, false));
        passed++;
    }

    private static void analyzesWithStableSarif(Path root) throws Exception {
        var task = task("ql-analyze", "@codeql", "analyze", "codeql", "db/java", "codeql/java-queries", "reports/ql.sarif");
        var cmd = QualityTaskCommands.command(task, root, false);
        check(cmd.equals(List.of("codeql", "database", "analyze", root.resolve("db/java").toString(),
                "codeql/java-queries", "--format=sarifv2.1.0",
                "--output=" + root.resolve("reports/ql.sarif"))), "CodeQL SARIF argv");
        expectFailure(() -> QualityTaskCommands.command(
                task("wrong", "@codeql", "analyze", "codeql", "db/java", "--malicious", "reports/ql.sarif"),
                root, false));
        passed++;
    }

    private static void inventoriesAndDecodesSemanticEvidence(Path root) throws Exception {
        var query = task("ql-query", "@codeql", "query", "codeql", "db/java",
                "m3-codeql/ql/src/inventory/CallableInventory.ql", "reports/callables.bqrs");
        var planned = QualityTaskCommands.command(query, root, false);
        check(planned.equals(List.of("codeql", "query", "run",
                root.resolve("m3-codeql/ql/src/inventory/CallableInventory.ql").toString(),
                "--database=" + root.resolve("db/java"),
                "--output=" + root.resolve("reports/callables.bqrs"))), "CodeQL inventory query argv");
        var decode = task("ql-decode", "@codeql", "decode", "codeql", "reports/callables.bqrs",
                "csv", "reports/callables.csv");
        check(QualityTaskCommands.command(decode, root, false).equals(List.of(
                "codeql", "bqrs", "decode", root.resolve("reports/callables.bqrs").toString(),
                "--format=csv", "--output=" + root.resolve("reports/callables.csv"))),
                "CodeQL CSV decoding argv");
        expectFailure(() -> QualityTaskCommands.command(
                task("wrong", "@codeql", "decode", "codeql", "reports/inventory.bqrs",
                        "json", "reports/inventory.csv"), root, false));
        passed++;
    }

    private static void validatesOpenRewriteModesAndPins(Path root) throws Exception {
        var task = task("recipe-check", "@rewrite", "check", "mvn", "pom.xml", "6.61.0-SNAPSHOT",
                "org.openrewrite.java.format.AutoFormat", "org.example:recipes:1.0.0");
        var cmd = QualityTaskCommands.command(task, root, false);
        check(cmd.get(5).equals("org.openrewrite.maven:rewrite-maven-plugin:6.61.0-SNAPSHOT:dryRunNoFork"),
                "Rewrite must use original pinned plugin");
        check(cmd.contains("-Drewrite.failOnDryRunResults=true"), "check fails on pending changes");
        check(cmd.contains("-Drewrite.recipeArtifactCoordinates=org.example:recipes:1.0.0"),
                "explicit recipe coordinates");
        var preview = QualityTaskCommands.command(
                task("preview", "@rewrite", "preview", "mvn", "pom.xml", "6.61.0-SNAPSHOT",
                        "org.openrewrite.java.format.AutoFormat"), root, false);
        check(!preview.contains("-Drewrite.failOnDryRunResults=true"), "preview remains observational");
        var apply = task("apply", "@rewrite", "apply", "mvn", "pom.xml", "6.61.0-SNAPSHOT",
                "org.openrewrite.java.format.AutoFormat");
        expectFailure(() -> QualityTaskCommands.command(apply, root, false));
        check(QualityTaskCommands.command(apply, root, true).get(5).endsWith(":runNoFork"),
                "apply guarded and delegated");
        expectFailure(() -> QualityTaskCommands.command(
                task("unpinned", "@rewrite", "check", "mvn", "pom.xml", "LATEST", "org.example.Recipe"),
                root, false));
        passed++;
    }

    private static void rejectsMalformedOrEscapingProviderPaths(Path root) throws Exception {
        expectFailure(() -> QualityTaskCommands.command(
                task("escape", "@codeql", "create", "codeql", "python", "../outside", "none"), root, false));
        expectFailure(() -> QualityTaskCommands.command(
                task("escape", "@rewrite", "check", "mvn", "../pom.xml", "6.61.0", "org.example.Recipe"),
                root, false));
        Path link = root.resolve("symlink");
        try {
            Files.createSymbolicLink(link, root.getParent());
            expectFailure(() -> QualityTaskCommands.command(
                    task("link", "@codeql", "create", "codeql", "python", "symlink/db", "none"), root, false));
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            // Symlink creation is unavailable on some Windows or sandbox hosts.
        }
        passed++;
    }

    private static void graphIncludesProviderChecksBeforeToolExecution(Path root) throws Exception {
        Path manifest = root.resolve("quality.tsv");
        Files.writeString(manifest, TaskManifest.MAGIC + "\n"
                + "task\tall\t-\t.\n"
                + "dep\tall\tsecurity\n"
                + "task\tsecurity\t@codeql\t.\n"
                + "arg\tsecurity\tanalyze\n"
                + "arg\tsecurity\tcodeql\n"
                + "arg\tsecurity\tdb/python\n"
                + "arg\tsecurity\tcodeql/python-queries\n"
                + "arg\tsecurity\treports/python.sarif\n", StandardCharsets.UTF_8);
        var events = new ArrayList<String>();
        var ordered = TaskRunner.run(TaskManifest.load(manifest), root, "all", true,
                Duration.ofSeconds(5), events::add);
        check(ordered.equals(List.of("security", "all")), "provider task scheduled as a graph predecessor");
        check(events.stream().allMatch(x -> x.startsWith("PLAN")), "read-only planning");
        passed++;
    }

    private static TaskManifest.Task task(String name, String tool, String... args) {
        return new TaskManifest.Task(name, tool, ".", List.of(args), List.of(), List.of(), List.of());
    }

    @FunctionalInterface
    private interface Throwing {
        void run() throws Exception;
    }

    private static void expectFailure(Throwing action) {
        try {
            action.run();
        } catch (IOException | IllegalArgumentException expected) {
            return;
        } catch (Exception unexpected) {
            throw new AssertionError("unexpected exception", unexpected);
        }
        throw new AssertionError("expected refusal");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
