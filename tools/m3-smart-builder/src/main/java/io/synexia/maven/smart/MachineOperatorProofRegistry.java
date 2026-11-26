// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies the integrity and external sign-off of operator proof artifacts, not their semantics.
 * The trusted Ed25519 public-key SHA-256 must be supplied independently by the operator.
 * Signed attestations remain REVIEW_ONLY until the M3 canonical proof owner ratifies them.
 */
final class MachineOperatorProofRegistry {
    static final String HEADER = "operator_id\trecipe_id\timplementation_sha256\tcontract_sha256"
            + "\toperator_catalog_sha256\tproof_receipt_sha256\tsignature_ed25519_hex";
    static final String SOURCE_HEADER = "operator_id\trecipe_id\timplementation_sha256\tcontract_sha256"
            + "\toperator_catalog_sha256\tmachine_evidence_sha256\tsemantic_leaf_sha256"
            + "\tproof_receipt_sha256\tsignature_ed25519_hex";
    static final String CANON_SCHEMA = "m3.smart.operator-canon-evidence.v1";
    static final String RECEIPT_SCHEMA = "m3.smart.operator-proof-receipt.v1";
    static final String SOURCE_RECEIPT_SCHEMA = "m3.smart.operator-proof-receipt.v2";
    private static final int MAX_ROWS = 64;
    private static final int MAX_DETAILS = 24;
    private static final Set<String> M3_ARTIFACTS = Set.of("STATUS.tsv", "FINAL_REPORT.md", "RUN_CONTEXT.tsv",
            "PROVENANCE.tsv", "VERIFY_CONTRACT.tsv", "OUTPUT_CONTRACT.tsv");
    private static final Set<String> RECEIPT_KEYS = Set.of("schema", "operatorId", "recipeId",
            "implementationSha256", "contractSha256", "operatorCatalogSha256",
            "stageReceiptSha256", "canonReceiptSha256", "status");
    private static final Set<String> SOURCE_RECEIPT_KEYS = Set.of("schema", "operatorId", "recipeId",
            "implementationSha256", "contractSha256", "operatorCatalogSha256",
            "machineEvidenceSha256", "semanticLeafSha256",
            "stageReceiptSha256", "canonReceiptSha256", "status");
    private static final Set<String> SOURCE_STAGE_KEYS = Set.of("schema", "status", "mode",
            "approvedRecipeId", "recipeJarSha256", "rewritePluginSha256", "machineRulebookSha256",
            "evidenceBindingSha256", "stageRelativePath", "beforeSourceDigest", "afterSourceDigest",
            "changedJavaFiles", "dryRunPatchSha256", "recipeExecutionAttempted", "mavenVerifyPassed",
            "fixedPointPassed", "sourceMutationAuthority", "semanticProofAuthenticated",
            "verificationAuthenticated", "promoted");
    private static final Set<String> CANON_KEYS = Set.of("schema", "status", "contractSha256", "diffPassed",
            "linterPassed", "compilerPassed", "testsPassed", "runtimePassed", "artifactDigests");

    record Candidate(String operatorId, String recipeId, String contractSha256, String proofReceiptSha256,
                     String semanticLeafSha256, int matchedVisibleAtoms) {
        String json() {
            return "{\"operatorId\":" + TinyJson.quote(operatorId)
                    + ",\"recipeId\":" + TinyJson.quote(recipeId)
                    + ",\"contractSha256\":" + TinyJson.quote(contractSha256)
                    + ",\"signedReceiptSha256\":" + TinyJson.quote(proofReceiptSha256)
                    + ",\"matchedVisibleAtoms\":" + matchedVisibleAtoms
                    + ",\"semanticLeafSha256\":" + (semanticLeafSha256 == null ? "null" : TinyJson.quote(semanticLeafSha256))
                    + ",\"sourceBindingVerified\":" + (semanticLeafSha256 != null)
                    + ",\"scope\":" + TinyJson.quote(semanticLeafSha256 == null
                    ? "CATALOGUE_ONLY" : "SOURCE_LEAF")
                    + ",\"authority\":\"SIGNED_EXTERNAL_ATTESTATION\"}";
        }
    }

