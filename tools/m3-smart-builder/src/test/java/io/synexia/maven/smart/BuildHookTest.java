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

/** Product command and protocol regression; the MCP fixture never runs a model. */
public final class BuildHookTest {
    private static int checks;
    private BuildHookTest() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--workflow")) {
            Path project = Path.of(args[1]);
            String pre = Files.readString(project.resolve(".reports/m3-smart/pre-build.json"));
            String post = Files.readString(project.resolve(".reports/m3-smart/post-build.json"));
            require(pre.contains("\"phase\":\"pre-build\""), "pre hook executed before clean");
            require(post.contains("\"phase\":\"post-build\""), "post hook executed after failure");
            require(post.contains("\"buildOutcome\":\"failure\""), "original build failure retained");
            require(hasCompilerFailure(post), "real Maven compiler failure classified with a positive count");
            require(post.contains("\"adviceStatus\":\"available\""), "MCP returned a completed advisory response");
            require(pre.contains("\"atomPatternStatus\":\"structurally-validated\"")
                    && post.contains("\"atomPatternStatus\":\"structurally-validated\""), "both action calls validate receipt coverage");
            require(post.contains("\"semanticProofAuthenticated\":false"), "synthetic receipts cannot claim semantic proof");
            System.out.println("WORKFLOW REPORT CHECKS PASS: " + checks);
            return;
        }
        Path root = Files.createTempDirectory("build-hooks-");
        Path project = Files.createDirectories(root.resolve("project with spaces"));
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>test</groupId><artifactId>hooks</artifactId><version>1</version></project>");
        Path source = Files.createDirectories(project.resolve("src"));
        Files.writeString(source.resolve("Example.java"), "class Example { native void run(); }");
        Path log = project.resolve("build.log");
        Files.writeString(log, "[ERROR] cannot find symbol secret=DO_NOT_DISCLOSE\n");
        List<String> base = List.of("--project-dir", project.toString(), "--build-dir", ".hook-results");

        require(run(base, "--phase", "pre-build") == 0, "pre-build without provider");
        Map<String, Object> pre = report(project, "pre-build");
        require("disabled".equals(pre.get("adviceStatus")), "inference must be opt-in");
        require("not-run".equals(pre.get("buildOutcome")), "pre-build cannot assert a build result");
        require(Files.exists(project.resolve(".hook-results/m3-smart/pre-build.json")), "distinct pre report");

        require(run(base, "--phase", "post-build", "--build-outcome", "failure", "--log-file", "build.log",
                "--source-root", "src") == 0, "post hook runs after failed build");
        Map<String, Object> post = report(project, "post-build");
        require("failure".equals(post.get("buildOutcome")), "observed build failure must remain a failure");
        String text = Files.readString(project.resolve(".hook-results/m3-smart/post-build.json"));
        require(hasCompilerFailure(text), "reuse deterministic log classifier");
        require(!hasCompilerFailure("{\"logFacts\":{\"patterns\":{\"cannot-find-symbol\":0}}}"),
                "zero compiler errors cannot qualify the failed-compilation workflow");
        require(!hasCompilerFailure("{\"logFacts\":null}"), "missing log cannot qualify compilation failure");
        require(!text.contains("DO_NOT_DISCLOSE"), "raw logs must not reach report or prompt");
        require(Files.exists(project.resolve(".hook-results/m3-smart/pre-build.json")), "post preserves pre report");

        Files.writeString(project.resolve("pom.xml"), "<broken");
        require(run(base, "--phase", "post-build", "--build-outcome", "failure", "--log-file", "missing.log") == 0,
                "failed early build still produces a report");
        post = report(project, "post-build");
        require("unavailable".equals(post.get("pomStatus")), "malformed POM explicitly unavailable");
        require("unavailable".equals(post.get("logStatus")), "missing log explicitly unavailable");
        require(post.get("logFacts") == null, "missing logs are not zero-error evidence");
        require(run(base, "--phase", "post-build") == 2, "post requires observed build outcome");
        require(run(base, "--phase", "pre-build", "--build-outcome", "success") == 2,
                "pre rejects invented build result");
        require(run(base, "--phase", "pre-build", "--unknown", "value") == 2, "unknown options rejected");
        require(run(base, "--phase", "pre-build", "--phase", "pre-build") == 2, "duplicate options rejected");
        require(run(base, "--phase", "pre-build", "--backend", "none", "--allow-download", "true") == 2,
                "browser download flag no longer exists");
        require(run(base, "--phase", "pre-build", "--backend", "chrome") == 2, "chrome backend removed");
        require(run(base, "--phase", "pre-build", "--backend", "bridge") == 2, "bridge backend removed");
        require(run(base, "--phase", "pre-build", "--build-dir", root.resolve("outside").toString()) == 2,
                "output cannot escape project");
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>test</groupId><artifactId>hooks</artifactId><version>1</version></project>");


        // Source-bound preparation and the independent four-stage checkpoint gate compose.
        Path preparationFolder = Files.createDirectories(project.resolve(".reports"));
        Path preparationReceipt = preparationFolder.resolve("preparation.json");
        SourceInventory.Selected selectedSources = SourceInventory.inspectSelected(project, source);
        Files.writeString(preparationReceipt, CanonicalPreparationEvidenceTestMain.report(selectedSources));
        List<String> preparationArgs = new ArrayList<>(base);
        preparationArgs.addAll(List.of("--source-root", "src",
                "--preparation-required", "true", "--preparation-report", ".reports/preparation.json"));
        require(run(preparationArgs, "--phase", "pre-build") == 0,
                "source-bound preparation accepted with existing checkpoint mode disabled");
        Map<String, Object> acceptedPreparation = report(project, "pre-build");
        require("source-bound".equals(acceptedPreparation.get("preparationStatus")),
                "preparation evidence is explicitly source-bound");
        require("disabled".equals(acceptedPreparation.get("adviceStatus")),
                "preparation does not implicitly enable AI");
        require(run(preparationArgs, "--phase", "pre-build", "--require-atom-pattern", "true") == 2,
                "independent four-stage checkpoint requirement remains enforced");
        Map<String, Object> missingCheckpoint = report(project, "pre-build");
        require("blocked-evidence".equals(missingCheckpoint.get("adviceStatus")),
                "preparation alone cannot override missing checkpoint evidence");
        Files.writeString(source.resolve("Example.java"),
                "class Example { native void run(); } // intervening drift");
        List<String> rejectedProvider = new ArrayList<>(preparationArgs);
        rejectedProvider.addAll(List.of("--backend", "cli",
                "--chrome-llm-jar", "unavailable-agent.jar",
                "--llama-bin", "unavailable-llama", "--model", ".nonexistent-model.gguf"));
        require(run(rejectedProvider, "--phase", "pre-build") == 2,
                "stale preparation refuses before any provider construction");
        Map<String, Object> blockedPreparation = report(project, "pre-build");
        require("blocked-preparation".equals(blockedPreparation.get("adviceStatus"))
                && "CanonicalPreparationEvidenceRejected".equals(blockedPreparation.get("adviceErrorType")),
                "preparation refusal cannot become a provider failure");
        Files.writeString(source.resolve("Example.java"),
                "class Example { native void run(); }");
        require(run(preparationArgs, "--phase", "pre-build") == 0,
                "unchanged source bytes restore source-bound preparation admission");

        // A positive dual-admission test must use both real parsers, not assume
        // that a missing-checkpoint refusal proves the combined accepted path.
        AtomPatternEvidenceTest.writeActionFixture(project, Path.of("src"));
        List<String> bothEvidence = new ArrayList<>(preparationArgs);
        bothEvidence.addAll(List.of("--require-atom-pattern", "true",
                "--atom-pattern-manifest", "synthetic-checkpoints.tsv"));
        require(run(bothEvidence, "--phase", "pre-build") == 0,
                "valid independent preparation and checkpoint reports both admit");
        Map<String, Object> bothAccepted = report(project, "pre-build");
        require("source-bound".equals(bothAccepted.get("preparationStatus"))
                && "structurally-validated".equals(bothAccepted.get("atomPatternStatus")),
                "both checks report their separate source-binding outcomes");
        require("disabled".equals(bothAccepted.get("adviceStatus")),
                "dual admission does not automatically authorize a model");
        @SuppressWarnings("unchecked")
        Map<String, Object> checkpointFacts = (Map<String, Object>) bothAccepted.get("atomPatternFacts");
        require(Boolean.FALSE.equals(checkpointFacts.get("semanticProofAuthenticated")),
                "both structural receipts remain unauthenticated semantic evidence");
        String validManifest = Files.readString(project.resolve("synthetic-checkpoints.tsv"));
        Files.writeString(project.resolve("synthetic-checkpoints.tsv"),
                validManifest.replaceFirst("synthetic-checkpoints/source-0.tsv",
                        "synthetic-checkpoints/missing.tsv"));
        require(run(bothEvidence, "--phase", "pre-build") == 2,
                "bad checkpoint blocks admission despite valid canonical preparation");
        Map<String, Object> oneRejected = report(project, "pre-build");
        require("blocked-evidence".equals(oneRejected.get("adviceStatus"))
                && "source-bound".equals(oneRejected.get("preparationStatus")),
                "the other preparation gate stays accepted when checkpoints fail");
        Files.writeString(project.resolve("synthetic-checkpoints.tsv"), validManifest);
        require(run(bothEvidence, "--phase", "pre-build") == 0,
                "restoring the checkpoint returns to dual accepted admission");

        require(run(base, "--phase", "pre-build",
                "--preparation-report", ".reports/preparation.json") == 2,
                "preparation report alone cannot implicitly enable the gated mode");

        // A CPU summarizer of the operator's choice: its lines join the facts; a failure is recorded, not raised.
        require(run(base, "--phase", "pre-build", "--summarizer-timeout-seconds", "5") == 2,
                "summarizer timeout requires a summarizer");
        Path summarizerSource = Files.writeString(root.resolve("Summ.java"),
                "public class Summ { public static void main(String[] a) { if (a.length > 0 && a[0].equals(\"--fail\")) System.exit(4);"
                        + " System.out.println(\"summary of \" + a.length + \" inputs\");"
                        + " for (String s : a) System.out.println(\"input \" + java.nio.file.Path.of(s).getFileName());"
                        + " System.out.println(\"  \"); } }");
        String summarizer = "\"" + Path.of(System.getProperty("java.home"), "bin", "java") + "\" \"" + summarizerSource + "\"";
        require(run(base, "--phase", "pre-build") == 0, "no summarizer");
        require("not-requested".equals(report(project, "pre-build").get("summaryStatus")), "summarizer is opt-in");
        require(run(base, "--phase", "pre-build", "--summarizer", summarizer) == 0, "summarizer before the build");
        Map<String, Object> summarized = report(project, "pre-build");
        require("available".equals(summarized.get("summaryStatus")), "summarizer completed");
        require(List.of("summary of 1 inputs", "input pom.xml").equals(summarized.get("summaryLines")),
                "pre-build hands the pom; blank lines are not facts");
        require(run(base, "--phase", "post-build", "--build-outcome", "failure", "--log-file", "build.log",
                "--summarizer", summarizer, "--summarizer-timeout-seconds", "60") == 0, "summarizer after the build");
        require(List.of("summary of 2 inputs", "input pom.xml", "input build.log")
                .equals(report(project, "post-build").get("summaryLines")), "post-build hands the pom and the log");
        require(run(base, "--phase", "post-build", "--build-outcome", "success", "--summarizer", summarizer + " --fail") == 0,
                "a failing summarizer does not fail the hook");
        require("failed: exit 4".equals(report(project, "post-build").get("summaryStatus")), "summarizer exit recorded");
        require(run(base, "--phase", "pre-build", "--summarizer", "\"" + root.resolve("no summarizer here") + "\"") == 0,
                "a summarizer that cannot start does not fail the hook");
        require("failed: IOException".equals(report(project, "pre-build").get("summaryStatus")), "start failure recorded");
        require(List.of("a b", "c", "", "d'e").equals(BuildHookMain.commandLine(" \"a b\"  c '' \"d'e\" ")),
                "quotes group arguments and are removed");

        String fixture = System.getProperty("m3.smart.fixtureJar");
        if (fixture != null) {
            Path module = Path.of(fixture);
            Path executable = Files.writeString(root.resolve("llama executable"), "fixture only");
            Path valid = Files.writeString(root.resolve("mcp-valid.gguf"), "fixture model");
            McpAdviser.Config cli = new McpAdviser.Config(module, "cli", executable, valid, 7);
            String advice = McpAdviser.propose(cli, "fixture cli argument check");
            require(advice.contains("backend=cli"), "cli selected explicitly");
            require(advice.contains("timeout=7"), "configured timeout reaches inference");
            McpAdviser.Config nativeConfig = new McpAdviser.Config(module, "jni", executable, executable, 7);
            require(McpAdviser.propose(nativeConfig, "legacy").contains("fake MCP advisory"), "legacy native API preserved");

            List<String> live = new ArrayList<>(base);
            live.addAll(List.of("--backend", "cli", "--chrome-llm-jar", module.toString(),
                    "--llama-bin", executable.toString(), "--model", valid.toString(), "--timeout-seconds", "7"));
            require(run(live, "--phase", "post-build", "--build-outcome", "failure", "--log-file", "build.log") == 0,
                    "actual subprocess boundary completes post-build report");
            post = report(project, "post-build");
            require("available".equals(post.get("adviceStatus")), "completed fixture advice recorded");
            require("failure".equals(post.get("buildOutcome")), "advice cannot clear Maven failure");
            require(Boolean.FALSE.equals(post.get("llmCandidateTrusted")), "advice remains untrusted");

            Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                    + "<groupId>test</groupId><artifactId>fixture-provider-error</artifactId><version>1</version></project>");
            require(run(live, "--phase", "post-build", "--build-outcome", "failure", "--log-file", "build.log") == 2,
                    "provider refusal is visible");
            post = report(project, "post-build");
            require("failed".equals(post.get("adviceStatus")), "failed advice still leaves diagnostic facts");
            require("failure".equals(post.get("buildOutcome")), "provider failure cannot clear Maven failure");
            require("".equals(post.get("llmCandidate")), "failed reply must not become candidate text");
            expectFailure(() -> McpAdviser.propose(cli, "fixture wrong reply id"));
            expectFailure(() -> McpAdviser.propose(cli, "fixture invalid content"));
            Path badHello = Files.writeString(root.resolve("mcp-init-wrong-id.gguf"), "fixture model");
            expectFailure(() -> McpAdviser.propose(new McpAdviser.Config(module, "cli", executable, badHello, 7),
                    "hello validation"));
            long start = System.nanoTime();
            expectFailure(() -> McpAdviser.propose(new McpAdviser.Config(module, "cli", executable, valid, 1),
                    "fixture timeout"));
            require(java.time.Duration.ofNanos(System.nanoTime() - start).toSeconds() < 8,
                    "deadline must retire the waiting provider process");
            checkBlockedWriter(module, executable, Files.writeString(root.resolve("mcp-blocked-writer.gguf"), "fixture model"));
        }
        System.out.println("BUILD HOOK TESTS PASS: " + checks);
    }

    private static void checkBlockedWriter(Path module, Path executable, Path model) throws Exception {
        long start = System.nanoTime();
        Path marker = Path.of(model + ".blocked-writer");
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread caller = new Thread(() -> {
            try {
                // Maximum admitted UTF-8 input; JSON escaping also fills the provider's input pipe.
                McpAdviser.propose(new McpAdviser.Config(module, "cli", executable, model, 1), "\"".repeat(65_536));
            } catch (Throwable observed) { failure.set(observed); }
        }, "build-hook-blocked-writer-probe");
        caller.setDaemon(true);
        boolean writerObserved = false;
        caller.start();
        try {
            long observeUntil = start + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (caller.isAlive() && System.nanoTime() < observeUntil) {
                if (Files.exists(marker)) {
                    for (var entry : Thread.getAllStackTraces().entrySet()) {
                        if (!entry.getKey().getName().equals("maven-native-advisory-mcp")) continue;
                        for (StackTraceElement frame : entry.getValue()) {
                            if (frame.getClassName().equals("java.io.FileOutputStream")
                                    && frame.getMethodName().equals("writeBytes")) writerObserved = true;
                        }
                    }
                }
                if (writerObserved) break;
                Thread.sleep(5);
            }
            caller.join(5_000);
            require(Files.exists(marker), "blocked fixture must complete initialize before refusing input");
            require(writerObserved, "provider worker must be observed writing, not only waiting for a response");
            require(!caller.isAlive(), "deadline must finish the blocked caller");
            require(failure.get() instanceof java.io.IOException
                    && "MCP advisory deadline reached".equals(failure.get().getMessage()),
                    "blocked writer must fail at the MCP deadline, not prompt admission");
            long pid = Long.parseLong(Files.readString(marker));
            require(ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true),
                    "blocked fixture process must be retired");
            require(java.time.Duration.ofNanos(System.nanoTime() - start).toSeconds() < 8,
                    "deadline must retire a provider that stops reading a full input pipe");
        } finally {
            if (caller.isAlive()) { caller.interrupt(); caller.join(1_000); }
        }
    }

    private static int run(List<String> base, String... extra) {
        List<String> args = new ArrayList<>(base);
        args.addAll(List.of(extra));
        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(messages, true, StandardCharsets.UTF_8)) {
            return BuildHookMain.run(args.toArray(String[]::new), out, out);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> report(Path project, String phase) throws Exception {
        return (Map<String, Object>) TinyJson.parse(Files.readString(project.resolve(".hook-results/m3-smart/" + phase + ".json")));
    }

    private static boolean hasCompilerFailure(String report) {
        if (!(TinyJson.parse(report) instanceof Map<?, ?> record)
                || !(record.get("logFacts") instanceof Map<?, ?> facts)
                || !(facts.get("patterns") instanceof Map<?, ?> patterns)) return false;
        // TinyJson retains numeric lexemes as strings, as it does for MCP request IDs.
        Object value = patterns.get("cannot-find-symbol");
        return value instanceof String count && count.matches("[1-9][0-9]*");
    }

    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void expectFailure(Checked operation) throws Exception {
        checks++;
        try { operation.run(); }
        catch (java.io.IOException expected) { return; }
        throw new AssertionError("invalid MCP protocol was accepted");
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
