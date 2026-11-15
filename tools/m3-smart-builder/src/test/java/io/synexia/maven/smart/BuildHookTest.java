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
                "download flag requires Chrome backend");
        require(run(base, "--phase", "pre-build", "--build-dir", root.resolve("outside").toString()) == 2,
                "output cannot escape project");
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>test</groupId><artifactId>hooks</artifactId><version>1</version></project>");

        String fixture = System.getProperty("m3.smart.fixtureJar");
        if (fixture != null) {
            Path module = Path.of(fixture);
            Path executable = Files.writeString(root.resolve("chrome executable"), "fixture only");
            Path profile = root.resolve("dedicated profile");
            McpAdviser.ChromeConfig chrome = new McpAdviser.ChromeConfig(module, executable, profile, true, true, 7);
            String advice = McpAdviser.propose(chrome, "fixture chrome argument check");
            require(advice.contains("backend=chrome"), "Chrome selected explicitly");
            require(advice.contains("headless=true download=true"), "explicit browser options retained");
            require(advice.contains("timeout=7"), "configured timeout reaches inference");
            require(!Files.exists(profile), "fixture does not prepare or invent a real model");
            McpAdviser.Config nativeConfig = new McpAdviser.Config(module, "jni", executable, executable, 7);
            require(McpAdviser.propose(nativeConfig, "legacy").contains("fake MCP advisory"), "legacy native API preserved");

            List<String> live = new ArrayList<>(base);
            live.addAll(List.of("--backend", "chrome", "--chrome-llm-jar", module.toString(),
                    "--chrome-binary", executable.toString(), "--chrome-profile", profile.toString(),
                    "--timeout-seconds", "7", "--headless", "true"));
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
            expectFailure(() -> McpAdviser.propose(chrome, "fixture wrong reply id"));
            expectFailure(() -> McpAdviser.propose(chrome, "fixture invalid content"));
            expectFailure(() -> McpAdviser.propose(new McpAdviser.ChromeConfig(module, executable,
                    profile.resolve("bad-hello"), true, false, 7), "hello validation"));
            long start = System.nanoTime();
            expectFailure(() -> McpAdviser.propose(new McpAdviser.ChromeConfig(module, executable,
                    profile, true, false, 1), "fixture timeout"));
            require(java.time.Duration.ofNanos(System.nanoTime() - start).toSeconds() < 8,
                    "deadline must retire the waiting provider process");
            start = System.nanoTime();
            expectFailure(() -> McpAdviser.propose(new McpAdviser.ChromeConfig(module, executable,
                    profile.resolve("blocked-writer"), true, false, 1), "x".repeat(262144)));
            require(java.time.Duration.ofNanos(System.nanoTime() - start).toSeconds() < 8,
                    "deadline must retire a provider that stops reading a full input pipe");
        }
        System.out.println("BUILD HOOK TESTS PASS: " + checks);
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