    record Summary(String registrySha256, String trustedPublicKeySha256, int signedEntries,
                   int sourceScopedEntries, int matchedEntries, List<Candidate> candidates) {
        String json() {
            StringBuilder out = new StringBuilder("{\"schema\":\"m3.smart.operator-proof-registry.v1\","
                    + "\"registrySha256\":").append(TinyJson.quote(registrySha256))
                    .append(",\"trustedPublicKeySha256\":").append(TinyJson.quote(trustedPublicKeySha256))
                    .append(",\"signedEntries\":").append(signedEntries)
                    .append(",\"sourceScopedEntries\":").append(sourceScopedEntries)
                    .append(",\"matchedEntries\":").append(matchedEntries)
                    .append(",\"candidatesTruncated\":").append(matchedEntries > candidates.size())
                    .append(",\"candidateAttestations\":[");
            for (int i = 0; i < candidates.size(); i++)
                out.append(i == 0 ? "" : ",").append(candidates.get(i).json());
            return out.append("],\"artifactDigestsVerified\":true,\"signatureVerified\":true,"
                    + "\"semanticProofAuthenticated\":false,\"promotionAuthority\":false}").toString();
        }
    }

    private MachineOperatorProofRegistry() { }

    static Summary inspect(Path base, Path registryFile, Path proofDirectory, Path keyFile,
                           String trustedKeySha256, MachineOperatorCatalog.Summary semantic,
                           RecipeCatalog recipes) throws IOException {
        return inspect(base, registryFile, proofDirectory, keyFile, trustedKeySha256, semantic, recipes, null, null);
    }

    static Summary inspect(Path base, Path registryFile, Path proofDirectory, Path keyFile,
                           String trustedKeySha256, MachineOperatorCatalog.Summary semantic,
                           RecipeCatalog recipes, String currentSourceDigest) throws IOException {
        return inspect(base, registryFile, proofDirectory, keyFile, trustedKeySha256,
                semantic, recipes, currentSourceDigest, null);
    }

