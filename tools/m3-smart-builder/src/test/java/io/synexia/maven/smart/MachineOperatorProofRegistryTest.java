// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Signed proof provenance is independently verified; it never upgrades model trust. */
public final class MachineOperatorProofRegistryTest {
    private static int checks;
    private MachineOperatorProofRegistryTest() { }

    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("m3-signed-operator-");
        Path root = Files.createDirectory(base.resolve("proofs"));
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path key = Files.write(base.resolve("operator-key.der"), keys.getPublic().getEncoded());
        String pinned = Hashes.sha256(Files.readAllBytes(key));
        String recipeId = "iter-fold", operatorId = "sum-fold";
        String implementation = "1".repeat(64), contract = "2".repeat(64);
        Map<String, String> artifacts = new LinkedHashMap<>();
        for (String name : List.of("STATUS.tsv", "FINAL_REPORT.md", "RUN_CONTEXT.tsv",
                "PROVENANCE.tsv", "VERIFY_CONTRACT.tsv", "OUTPUT_CONTRACT.tsv")) {
            byte[] body = ("artifact=" + name + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String sha = Hashes.sha256(body);
            Files.write(root.resolve(sha + ".artifact"), body);
            artifacts.put(name, sha);
        }
        StringBuilder artifactsJson = new StringBuilder("{");
        for (var entry : artifacts.entrySet()) {
            if (artifactsJson.length() > 1) artifactsJson.append(',');
            artifactsJson.append(TinyJson.quote(entry.getKey())).append(':').append(TinyJson.quote(entry.getValue()));
        }
        artifactsJson.append('}');
        String canon = "{\"schema\":\"" + MachineOperatorProofRegistry.CANON_SCHEMA
                + "\",\"status\":\"PASS\",\"contractSha256\":\"" + contract
                + "\",\"diffPassed\":true,\"linterPassed\":true,\"compilerPassed\":true,"
                + "\"testsPassed\":true,\"runtimePassed\":true,\"artifactDigests\":" + artifactsJson + "}";
        String canonSha = saveJson(root, canon);
        String stage = "{\"schema\":\"m3.smart.code-agent-verify.v1\",\"status\":\"verified-for-review\","
                + "\"approvedRecipeId\":\"" + recipeId + "\",\"recipeJarSha256\":\"" + implementation
                + "\",\"mavenVerifyPassed\":true,\"fixedPointPassed\":true,"
                + "\"sourceMutationAuthority\":false,\"semanticProofAuthenticated\":false,\"promoted\":false,"
                + "\"mode\":\"isolated-execution\",\"rewritePluginSha256\":\"" + "a".repeat(64)
                + "\",\"machineRulebookSha256\":\"" + "b".repeat(64)
                + "\",\"evidenceBindingSha256\":\"" + "c".repeat(64)
                + "\",\"stageRelativePath\":\"target/m3-smart/stage-one\","
                + "\"beforeSourceDigest\":\"" + "6".repeat(64)
                + "\",\"afterSourceDigest\":\"" + "7".repeat(64)
                + "\",\"changedJavaFiles\":1,\"dryRunPatchSha256\":\"" + "8".repeat(64)
                + "\",\"recipeExecutionAttempted\":true,\"verificationAuthenticated\":false}";
        String stageSha = saveJson(root, stage);
        String proof = "{\"schema\":\"" + MachineOperatorProofRegistry.RECEIPT_SCHEMA
                + "\",\"operatorId\":\"" + operatorId + "\",\"recipeId\":\"" + recipeId
                + "\",\"implementationSha256\":\"" + implementation
                + "\",\"contractSha256\":\"" + contract + "\",\"operatorCatalogSha256\":\"" + "3".repeat(64)
                + "\",\"stageReceiptSha256\":\"" + stageSha
                + "\",\"canonReceiptSha256\":\"" + canonSha + "\",\"status\":\"EXTERNALLY_RATIFIED\"}";
        String proofSha = saveJson(root, proof);
        String[] fields = {operatorId, recipeId, implementation, contract, "3".repeat(64), proofSha};
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(MachineOperatorProofRegistry.signable(fields));
        String signature = HexFormat.of().formatHex(signer.sign());
        String valid = MachineOperatorProofRegistry.HEADER + "\n"
                + String.join("\t", fields) + "\t" + signature + "\n";
        Path registry = Files.writeString(base.resolve("registry.tsv"), valid);
        MachineContractEnvelope envelope = new MachineContractEnvelope(contract,
                -1, -1, null, null, -1, null, null, -1);
        MachineOperatorCatalog.Summary semantic = new MachineOperatorCatalog.Summary(
                "3".repeat(64), "4".repeat(64), 1, 1, 0, 0, 0,
                List.of(new MachineOperatorCatalog.Leaf("Example.java", "a000001", "5".repeat(64),
                        operatorId, "FOLD", "LOGIC", "CONTRACT_AND_EFFECT_PROOF_REQUIRED", envelope)));
        Path catalogueFile = Files.writeString(base.resolve("catalog.tsv"),
                RecipeCatalog.HEADER + "\n" + recipeId + "\tITERATION\tFILE\texample.Recipe\t"
                        + implementation + "\n");
        RecipeCatalog recipes = RecipeCatalog.load(base, catalogueFile);

        MachineOperatorProofRegistry.Summary result = MachineOperatorProofRegistry.inspect(
                base, registry, root, key, pinned, semantic, recipes);
        require(result.signedEntries() == 1 && result.matchedEntries() == 1
                        && result.candidates().size() == 1, "a matching signed receipt yields only an attested hint");
        require(result.json().contains("\"signatureVerified\":true")
                        && result.json().contains("\"semanticProofAuthenticated\":false")
                        && result.json().contains("\"promotionAuthority\":false"),
                "signature validation is separate from semantic proof or promotion");
        require(result.equals(MachineOperatorProofRegistry.inspect(
                base, registry, root, key, pinned, semantic, recipes)), "deterministic signed evidence replay");

        MachineOperatorCatalog.Summary other = new MachineOperatorCatalog.Summary(
                "3".repeat(64), "4".repeat(64), 1, 1, 0, 0, 0,
                List.of(new MachineOperatorCatalog.Leaf("Example.java", "a000001", "5".repeat(64),
                        operatorId, "FOLD", "LOGIC", "CONTRACT_AND_EFFECT_PROOF_REQUIRED",
                        new MachineContractEnvelope("a".repeat(64),
                                -1, -1, null, null, -1, null, null, -1))));
        require(MachineOperatorProofRegistry.inspect(base, registry, root, key, pinned,
                other, recipes).matchedEntries() == 0, "signatures never override contract mismatches");
        refused(() -> MachineOperatorProofRegistry.inspect(base, registry, root, key,
                "f".repeat(64), semantic, recipes), "mismatched independent trust pin");

        MachineOperatorCatalog.Summary changedCatalogue = new MachineOperatorCatalog.Summary(
                "f".repeat(64), "4".repeat(64), 1, 1, 0, 0, 0, semantic.details());
        refused(() -> MachineOperatorProofRegistry.inspect(base, registry, root, key,
                pinned, changedCatalogue, recipes), "signed proof cannot transfer to another operator catalogue");
        Files.writeString(registry, valid.replace("\t" + signature, "\t" + "0".repeat(128)));
        refused(() -> MachineOperatorProofRegistry.inspect(base, registry, root, key,
                pinned, semantic, recipes), "forged Ed25519 signature");
        Files.writeString(registry, valid);
        Files.writeString(root.resolve(stageSha + ".json"),
                stage.replace("\"mavenVerifyPassed\":true", "\"mavenVerifyPassed\":false"));
        refused(() -> MachineOperatorProofRegistry.inspect(base, registry, root, key,
                pinned, semantic, recipes), "modified Maven verification receipt");
        Files.writeString(root.resolve(stageSha + ".json"), stage);
        Files.writeString(root.resolve(canonSha + ".json"),
                canon.replace("\"runtimePassed\":true", "\"runtimePassed\":false"));
        refused(() -> MachineOperatorProofRegistry.inspect(base, registry, root, key,
                pinned, semantic, recipes), "modified canonical gate declaration");
        Files.writeString(root.resolve(canonSha + ".json"), canon);

        String artifact = artifacts.get("STATUS.tsv");
        Files.writeString(root.resolve(artifact + ".artifact"), "tampered\n");
        refused(() -> MachineOperatorProofRegistry.inspect(base, registry, root, key,
                pinned, semantic, recipes), "modified mandatory M3 artifact");
        Files.writeString(root.resolve(artifact + ".artifact"), "artifact=STATUS.tsv\n");
        require(MachineOperatorProofRegistry.inspect(base, registry, root, key,
                pinned, semantic, recipes).signedEntries() == 1, "restored evidence is accepted");
        Path wrong = Files.writeString(base.resolve("wrong-recipes.tsv"),
                RecipeCatalog.HEADER + "\n" + recipeId + "\tITERATION\tFILE\texample.Recipe\t"
                        + "3".repeat(64) + "\n");
        refused(() -> MachineOperatorProofRegistry.inspect(base, registry, root, key,
                pinned, semantic, RecipeCatalog.load(base, wrong)), "implementation hash drift");
        Path broad = Files.writeString(base.resolve("broad-recipes.tsv"),
                RecipeCatalog.HEADER + "\n" + recipeId + "\tITERATION\tPACKAGE\texample.Recipe\t"
                        + implementation + "\n");
        refused(() -> MachineOperatorProofRegistry.inspect(base, registry, root, key,
                pinned, semantic, RecipeCatalog.load(base, broad)), "unapproved higher-scope promotion");
        // Windows development hosts may not grant unprivileged symlink creation.
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            Path link = base.resolve("linked-key.der");
            Files.createSymbolicLink(link, key);
            refused(() -> MachineOperatorProofRegistry.inspect(base, registry, root, link,
                    pinned, semantic, recipes), "symlinked trust anchors refused");
        }
        // V2 binds an external attestation to the exact currently selected machine ledger and leaf.
        String sourceProof = "{\"schema\":\"" + MachineOperatorProofRegistry.SOURCE_RECEIPT_SCHEMA
                + "\",\"operatorId\":\"" + operatorId + "\",\"recipeId\":\"" + recipeId
                + "\",\"implementationSha256\":\"" + implementation
                + "\",\"contractSha256\":\"" + contract
                + "\",\"operatorCatalogSha256\":\"" + semantic.catalogSha256()
                + "\",\"machineEvidenceSha256\":\"" + semantic.evidenceSha256()
                + "\",\"semanticLeafSha256\":\"" + semantic.details().getFirst().semanticId()
                + "\",\"stageReceiptSha256\":\"" + stageSha
                + "\",\"canonReceiptSha256\":\"" + canonSha + "\",\"status\":\"EXTERNALLY_RATIFIED\"}";
        String v2proof = saveJson(root, sourceProof);
        String[] scopedFields = {operatorId, recipeId, implementation, contract, semantic.catalogSha256(),
                semantic.evidenceSha256(), semantic.details().getFirst().semanticId(), v2proof};
        signer.initSign(keys.getPrivate());
        signer.update(MachineOperatorProofRegistry.signable(scopedFields));
        String scopedSignature = HexFormat.of().formatHex(signer.sign());
        String scopedRegistry = MachineOperatorProofRegistry.SOURCE_HEADER + "\n"
                + String.join("\t", scopedFields) + "\t" + scopedSignature + "\n";
        Files.writeString(registry, scopedRegistry);
        MachineOperatorProofRegistry.Summary scope = MachineOperatorProofRegistry.inspect(
                base, registry, root, key, pinned, semantic, recipes, "6".repeat(64));
        require(scope.sourceScopedEntries() == 1 && scope.matchedEntries() == 1
                        && scope.candidates().getFirst().semanticLeafSha256() != null,
                "V2 attestation authenticates one specific machine ledger and semantic leaf");
        require(scope.json().contains("\"scope\":\"SOURCE_LEAF\"")
                        && scope.json().contains("\"semanticProofAuthenticated\":false"),
                "source-specific signature does not grant automatic semantic equivalence");
        MachineOperatorCatalog.Summary changedLedger = new MachineOperatorCatalog.Summary(
                semantic.catalogSha256(), "9".repeat(64), 1, 1, 0, 0, 0, semantic.details());
        refused(() -> MachineOperatorProofRegistry.inspect(base, registry, root, key, pinned,
                changedLedger, recipes, "6".repeat(64)), "source-specific signature cannot follow a changed machine ledger");
        MachineOperatorCatalog.Summary changedLeaf = new MachineOperatorCatalog.Summary(
                semantic.catalogSha256(), semantic.evidenceSha256(), 1, 1, 0, 0, 0,
                List.of(new MachineOperatorCatalog.Leaf("Other.java", "a000009", "8".repeat(64),
                        operatorId, "FOLD", "LOGIC", "CONTRACT_AND_EFFECT_PROOF_REQUIRED", envelope)));
        require(MachineOperatorProofRegistry.inspect(base, registry, root, key, pinned,
                changedLeaf, recipes, "6".repeat(64)).matchedEntries() == 0,
                "same contract and catalogue cannot transfer a V2 proof to another leaf");
        refused(() -> MachineOperatorProofRegistry.inspect(base, registry, root, key, pinned,
                semantic, recipes), "source-specific signature requires current source digest");
        refused(() -> MachineOperatorProofRegistry.inspect(base, registry, root, key, pinned,
                semantic, recipes, "7".repeat(64)), "signed staged source cannot move to a different checkout");
        // A correctly signed V2 chain cannot turn a zero-change or incomplete stage into proof.
        String zeroStage = stage.replace("\"changedJavaFiles\":1", "\"changedJavaFiles\":0");
        String zeroStageSha = saveJson(root, zeroStage);
        String zeroProofSha = saveJson(root, sourceProof.replace(stageSha, zeroStageSha));
        String[] zeroFields = scopedFields.clone();
        zeroFields[7] = zeroProofSha;
        signer.initSign(keys.getPrivate());
        signer.update(MachineOperatorProofRegistry.signable(zeroFields));
        String zeroSignature = HexFormat.of().formatHex(signer.sign());
        Files.writeString(registry, MachineOperatorProofRegistry.SOURCE_HEADER + "\n"
                + String.join("\t", zeroFields) + "\t" + zeroSignature + "\n");
        refused(() -> MachineOperatorProofRegistry.inspect(base, registry, root, key, pinned,
                semantic, recipes, "6".repeat(64)), "signed V2 zero-change staged receipt is not proof");
        Files.writeString(registry, scopedRegistry);
        String emptyStage = stage.replace(",\"dryRunPatchSha256\":\"" + "8".repeat(64) + "\"", "");
        String emptyStageSha = saveJson(root, emptyStage);
        String emptyProofSha = saveJson(root, sourceProof.replace(stageSha, emptyStageSha));
        String[] emptyFields = scopedFields.clone();
        emptyFields[7] = emptyProofSha;
        signer.initSign(keys.getPrivate());
        signer.update(MachineOperatorProofRegistry.signable(emptyFields));
        Files.writeString(registry, MachineOperatorProofRegistry.SOURCE_HEADER + "\n"
                + String.join("\t", emptyFields) + "\t" + HexFormat.of().formatHex(signer.sign()) + "\n");
        refused(() -> MachineOperatorProofRegistry.inspect(base, registry, root, key, pinned,
                semantic, recipes, "6".repeat(64)), "signed V2 missing dry-run preview is not proof");
        Files.writeString(registry, scopedRegistry);
        Files.writeString(registry, scopedRegistry.replace(scopedSignature, signature));
        refused(() -> MachineOperatorProofRegistry.inspect(base, registry, root, key, pinned,
                semantic, recipes, "6".repeat(64)), "V1 signature cannot authorize V2 source scoped claim");
        Files.writeString(registry, valid);
        System.out.println("MACHINE OPERATOR PROOF REGISTRY TESTS PASS: " + checks);
    }

    private static String saveJson(Path root, String text) throws IOException {
        byte[] content = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = Hashes.sha256(content);
        Files.write(root.resolve(sha + ".json"), content);
        return sha;
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void refused(Checked test, String message) throws Exception {
        checks++;
        try { test.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError("Expected refusal: " + message);
    }
    private static void require(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }
}
