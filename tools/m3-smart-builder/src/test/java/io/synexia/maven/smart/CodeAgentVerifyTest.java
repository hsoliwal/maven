// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A synthetic Maven executable checks orchestration; it is not a live Rewrite or model qualification. */
public final class CodeAgentVerifyTest {
    private static int checks;
    private CodeAgentVerifyTest() { }

    public static void main(String[] args) throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            System.out.println("CODE AGENT VERIFY TESTS SKIPPED: POSIX synthetic Maven executable unavailable on Windows");
            return;
        }
        Path project = Files.createTempDirectory("m3-code-verify-").toRealPath();
        Path source = Files.createDirectory(project.resolve("src"));
        String initial = "class Example { int sum(int[] a) { int n=0; for(int x:a)n+=x; return n; } }\n";
        Path file = Files.writeString(source.resolve("Example.java"), initial);
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>\n");
        AtomPatternEvidenceTest.writeActionFixture(project, Path.of("src"));
        SourceInventory.Selected selected = SourceInventory.inspectSelected(project, source);
        Path receipts = Files.createDirectory(project.resolve("receipts"));
        String pattern = "{\"ownerKind\":\"METHOD\",\"owner\":\"Example#sum\","
                + "\"pattern\":\"ITERATION\",\"exactSha256\":\"" + "a".repeat(64) + "\","
                + "\"structuralSha256\":\"" + "b".repeat(64) + "\",\"authority\":\"CANDIDATE_ONLY\"}";
        String prep = CanonicalPreparationEvidenceTestMain.report(selected)
                .replace("\"patterns\":[]", "\"patterns\":[" + pattern + "]")
                .replace("\"patternRowCount\":0", "\"patternRowCount\":1")
                .replace("\"patternCounts\":{}", "\"patternCounts\":{\"ITERATION\":1}")
                .replace("\"executedFiles\":1,\"rows\":0", "\"executedFiles\":1,\"rows\":1");
        Files.writeString(receipts.resolve("preparation.json"), prep);
        Path evidence = receipts.resolve("fingerprints.tsv");
        String ledger = MachineEvidence.HEADER + "\nExample.java\t" + Hashes.sha256(file)
                + "\tExample#sum\t" + "b".repeat(64) + "\t" + "c".repeat(64)
                + "\t0000000000000001\t" + "d".repeat(64) + "\n";
        Files.writeString(evidence, ledger);
        Path repository = Files.createDirectory(project.resolve("offline-repository"));
        Path recipeJar = repository.resolve("com/synexia/recipes/1.0.0/recipes-1.0.0.jar");
        Path pluginJar = repository.resolve("org/openrewrite/maven/rewrite-maven-plugin/6.66.0/rewrite-maven-plugin-6.66.0.jar");
        Files.createDirectories(recipeJar.getParent());
        Files.createDirectories(pluginJar.getParent());
        Files.writeString(recipeJar, "synthetic recipe JAR fixture");
        Files.writeString(pluginJar, "synthetic OpenRewrite plugin JAR fixture");
        Path catalog = project.resolve("recipes.tsv");
        Files.writeString(catalog, RecipeCatalog.HEADER + "\niteration-loop\tITERATION\tFILE\t"
                + "com.synexia.rewrite.IterationLoop\t" + Hashes.sha256(recipeJar) + "\n");
        List<String> nomination = new ArrayList<>(List.of("--project-dir", project.toString(),
                "--build-dir", "target", "--source-root", "src", "--preparation-report", "receipts/preparation.json",
                "--atom-pattern-manifest", "synthetic-checkpoints.tsv", "--recipe-catalog", "recipes.tsv",
                "--machine-evidence", "receipts/fingerprints.tsv"));
        require(runNomination(nomination) == 0, "machine-bound catalogue listed offline");
        Map<?, ?> report = nomination(project);
        require(MachineRulebook.load().sha256().equals(report.get("machineRulebookSha256")),
                "the nominal model contract records its exact packaged M3 rules");
        Map<?, ?> machine = object(report.get("machineEvidenceFacts"));
        require("m3.smart.machine-evidence.v1".equals(machine.get("schema")), "fingerprint ledger reaches nomination");
        require(Boolean.FALSE.equals(machine.get("similarityIsProof")), "SimHash never authenticates equivalence");
        require(Boolean.FALSE.equals(object(machine.get("staticCues")).get("available")),
                "legacy seven-column machine ledgers remain accepted without fabricated static cues");
        String binding = (String) report.get("evidenceBindingSha256");
        Files.writeString(project.resolve("candidate.json"), "{\"schema\":\"m3.smart.code-agent.candidate.v1\","
                + "\"evidenceSha256\":\"" + binding + "\",\"recipeId\":\"iteration-loop\",\"confidence\":\"HIGH\"}");
        require(runNomination(plus(nomination, "--candidate-file", "candidate.json")) == 0,
                "selected candidate bound to source and fingerprints");
        Path runner = project.resolve("fake-maven.sh");
        String fake = "#!/bin/sh\nset -eu\nmkdir -p target/m3-code-agent\n"
                + "printf '%s\\n' \"$*\" >> target/m3-code-agent/calls\n"
                + "case \"$*\" in\n"
                + "  *:dryRun) mkdir -p target/rewrite; printf 'synthetic patch\\n' > target/rewrite/rewrite.patch; exit 0 ;;\n"
                + "  *:run) if [ ! -f target/m3-code-agent/applied ]; then\n"
                + "    printf '//synthetic recipe edit\\n' >> src/Example.java\n"
                + "    : > target/m3-code-agent/applied\n"
                + "  fi ;;\n"
                + "  *' verify') exit 0 ;;\n"
                + "  *) exit 12 ;;\n"
                + "esac\n";
        Files.writeString(runner, fake);
        Files.setPosixFilePermissions(runner, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        List<String> command = new ArrayList<>(nomination);
        command.addAll(List.of("--approved-recipe-id", "iteration-loop", "--approve-execution", "false",
                "--maven-executable", runner.toString(), "--maven-repository", repository.toString(),
                "--recipe-coordinates", "com.synexia:recipes:1.0.0",
                "--plugin-sha256", Hashes.sha256(pluginJar), "--timeout-seconds", "10"));
        refused(command, project, "operator approval mandatory");
        command.set(command.indexOf("--approve-execution") + 1, "true");
        refused(replace(command, "--plugin-sha256", "0".repeat(64)), project,
                "plugin artifact pin mandatory");
        refused(replace(command, "--approved-recipe-id", "unknown-recipe"), project,
                "operator cannot approve unknown recipe");
        Path nominationFile = project.resolve("target/m3-smart/code-agent.json");
        String savedNomination = Files.readString(nominationFile);
        Files.writeString(nominationFile, savedNomination.replace(MachineRulebook.load().sha256(), "0".repeat(64)));
        refused(command, project, "forged or stale M3 rule digest invalidates the execution nomination");
        Files.writeString(nominationFile, savedNomination);
        Files.writeString(evidence, ledger.replace("0000000000000001", "0000000000000002"));
        refused(command, project, "changed SimHash invalidates candidate binding");
        Files.writeString(evidence, ledger);
        String second = "Example.java\t" + Hashes.sha256(file)
                + "\tExample#z\t" + "b".repeat(64) + "\t" + "c".repeat(64)
                + "\t0000000000000003\t" + "d".repeat(64) + "\n";
        Files.writeString(evidence, ledger + second);
        MachineEvidence.Summary duplicates = MachineEvidence.inspect(project, source, evidence);
        require(duplicates.atoms() == 2 && duplicates.structuralGroups() == 1
                && duplicates.logicGroups() == 1 && duplicates.similarPairs() == 1,
                "identical structural/logic fingerprints and nearby SimHashes remain advisory groups");
        Map<?, ?> grouped = object(TinyJson.parse(duplicates.json()));
        List<?> patterns = (List<?>) grouped.get("patternCandidates");
        require(patterns.size() == 1 && "LOGIC_EXACT".equals(object(patterns.getFirst()).get("kind"))
                && "REVIEW_ONLY".equals(object(patterns.getFirst()).get("authority")),
                "legacy ledgers now yield bounded, explicitly non-authoritative pattern pairs");
        String shaped = "Example.java\t" + Hashes.sha256(file) + "\tExample#y\t"
                + "b".repeat(64) + "\t" + "e".repeat(64) + "\t0000000000000002\t" + "d".repeat(64) + "\n";
        String nearOnly = "Example.java\t" + Hashes.sha256(file) + "\tExample#z\t"
                + "a".repeat(64) + "\t" + "f".repeat(64) + "\t0000000000000003\t" + "e".repeat(64) + "\n";
        Files.writeString(evidence, ledger + shaped + nearOnly);
        Map<?, ?> separated = object(TinyJson.parse(MachineEvidence.inspect(project, source, evidence).json()));
        List<?> ranked = (List<?>) separated.get("patternCandidates");
        require(ranked.size() == 3 && "STRUCTURE_EXACT".equals(object(ranked.getFirst()).get("kind"))
                && "SIMHASH_NEAR".equals(object(ranked.get(1)).get("kind"))
                && "SIMHASH_NEAR".equals(object(ranked.get(2)).get("kind")),
                "structure matches rank above near-only SimHash suggestions even with no logic match");
        Files.writeString(evidence, MachineEvidence.HEADER + "\n" + second + ledger.substring(ledger.indexOf('\n') + 1));
        require(rejectsMachine(project, source, evidence), "unsorted atom ledger rejected");
        Files.writeString(evidence, ledger.replace(Hashes.sha256(file), "0".repeat(64)));
        require(rejectsMachine(project, source, evidence), "source SHA mismatch rejected");
        Files.writeString(evidence, ledger);
        require(runVerify(command) == 0, "explicitly approved recipe stages and verifies");
        Map<?, ?> verified = verification(project);
        require("verified-for-review".equals(verified.get("status")), "verified stage is review-only");
        require("1".equals(verified.get("changedJavaFiles")), "a synthetic staged Java change observed");
        require(Boolean.TRUE.equals(verified.get("fixedPointPassed"))
                        && Boolean.TRUE.equals(verified.get("mavenVerifyPassed")), "Maven verify and repeat applied");
        require(MachineRulebook.load().sha256().equals(verified.get("machineRulebookSha256")),
                "execution receipt is bound to the same immutable rules the model saw");
        require(Boolean.FALSE.equals(verified.get("semanticProofAuthenticated"))
                        && Boolean.FALSE.equals(verified.get("promoted")), "offline runner cannot prove behavior or promote");
        require(verified.get("dryRunPatchSha256") instanceof String,
                "staged OpenRewrite dry run emits a content-addressed preview patch");
        require(initial.equals(Files.readString(file)), "original Java source remains unchanged");
        Path stage = project.resolve((String) verified.get("stageRelativePath"));
        require(Files.readString(stage.resolve("src/Example.java")).contains("synthetic recipe edit"),
                "stage contains the proposed transformation");
        String calls = Files.readString(stage.resolve("target/m3-code-agent/calls"));
        require(calls.contains("rewrite-maven-plugin:6.66.0:dryRun")
                        && calls.contains("rewrite-maven-plugin:6.66.0:run")
                        && calls.contains("-Drewrite.recipeArtifactCoordinates=com.synexia:recipes:1.0.0")
                        && calls.contains("-o -B -ntp") && calls.contains(" verify"),
                "only pinned offline Rewrite and Maven verification goals were invoked");
        Files.writeString(runner, fake.replace("*:dryRun) mkdir -p target/rewrite; printf 'synthetic patch\\n' > target/rewrite/rewrite.patch; exit 0 ;;",
                "*:dryRun) printf '//unexpected dry run mutation\\n' >> src/Example.java; exit 0 ;;"));
        refused(command, project, "a dry-run source mutation fails closed");
        Files.writeString(runner, fake.replace("*:dryRun) mkdir -p target/rewrite; printf 'synthetic patch\\n' > target/rewrite/rewrite.patch; exit 0 ;;",
                "*:dryRun) exit 0 ;;"));
        refused(command, project, "a source-changing run requires its independent dry-run preview patch");
        Files.writeString(runner, fake.replace("*' verify') exit 0 ;;", "*' verify') exit 17 ;;"));
        refused(command, project, "Maven verification failure cannot promote a recipe");
        require(Boolean.TRUE.equals(verification(project).get("recipeExecutionAttempted")),
                "failed verification still records that rewrite application was attempted");
        Files.writeString(runner, fake.replace("    : > target/m3-code-agent/applied",
                "    printf '//out of scope\\n' >> pom.xml\n    : > target/m3-code-agent/applied"));
        refused(command, project, "recipe cannot alter POM outside FILE scope");
        Files.writeString(runner, fake.replace("  fi ;;", "  else\n    printf '//non-idempotent replay\\n' >> src/Example.java\n  fi ;;"));
        refused(command, project, "non-idempotent rewrite replay cannot pass");
        Files.writeString(runner, fake);
        Files.writeString(file, initial + "//source changed\n");
        refused(command, project, "stale source cannot run a nominated recipe");
        Files.writeString(file, initial);
        require(runNomination(plus(nomination, "--candidate-file", "candidate.json")) == 0,
                "valid original source restores nomination");
        System.out.println("CODE AGENT VERIFY TESTS PASS: " + checks + " (synthetic runner only)");
    }

    private static void refused(List<String> args, Path project, String why) throws Exception {
        require(runVerify(args) == 2, why);
        require("refused".equals(verification(project).get("status")), "refusal overwrites earlier success");
    }
    private static boolean rejectsMachine(Path project, Path source, Path evidence) {
        try { MachineEvidence.inspect(project, source, evidence); return false; }
        catch (java.io.IOException expected) { return true; }
    }
    private static List<String> replace(List<String> values, String key, String value) {
        List<String> copy = new ArrayList<>(values);
        copy.set(copy.indexOf(key) + 1, value);
        return copy;
    }
    private static List<String> plus(List<String> values, String key, String value) {
        List<String> copy = new ArrayList<>(values);
        copy.add(key); copy.add(value);
        return copy;
    }
    private static int runNomination(List<String> values) {
        return CodeAgentMain.run(values.toArray(String[]::new),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
    }
    private static int runVerify(List<String> values) {
        return CodeAgentVerifyMain.run(values.toArray(String[]::new),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
    }
    private static Map<?, ?> nomination(Path project) throws Exception {
        return object(TinyJson.parse(Files.readString(project.resolve("target/m3-smart/code-agent.json"))));
    }
    private static Map<?, ?> verification(Path project) throws Exception {
        return object(TinyJson.parse(Files.readString(project.resolve("target/m3-smart/code-agent-verify.json"))));
    }
    private static Map<?, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new AssertionError("JSON object expected");
        return map;
    }
    private static void require(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }
}