    static Summary inspect(Path base, Path registryFile, Path proofDirectory, Path keyFile,
                           String trustedKeySha256, MachineOperatorCatalog.Summary semantic,
                           RecipeCatalog recipes, String currentSourceDigest,
                           String expectedStageEvidenceBindingSha256) throws IOException {
        if (semantic == null || recipes == null) throw new IOException("proof registry needs validated operator candidates");
        if (!isHash(trustedKeySha256)) throw new IOException("missing independent public-key pin");
        byte[] keyBytes = RecipeCatalog.readLocal(base, keyFile, 256);
        String actualKey = Hashes.sha256(keyBytes);
        if (!actualKey.equals(trustedKeySha256)) throw new IOException("untrusted proof signing key");
        PublicKey key = decodePublicKey(keyBytes);
        byte[] registryBytes = RecipeCatalog.readLocal(base, registryFile, 65_536);
        String[] rows = lines(utf8(registryBytes));
        boolean sourceScoped = SOURCE_HEADER.equals(rows[0]);
        if (sourceScoped && (!isHash(currentSourceDigest)
                || !isHash(expectedStageEvidenceBindingSha256)))
            throw new IOException("current source and pre-proof evidence binding required for source-scoped proof");
        if (rows.length < 2 || rows.length > MAX_ROWS + 1
                || !(HEADER.equals(rows[0]) || sourceScoped))
            throw new IOException("invalid operator proof registry schema");
        List<Candidate> matches = new ArrayList<>();
        int totalMatches = 0;
        String previous = "";
        for (int i = 1; i < rows.length; i++) {
            String[] fields = rows[i].split("\t", -1);
            if (fields.length != (sourceScoped ? 9 : 7) || !fields[0].matches("[a-z][a-z0-9-]{0,63}")
                    || !fields[1].matches("[a-z][a-z0-9-]{0,63}")
                    || !isHash(fields[2]) || !isHash(fields[3]) || !isHash(fields[4])
                    || !isHash(fields[sourceScoped ? 7 : 5])
                    || sourceScoped && (!isHash(fields[5]) || !isHash(fields[6]))
                    || !fields[sourceScoped ? 8 : 6].matches("[0-9a-f]{128}"))
                throw new IOException("invalid operator proof row");
            String keyName = fields[0] + (sourceScoped ? "\t" + fields[6] : "");
            if (keyName.compareTo(previous) <= 0)
                throw new IOException("duplicate or unsorted operator proof row");
            previous = keyName;
            if (sourceScoped && !fields[5].equals(semantic.evidenceSha256()))
                throw new IOException("source-scoped proof bound to another machine ledger");
            if (!fields[4].equals(semantic.catalogSha256()))
                throw new IOException("signed operator proof bound to another operator catalogue");
            RecipeCatalog.Entry recipe = recipes.entries().stream()
                    .filter(entry -> entry.id().equals(fields[1])).findFirst()
                    .orElseThrow(() -> new IOException("proof registry refers to unapproved recipe"));
            if (!recipe.implementationSha256().equals(fields[2]) || !"FILE".equals(recipe.scope()))
                throw new IOException("proof registry recipe implementation or scope drift");
            byte[] message = signable(java.util.Arrays.copyOf(fields, sourceScoped ? 8 : 6));
            verifySignature(key, message, HexFormat.of().parseHex(fields[sourceScoped ? 8 : 6]));
            Map<?, ?> receipt = readJsonProof(base, proofDirectory, fields[sourceScoped ? 7 : 5]);
            if (!receipt.keySet().equals(sourceScoped ? SOURCE_RECEIPT_KEYS : RECEIPT_KEYS)
                    || !(sourceScoped ? SOURCE_RECEIPT_SCHEMA : RECEIPT_SCHEMA).equals(receipt.get("schema"))
                    || !"EXTERNALLY_RATIFIED".equals(receipt.get("status"))
                    || !fields[0].equals(receipt.get("operatorId"))
                    || !fields[1].equals(receipt.get("recipeId"))
                    || !fields[2].equals(receipt.get("implementationSha256"))
                    || !fields[3].equals(receipt.get("contractSha256"))
                    || !fields[4].equals(receipt.get("operatorCatalogSha256"))
                    || sourceScoped && (!fields[5].equals(receipt.get("machineEvidenceSha256"))
                    || !fields[6].equals(receipt.get("semanticLeafSha256"))))
                throw new IOException("proof receipt does not match signed registry row");
            String stageSha = hashField(receipt, "stageReceiptSha256");
            String canonSha = hashField(receipt, "canonReceiptSha256");
            checkStage(readJsonProof(base, proofDirectory, stageSha), fields[1], fields[2],
                    sourceScoped, currentSourceDigest, expectedStageEvidenceBindingSha256);
            checkCanon(base, proofDirectory, readJsonProof(base, proofDirectory, canonSha), fields[3]);
            int visible = 0;
            for (MachineOperatorCatalog.Leaf leaf : semantic.details())
                if (fields[0].equals(leaf.operatorId())
                        && fields[3].equals(leaf.envelope().syntacticContractSha256())
                        && (!sourceScoped || fields[6].equals(leaf.semanticId()))) visible++;
            if (visible > 0) {
                totalMatches++;
                if (matches.size() < MAX_DETAILS)
                    matches.add(new Candidate(fields[0], fields[1], fields[3],
                            fields[sourceScoped ? 7 : 5], sourceScoped ? fields[6] : null, visible));
            }
        }
        return new Summary(Hashes.sha256(registryBytes), actualKey, rows.length - 1,
                sourceScoped ? rows.length - 1 : 0, totalMatches, List.copyOf(matches));
    }

