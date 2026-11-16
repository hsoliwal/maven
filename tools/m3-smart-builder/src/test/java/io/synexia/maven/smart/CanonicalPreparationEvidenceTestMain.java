// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Synthetic receipt structural checks; no claim of authenticated SDK execution. */
public final class CanonicalPreparationEvidenceTestMain {
    private static int checks;
    private CanonicalPreparationEvidenceTestMain() {}

    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void refused(Checked action, String reason) throws Exception {
        checks++;
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("admitted invalid receipt: " + reason);
    }

    static String report(SourceInventory.Selected selected) {
        SourceInventory.FileFact file = selected.files().getFirst();
        String id = TinyJson.quote(file.path());
        String hash = TinyJson.quote(file.sha256());
        String owners = "{\"M3PreparePrivatePrimitiveAtomsRecipe.java\":\"" + "a".repeat(64) + "\"}";
        return "{"
                + "\"schema\":\"synexia.smart-build.preparation.v1\","
                + "\"status\":\"COMPLETE\","
                + "\"sourceRoot\":" + TinyJson.quote(selected.sourceRoot()) + ","
                + "\"sourceDigest\":" + TinyJson.quote(selected.summary().digest()) + ","
                + "\"sourceFiles\":1,\"sourceBytes\":" + selected.summary().bytes() + ","
                + "\"files\":[{\"path\":" + id + ",\"sha256\":" + hash
                + ",\"bytes\":" + file.bytes()
                + ",\"kind\":\"JAVA\",\"status\":\"COMPLETE\","
                + "\"reason\":\"NO_SUPPORTED_EXTRACTION\","
                + "\"atomPreparation\":{\"status\":\"UNCHANGED:NO_SUPPORTED_EXTRACTION:SYNEXIA-FILE-ATOM-CANONICALIZATION-1\","
                + "\"candidateSha256\":" + hash + ",\"proposedHelpers\":0},"
                + "\"patterns\":[]}],"
                + "\"recipes\":[{\"name\":\"com.synexia.rewrite.M3PreparePrivatePrimitiveAtomsRecipe\","
                + "\"status\":\"EXECUTED\",\"executedFiles\":1,\"rows\":1},"
                + "{\"name\":\"com.synexia.rewrite.M3JavaPatternInventoryRecipe\","
                + "\"status\":\"EXECUTED\",\"executedFiles\":1,\"rows\":0}],"
                + "\"atomCandidateFiles\":0,\"proposedPrivateHelpers\":0,"
                + "\"patternRowCount\":0,\"patternCounts\":{},\"unsupportedFiles\":0,"
                + "\"executionProvenance\":{\"runner\":\"com.synexia.rewrite.ChromeLlmRecipeExecution\","
                + "\"ownerRevision\":\"" + "0".repeat(40) + "\","
                + "\"ownerSources\":" + owners + ",\"javaRuntime\":\"21\","
                + "\"execution\":\"OPENREWRITE_SDK\",\"sourceMutationAuthority\":false,"
                + "\"promotionAuthority\":false,\"physicalExtractionExecuted\":false},"
                + "\"remainingStages\":[\"com.synexia.rewrite.M3AtomizeRecipe\","
                + "\"com.synexia.rewrite.M3PatternizeRecipe\"]}";
    }

    public static void main(String[] args) throws Exception {
        Path project = Files.createTempDirectory("canonical-preparation-evidence");
        Path selected = Files.createDirectory(project.resolve("src"));
        Path java = selected.resolve("Example.java");
        String original = "class Example { int doubleIt(int x) { return x + x; } }\n";
        Files.writeString(java, original);
        SourceInventory.Selected inventory = SourceInventory.inspectSelected(project, selected);
        require(inventory.files().size() == 1 && inventory.summary().javaFiles() == 1,
                "selected source inventory includes Java");
        Path reports = Files.createDirectory(project.resolve(".reports"));
        Path receipt = reports.resolve("preparation.json");
        String payload = report(inventory);
        Files.writeString(receipt, payload);
        String accepted = CanonicalPreparationEvidence.inspect(project, selected, receipt);
        require(accepted.contains("PRODUCER_REPORTED_NOT_REEXECUTED"),
                "only source-bound producer-reported evidence");
        require(accepted.contains("\"behaviorEquivalenceVerified\":false"),
                "no semantic proof conferred");
        require(!accepted.contains("doubleIt"),
                "no raw source or method name in evidence facts");

        Files.writeString(java, original + "// source drift\n");
        refused(() -> CanonicalPreparationEvidence.inspect(project, selected, receipt),
                "changed source bytes");
        Files.writeString(java, original);
        require(CanonicalPreparationEvidence.inspect(project, selected, receipt).equals(accepted),
                "repeated byte-identical source restores accepted facts");
        Files.writeString(selected.resolve("configuration.yml"), "mode: candidate\n");
        refused(() -> CanonicalPreparationEvidence.inspect(project, selected, receipt),
                "extra unsupported asset");
        Files.delete(selected.resolve("configuration.yml"));
        Files.writeString(receipt, payload.replace("\"status\":\"COMPLETE\"", "\"status\":\"INCOMPLETE\""));
        refused(() -> CanonicalPreparationEvidence.inspect(project, selected, receipt),
                "incomplete producer report");
        Files.writeString(receipt, payload);
        Files.writeString(receipt, payload.replace("\"physicalExtractionExecuted\":false",
                "\"physicalExtractionExecuted\":true"));
        refused(() -> CanonicalPreparationEvidence.inspect(project, selected, receipt),
                "candidate falsely promotes physical extraction");
        Files.writeString(receipt, payload);
        Files.writeString(receipt, payload.replace("\"candidateSha256\":" + TinyJson.quote(inventory.files().getFirst().sha256()),
                "\"candidateSha256\":\"" + "f".repeat(64) + "\""));
        refused(() -> CanonicalPreparationEvidence.inspect(project, selected, receipt),
                "unchanged atom candidate must retain source hash");
        System.out.println("CANONICAL_PREPARATION_EVIDENCE_PASS checks=" + checks);
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }
}
