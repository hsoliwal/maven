// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.nio.file.Files;
import java.nio.file.Path;

/** Hash-based semantic operator labels are candidate-only; tests require real source binding. */
public final class MachineOperatorCatalogTest {
    private static int checks;
    private MachineOperatorCatalogTest() { }

    public static void main(String[] args) throws Exception {
        Path project = Files.createTempDirectory("m3-operator-catalog-");
        Path source = Files.createDirectory(project.resolve("src"));
        String original = "class Example { int a(int v) { return v + 1; } int b(int v) { return v - 1; } }\n";
        Path java = Files.writeString(source.resolve("Example.java"), original);
        Path evidence = project.resolve("machine.tsv");
        Path catalog = project.resolve("operators.tsv");
        String fileSha = Hashes.sha256(java);
        String logicA = "a".repeat(64), shapeB = "b".repeat(64);
        String ledger = MachineEvidence.HEADER + "\n"
                + row(fileSha, "a000001", "c".repeat(64), logicA)
                + row(fileSha, "a000002", shapeB, "d".repeat(64))
                + row(fileSha, "a000003", "e".repeat(64), "f".repeat(64));
        Files.writeString(evidence, ledger);
        String approved = MachineOperatorCatalog.HEADER + "\n"
                + "map-int\tMAP\tLOGIC\t" + logicA + "\n"
                + "shape-int\tADAPTER\tSTRUCTURAL\t" + shapeB + "\n";
        Files.writeString(catalog, approved);
        MachineEvidence.Summary bound = MachineEvidence.inspect(project, source, evidence);
        MachineOperatorCatalog.Summary summary = MachineOperatorCatalog.inspect(project, evidence, catalog, bound);
        require(summary.atoms() == 3 && summary.operatorHints() == 2 && summary.unclassified() == 1,
                "source-bound operator hints and explicit unmatched leaf");
        require("NO_ADMITTED_OPERATOR".equals(summary.details().getFirst().residue()),
                "unrecognized atoms get priority in the bounded context");
        require(summary.details().stream().anyMatch(x -> "map-int".equals(x.operatorId())
                        && "LOGIC".equals(x.matchKind())), "exact logic cue is catalogued without proof");
        require(summary.details().stream().anyMatch(x -> "shape-int".equals(x.operatorId())
                        && "STRUCTURAL".equals(x.matchKind())), "structural cue is a fallback only");
        require(summary.details().stream().allMatch(x -> (x.proofObligationMask() & 3) == 3),
                "neither recognized nor unmatched atoms bypass contract/effect verification");
        require(summary.json().contains("\"semanticProofAuthenticated\":false")
                        && summary.json().contains("\"sourceBound\":true"),
                "report states both source binding and lack of semantic proof");
        require(summary.json().equals(MachineOperatorCatalog.inspect(project, evidence, catalog,
                MachineEvidence.inspect(project, source, evidence)).json()), "deterministic replay");
        require(summary.equals(MachineOperatorCatalog.inspect(project, evidence, catalog,
                MachineEvidence.inspect(project, source, evidence))), "semantic receipt has value equality");
        require((summary.details().getFirst().proofObligationMask() & 0x2f) == 0x2f,
                "contract effects flow dispatch and API proof obligations remain outstanding");
        require(summary.json().contains("\"contractEnvelope\":")
                && summary.json().contains("\"observableContractProven\":false"),
                "bounded LLM evidence contains explicit syntactic contract and unproven status");
        String pin = "0".repeat(64);
        String pinned = MachineOperatorCatalog.CONTRACT_PINNED_HEADER + "\n"
                + "map-int\tMAP\tLOGIC\t" + logicA + "\t" + pin + "\n"
                + "shape-int\tADAPTER\tSTRUCTURAL\t" + shapeB + "\t" + pin + "\n";
        Files.writeString(catalog, pinned);
        MachineOperatorCatalog.Summary contractBound = MachineOperatorCatalog.inspect(project, evidence, catalog, bound);
        require(contractBound.operatorHints() == 2 && contractBound.contractCueMismatches() == 0,
                "optional syntactic-contract pins admit matching reviewed operator cues");
        Files.writeString(catalog, pinned.replace("map-int\tMAP\tLOGIC\t" + logicA + "\t" + pin,
                "map-int\tMAP\tLOGIC\t" + logicA + "\t" + "1".repeat(64)));
        MachineOperatorCatalog.Summary mismatch = MachineOperatorCatalog.inspect(project, evidence, catalog, bound);
        require(mismatch.contractCueMismatches() == 1 && mismatch.operatorHints() == 1
                && "CONTRACT_CUE_MISMATCH".equals(mismatch.details().getFirst().residue()),
                "matching logic with different method signature becomes typed residue");
        Files.writeString(catalog, pinned.replace(pin, "not-a-digest"));
        refused(() -> MachineOperatorCatalog.inspect(project, evidence, catalog, bound),
                "malformed contract pin fails closed");
        Files.writeString(catalog, approved);
        String[] syntheticFlow = ("Example.java\t" + fileSha + "\ta000001\t" + "b".repeat(64)
                + "\t" + logicA + "\t0000000000000000\t" + pin
                + "\t0010\t0001\t1\t0\t" + "a".repeat(64) + "\t" + "b".repeat(64)
                + "\t3\t4\t" + "c".repeat(64) + "\t2\t1\t3\t"
                + "d".repeat(64) + "\t0000").split("\t", -1);
        MachineContractEnvelope envelope = MachineContractEnvelope.fromValidatedRow(syntheticFlow);
        require(envelope.outstandingProofMask() == 0x2f
                && envelope.json().contains("\"flowIncompleteMask\":0")
                && envelope.json().contains("\"effectsProven\":false"),
                "even no reported flow gaps does not prove observable equivalence");
        syntheticFlow[20] = "0010";
        require((MachineContractEnvelope.fromValidatedRow(syntheticFlow).outstandingProofMask() & 0x10) != 0,
                "unmodelled paths require an additional review obligation");

        Files.writeString(catalog, MachineOperatorCatalog.HEADER + "\n"
                + "map-int\tMAP\tLOGIC\t" + logicA + "\n"
                + "map-other\tMAP\tLOGIC\t" + logicA + "\n"
                + "shape-int\tADAPTER\tSTRUCTURAL\t" + shapeB + "\n");
        require(MachineOperatorCatalog.inspect(project, evidence, catalog, bound).ambiguous() == 1,
                "colliding operator labels remain unresolved ambiguity");
        Files.writeString(catalog, MachineOperatorCatalog.HEADER + "\n"
                + "z-last\tMAP\tLOGIC\t" + logicA + "\n"
                + "a-first\tMAP\tLOGIC\t" + logicA + "\n");
        refused(() -> MachineOperatorCatalog.inspect(project, evidence, catalog, bound),
                "unsorted catalog must fail closed");
        Files.writeString(catalog, approved);
        Files.writeString(evidence, ledger.replace("a000003", "a000004"));
        refused(() -> MachineOperatorCatalog.inspect(project, evidence, catalog, bound),
                "stale ledger cannot reuse the prior projection");
        Files.writeString(evidence, ledger);
        Files.writeString(java, original + "// drift\n");
        refused(() -> MachineEvidence.inspect(project, source, evidence),
                "canonical machine evidence must reject changed Java source before projection");
        Files.writeString(java, original);
        StringBuilder many = new StringBuilder(MachineEvidence.HEADER).append('\n');
        for (int i = 1; i <= 30; i++) many.append(row(fileSha, String.format("a%06d", i),
                "c".repeat(64), logicA));
        Files.writeString(evidence, many.toString());
        MachineOperatorCatalog.Summary crowded = MachineOperatorCatalog.inspect(project, evidence, catalog,
                MachineEvidence.inspect(project, source, evidence));
        require(crowded.atoms() == 30 && crowded.details().size() == 24
                        && crowded.json().contains("\"detailsTruncated\":true"),
                "review context is bounded and admits honest truncation");
        System.out.println("MACHINE OPERATOR CATALOG TESTS PASS: " + checks);
    }

    private static String row(String sourceSha, String id, String shape, String logic) {
        return "Example.java\t" + sourceSha + "\t" + id + "\t" + shape + "\t" + logic
                + "\t0000000000000000\t" + "0".repeat(64) + "\n";
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void refused(Checked action, String message) throws Exception {
        checks++;
        try { action.run(); } catch (java.io.IOException expected) { return; }
        throw new AssertionError(message);
    }
    private static void require(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }
}