    /** Signs an exact canonical registry row, not its arbitrary path or untrusted presentation. */
    static byte[] signable(String[] fields) {
        if (fields.length != 6 && fields.length != 8)
            throw new IllegalArgumentException("unsupported signed row width");
        String domain = fields.length == 8 ? "m3.smart.operator-proof-registry.v2\n"
                : "m3.smart.operator-proof-registry.v1\n";
        return (domain + String.join("\n", fields) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static Map<?, ?> readJsonProof(Path base, Path root, String sha) throws IOException {
        byte[] bytes = RecipeCatalog.readLocal(base, root.resolve(sha + ".json"), 16_384);
        if (!Hashes.sha256(bytes).equals(sha)) throw new IOException("proof receipt digest mismatch");
        Object parsed;
        try { parsed = TinyJson.parse(utf8(bytes)); }
        catch (IllegalArgumentException invalid) { throw new IOException("invalid proof JSON", invalid); }
        if (!(parsed instanceof Map<?, ?> map)) throw new IOException("proof receipt not a JSON object");
        return map;
    }

    private static void checkStage(Map<?, ?> stage, String recipeId, String implementationSha,
                                   boolean sourceScoped, String currentSourceDigest,
                                   String expectedStageEvidenceBindingSha256) throws IOException {
        if (!"m3.smart.code-agent-verify.v1".equals(stage.get("schema"))
                || !"verified-for-review".equals(stage.get("status"))
                || !recipeId.equals(stage.get("approvedRecipeId"))
                || !implementationSha.equals(stage.get("recipeJarSha256"))
                || !Boolean.TRUE.equals(stage.get("mavenVerifyPassed"))
                || !Boolean.TRUE.equals(stage.get("fixedPointPassed"))
                || !Boolean.FALSE.equals(stage.get("sourceMutationAuthority"))
                || !Boolean.FALSE.equals(stage.get("semanticProofAuthenticated"))
                || !Boolean.FALSE.equals(stage.get("promoted")))
            throw new IOException("stage receipt not an approved verified-for-review replay");
        if (sourceScoped && (!stage.keySet().equals(SOURCE_STAGE_KEYS)
                || !"isolated-execution".equals(stage.get("mode"))
                || !hashValue(stage.get("rewritePluginSha256"))
                || !MachineRulebook.load().sha256().equals(stage.get("machineRulebookSha256"))
                || !expectedStageEvidenceBindingSha256.equals(stage.get("evidenceBindingSha256"))
                || !currentSourceDigest.equals(stage.get("beforeSourceDigest"))
                || !hashValue(stage.get("afterSourceDigest"))
                || currentSourceDigest.equals(stage.get("afterSourceDigest"))
                || !hashValue(stage.get("dryRunPatchSha256"))
                || !(stage.get("changedJavaFiles") instanceof String count
                     && count.matches("[1-9][0-9]{0,5}"))
                || !Boolean.TRUE.equals(stage.get("recipeExecutionAttempted"))
                || !Boolean.FALSE.equals(stage.get("verificationAuthenticated"))))
            throw new IOException("source-scoped proof refers to incomplete or stale staged run");
    }

    private static void checkCanon(Path base, Path root, Map<?, ?> canon, String contract) throws IOException {
        if (!canon.keySet().equals(CANON_KEYS) || !CANON_SCHEMA.equals(canon.get("schema"))
                || !"PASS".equals(canon.get("status")) || !contract.equals(canon.get("contractSha256"))
                || !Boolean.TRUE.equals(canon.get("diffPassed"))
                || !Boolean.TRUE.equals(canon.get("linterPassed"))
                || !Boolean.TRUE.equals(canon.get("compilerPassed"))
                || !Boolean.TRUE.equals(canon.get("testsPassed"))
                || !Boolean.TRUE.equals(canon.get("runtimePassed")))
            throw new IOException("incomplete external canon proof declaration");
        if (!(canon.get("artifactDigests") instanceof Map<?, ?> artifacts)
                || !artifacts.keySet().equals(M3_ARTIFACTS))
            throw new IOException("incomplete M3 output contract");
        for (String name : M3_ARTIFACTS) {
            Object digest = artifacts.get(name);
            if (!(digest instanceof String sha) || !isHash(sha))
                throw new IOException("malformed M3 output digest");
            byte[] material = RecipeCatalog.readLocal(base, root.resolve(sha + ".artifact"), 262_144);
            if (!Hashes.sha256(material).equals(sha)) throw new IOException("M3 output artifact digest mismatch");
        }
    }

    private static void verifySignature(PublicKey key, byte[] message, byte[] signature) throws IOException {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(message);
            if (!verifier.verify(signature)) throw new IOException("invalid external proof signature");
        } catch (GeneralSecurityException invalid) {
            throw new IOException("external proof signature unavailable", invalid);
        }
    }

    private static PublicKey decodePublicKey(byte[] bytes) throws IOException {
        try { return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(bytes)); }
        catch (GeneralSecurityException invalid) { throw new IOException("invalid Ed25519 trust anchor", invalid); }
    }

    private static String hashField(Map<?, ?> json, String key) throws IOException {
        Object raw = json.get(key);
        if (!(raw instanceof String sha) || !isHash(sha)) throw new IOException("missing proof hash field");
        return sha;
    }

    private static boolean hashValue(Object value) { return value instanceof String sha && isHash(sha); }
    private static boolean isHash(String text) { return text != null && text.matches("[0-9a-f]{64}"); }

    private static String utf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String[] lines(String text) throws IOException {
        if (text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0 || !text.endsWith("\n"))
            throw new IOException("malformed operator proof registry encoding");
        return text.substring(0, text.length() - 1).split("\n", -1);
    }
}
