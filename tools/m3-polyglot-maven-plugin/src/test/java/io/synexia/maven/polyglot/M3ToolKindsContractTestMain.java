/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Contract suite for the additive tool kinds and the default-method run contract. Providers are
 * supplied directly to the package-private registry, so the ServiceLoader inventory checked by
 * M3ToolProvidersContractTestMain is unchanged.
 */
public final class M3ToolKindsContractTestMain {
    private static int passed;

    private M3ToolKindsContractTestMain() {}

    /** Implements only the five abstract v1 methods, like every provider compiled before this change. */
    static class LegacyProvider implements M3ToolProvider {
        @Override public String id() { return "legacy"; }
        @Override public Set<M3ToolCapability> capabilities() {
            return Set.of(M3ToolCapability.DIFFER, M3ToolCapability.PATCHER, M3ToolCapability.LINTER);
        }
        @Override public Set<String> languages() { return Set.of("text", "java"); }
        @Override public List<String> command(M3ToolCapability role, String language,
                TaskManifest.Task task, Path workspace) {
            return List.of("tool", role.slug());
        }
    }

    /** Declares a source-writing effect, as an approved patch apply would. */
    static final class WritingProvider extends LegacyProvider {
        @Override public String id() { return "writer"; }
        @Override public M3ToolEffect effect(M3ToolCapability role, String language, TaskManifest.Task task) {
            return M3ToolEffect.WRITES_SOURCES;
        }
    }

    /** Declares contract values that must be refused. */
    static final class BrokenProvider extends LegacyProvider {
        @Override public String id() { return "broken"; }
        @Override public Set<Integer> successExitCodes(M3ToolCapability role, String language, TaskManifest.Task task) {
            return role == M3ToolCapability.DIFFER ? Set.of(1) : Set.of(0);
        }
        @Override public Optional<String> stdoutOutput(M3ToolCapability role, String language, TaskManifest.Task task) {
            return role == M3ToolCapability.LINTER ? Optional.of("elsewhere.txt") : Optional.empty();
        }
        @Override public List<M3Report> reports(M3ToolCapability role, String language, TaskManifest.Task task) {
            return role == M3ToolCapability.PATCHER
                    ? List.of(new M3Report("undeclared/PLAN.tsv", M3ReportFormat.PLAN_TSV)) : List.of();
        }
    }

    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("m3-tool-kinds-");
        Map<String, M3ToolProvider> registry = M3ToolProviders.registry(
                List.of(new LegacyProvider(), new WritingProvider(), new BrokenProvider()));
        capabilitiesStayAdditive();
        legacyProviderGetsDefaults(root, registry);
        writesSourcesNeedApproval(root, registry);
        refusesInvalidContracts(root, registry);
        newKindsKeepOverlapGate(root, registry);
        System.out.println("PASS: " + passed + " M3 tool-kind contract cases");
        Files.delete(root);
    }

    private static void capabilitiesStayAdditive() throws Exception {
        List<String> slugs = Arrays.stream(M3ToolCapability.values()).map(M3ToolCapability::slug).toList();
        require(slugs.subList(0, 5).equals(List.of("compiler", "interpreter", "transpiler", "atomizer",
                "patternizer")), "v1 constants keep their order and slugs");
        for (String slug : List.of("linter", "static-analyzer", "formatter", "differ", "patcher",
                "refactorer", "test-runner", "normalizer")) {
            require(M3ToolCapability.parse(slug).slug().equals(slug), "new kind parses: " + slug);
        }
        refuses(() -> M3ToolCapability.parse("Differ"), "case-sensitive slug");
        passed++;
    }

    private static void legacyProviderGetsDefaults(Path root, Map<String, M3ToolProvider> registry) throws Exception {
        M3ToolProviders.Planned planned = M3ToolProviders.plan(task("@m3:legacy:differ:text",
                List.of("a.txt"), List.of("out/a.patch")), root, false, registry);
        require(planned.argv().equals(List.of("tool", "differ")), "argv unchanged");
        require(planned.effect() == M3ToolEffect.READS_SOURCES, "default effect reads sources");
        require(planned.successExitCodes().equals(Set.of(0)), "default success exit code 0");
        require(planned.stdout().isEmpty() && planned.reports().isEmpty(), "no stdout file, no reports");
        require(new LegacyProvider().astStatus(M3ToolCapability.DIFFER, "text") == M3AstStatus.SEPARATE_PARSE,
                "undeclared one-AST status is conservative");
        require(new JdkCompilerProvider().astStatus(M3ToolCapability.COMPILER, "java") == M3AstStatus.COMPILER,
                "the JDK compiler owns the AST");
        passed++;
    }

    private static void writesSourcesNeedApproval(Path root, Map<String, M3ToolProvider> registry) throws Exception {
        TaskManifest.Task apply = task("@m3:writer:patcher:text",
                List.of("fix.patch"), List.of("target/patch/receipts/RECEIPT.tsv"));
        refuses(() -> M3ToolProviders.plan(apply, root, false, registry), "unapproved source write");
        require(M3ToolProviders.plan(apply, root, true, registry).effect() == M3ToolEffect.WRITES_SOURCES,
                "approved source write is planned");
        passed++;
    }

    private static void refusesInvalidContracts(Path root, Map<String, M3ToolProvider> registry) throws Exception {
        refuses(() -> M3ToolProviders.plan(task("@m3:broken:differ:text", List.of("a.txt"),
                List.of("out/a.patch")), root, false, registry), "success codes without 0");
        refuses(() -> M3ToolProviders.plan(task("@m3:broken:linter:java", List.of("A.java"),
                List.of("out/lint.sarif")), root, false, registry), "stdout file not declared");
        refuses(() -> M3ToolProviders.plan(task("@m3:broken:patcher:text", List.of("fix.patch"),
                List.of("out/PLAN.tsv")), root, false, registry), "report not declared");
        passed += 3;
    }

    private static void newKindsKeepOverlapGate(Path root, Map<String, M3ToolProvider> registry) throws Exception {
        refuses(() -> M3ToolProviders.plan(task("@m3:legacy:differ:text", List.of("src"),
                List.of("src/diff.patch")), root, false, registry), "differ output inside its input");
        refuses(() -> M3ToolProviders.plan(task("@m3:legacy:formatter:java", List.of("src"),
                List.of("out/f.patch")), root, false, registry), "capability the provider lacks");
        passed++;
    }

    private static TaskManifest.Task task(String tool, List<String> inputs, List<String> outputs) {
        return new TaskManifest.Task("t", tool, ".", List.of(), List.of(), inputs, outputs);
    }

    @FunctionalInterface private interface Attempt { void run() throws Exception; }

    private static void refuses(Attempt attempt, String label) throws Exception {
        try {
            attempt.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("must refuse " + label);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
