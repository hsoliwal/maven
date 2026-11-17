// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Synthetic receipts exercise nomination and fail-closed replay, never model or semantic proof. */
public final class CodeAgentTest {
    private static int checks;
    private CodeAgentTest() { }

    public static void main(String[] args) throws Exception {
        Path project = Files.createTempDirectory("m3-code-agent-");
        Path source = Files.createDirectory(project.resolve("src"));
        String originalSource = "class Example { int sum(int[] a) { int total=0; for (int x:a) total+=x; return total; } } // PRIVATE_SOURCE_MARKER\n";
        Path java = Files.writeString(source.resolve("Example.java"), originalSource);
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>\n");
        AtomPatternEvidenceTest.writeActionFixture(project, Path.of("src"));
        SourceInventory.Selected selected = SourceInventory.inspectSelected(project, source);
        Path receipts = Files.createDirectory(project.resolve("receipts"));
        Path preparation = receipts.resolve("preparation.json");
        String pattern = "{\"ownerKind\":\"METHOD\",\"owner\":\"Example#sum\","
                + "\"pattern\":\"ITERATION\",\"exactSha256\":\"" + "a".repeat(64) + "\","
                + "\"structuralSha256\":\"" + "b".repeat(64) + "\",\"authority\":\"CANDIDATE_ONLY\"}";
        String prep = CanonicalPreparationEvidenceTestMain.report(selected)
                .replace("\"patterns\":[]", "\"patterns\":[" + pattern + "]")
                .replace("\"patternRowCount\":0", "\"patternRowCount\":1")
                .replace("\"patternCounts\":{}", "\"patternCounts\":{\"ITERATION\":1}")
                .replace("\"executedFiles\":1,\"rows\":0", "\"executedFiles\":1,\"rows\":1");
        Files.writeString(preparation, prep);
        Path catalog = project.resolve("approved-recipes.tsv");
        String entries = RecipeCatalog.HEADER + "\n"
                + "branch-cleanup\tBRANCHING\tFILE\tcom.synexia.rewrite.BranchCleanup\t" + "a".repeat(64) + "\n"
                + "iteration-loop\tITERATION\tFILE\tcom.synexia.rewrite.IterationLoop\t" + "b".repeat(64) + "\n";
        Files.writeString(catalog, entries);
        List<String> options = new ArrayList<>(List.of("--project-dir", project.toString(),
                "--build-dir", "target", "--source-root", "src",
                "--preparation-report", "receipts/preparation.json",
                "--atom-pattern-manifest", "synthetic-checkpoints.tsv",
                "--recipe-catalog", "approved-recipes.tsv"));

        refused(plus(options, "--require-machine-evidence", "true"), project,
                "strict atom-pattern policy refuses a missing machine fingerprint ledger");
        refused(plus(options, "--require-machine-evidence", "invalid"), project,
                "strict atom-pattern option must be a literal boolean");
        require(run(options) == 0, "deterministic catalog examination without a model");
        Map<?, ?> report = result(project);
        require("awaiting-review".equals(report.get("status")), "no AI doesn't invent a choice");
        require(((List<?>) report.get("applicableRecipes")).size() == 1, "only observed patterns yield recipes");
        require(report.get("selectedRecipe") == null && Boolean.FALSE.equals(report.get("recipeExecuted")),
                "catalog entries have no execution authority");
        require(!Files.readString(project.resolve("target/m3-smart/code-agent.json")).contains("PRIVATE_SOURCE_MARKER"),
                "raw source excluded from the report");
        String binding = (String) report.get("evidenceBindingSha256");
        Path candidate = project.resolve("candidate.json");
        String nomination = "{\"schema\":\"m3.smart.code-agent.candidate.v1\",\"evidenceSha256\":\""
                + binding + "\",\"recipeId\":\"iteration-loop\",\"confidence\":\"MEDIUM\"}";
        Files.writeString(candidate, nomination);
        List<String> selectedOptions = plus(options, "--candidate-file", "candidate.json");
        require(run(selectedOptions) == 0, "only a matching hash-bound, catalogued nomination is admitted");
        report = result(project);
        require("proposed".equals(report.get("status")) && "MEDIUM".equals(report.get("modelConfidence")),
                "model confidence stays only a disclosed claim");
        require("iteration-loop".equals(object(report.get("selectedRecipe")).get("recipeId")), "recipe matched allowlist");
        require(Boolean.FALSE.equals(report.get("recipeApproved"))
                        && Boolean.FALSE.equals(report.get("verificationAuthenticated")), "no automatic trust or proof");
        String original = Files.readString(project.resolve("target/m3-smart/code-agent.json"));
        require(run(selectedOptions) == 0 && original.equals(Files.readString(project.resolve("target/m3-smart/code-agent.json"))),
                "byte-identical replay is deterministic");

        Files.writeString(candidate, nomination.replace("iteration-loop", "arbitrary-shell"));
        refused(selectedOptions, project, "model cannot invent a recipe");
        Files.writeString(candidate, nomination.replace(binding, "f".repeat(64)));
        refused(selectedOptions, project, "stale nomination cannot be replayed");
        Files.writeString(candidate, nomination.replace("\"confidence\"", "\"shellCommand\""));
        refused(selectedOptions, project, "unrecognized model fields refused");
        Files.writeString(candidate, nomination);
        Files.writeString(java, Files.readString(java) + "// changed\n");
        refused(selectedOptions, project, "changed source requires new prepared evidence");
        Files.writeString(java, originalSource);
        require(run(selectedOptions) == 0, "replayed evidence recovers after exact source restored");
        Files.writeString(catalog, entries.replace("b".repeat(64), "c".repeat(64)));
        refused(selectedOptions, project, "changing a recipe implementation digest invalidates nomination");
        Files.writeString(catalog, entries.replace("branch-cleanup", "zzz-branch"));
        refused(options, project, "unsorted catalog rejected");
        Files.writeString(catalog, entries);
        Path other = Files.createTempFile("external-candidate-", ".json");
        Files.writeString(other, nomination);
        refused(plus(options, "--candidate-file", other.toString()), project,
                "model candidate cannot be read outside the checkout");
        Path link = project.resolve("linked-catalog.tsv");
        Files.createSymbolicLink(link, catalog);
        refused(plus(withoutCatalog(options), "--recipe-catalog", "linked-catalog.tsv"), project,
                "symlinked catalog rejected");
        require(run(options) == 0, "offline mode still works after failures");
        String fixtureJar = System.getProperty("m3.smart.fixtureJar");
        if (fixtureJar != null) {
            Path executable = Files.writeString(project.resolve("synthetic-chrome-bin"), "not actually Chrome");
            Path profile = project.resolve("evidence-observer");
            List<String> chrome = new ArrayList<>(options);
            chrome.addAll(List.of("--backend", "chrome", "--chrome-llm-jar", fixtureJar,
                    "--chrome-binary", executable.toString(), "--chrome-profile", profile.toString(),
                    "--timeout-seconds", "7"));
            refused(chrome, project, "synthetic MCP prose cannot masquerade as a structured recipe nomination");
            require(Files.exists(Path.of(profile + ".invoked")), "existing ChromeLLM MCP provider launched");
            String request = Files.readString(Path.of(profile + ".request"));
            require(request.contains("m3.smart.code-agent.candidate.v1") && request.contains(binding),
                    "ChromeLLM receives bound recipe schema and evidence");
            require(request.contains("Machine-Oriented Programming") && request.contains("SimHash")
                            && request.contains("atom-leaf") && request.contains("pattern-order")
                            && request.contains("logic-hash") && request.contains("EXTERNAL_GATE"),
                    "ChromeLLM receives the versioned M3 rule packet with explicit authority distinctions");
            require(!request.contains("PRIVATE_SOURCE_MARKER"), "model prompt excludes raw source content");
        }
        System.out.println("CODE AGENT TESTS PASS: " + checks + " (synthetic evidence only)");
    }

    private static List<String> withoutCatalog(List<String> options) {
        List<String> result = new ArrayList<>(options);
        result.remove(result.indexOf("--recipe-catalog") + 1);
        result.remove("--recipe-catalog");
        return result;
    }

    private static List<String> plus(List<String> options, String key, String value) {
        List<String> added = new ArrayList<>(options);
        added.add(key); added.add(value);
        return added;
    }

    private static void refused(List<String> options, Path project, String explanation) throws Exception {
        require(run(options) == 2, explanation);
        require("refused".equals(result(project).get("status")), "old successful nomination replaced with refusal");
    }

    private static int run(List<String> arguments) {
        return CodeAgentMain.run(arguments.toArray(String[]::new),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
    }

    private static Map<?, ?> result(Path project) throws Exception {
        return object(TinyJson.parse(Files.readString(project.resolve("target/m3-smart/code-agent.json"),
                StandardCharsets.UTF_8)));
    }

    private static Map<?, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new AssertionError("JSON object expected");
        return map;
    }

    private static void require(boolean value, String explanation) {
        checks++;
        if (!value) throw new AssertionError(explanation);
    }
}
