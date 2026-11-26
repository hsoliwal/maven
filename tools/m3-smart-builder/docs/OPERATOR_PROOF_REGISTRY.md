<!-- SPDX-License-Identifier: Apache-2.0 -->
# M3 signed operator proof provenance (opt-in, review only)

This is an **external evidence authentication** layer for the existing
m3:code-agent and m3:code-agent-verify goals. It does not modify Java sources,
Maven Core, the default Maven lifecycle or the locked M3-SCALE canon.

A signature authenticates who vouched for a receipt and whether its bytes are
unchanged. It **does not** establish that two operators are semantically or
behaviorally equivalent. A signer can attest to poor tests or incomplete proof.
Canonical promotion still belongs to the independent M3 proof owner.

## Inputs and trust root

These four properties must be supplied together, in addition to source-bound
m3.smart.machineEvidence and m3.smart.operatorCatalog:

| Maven property | Meaning |
| --- | --- |
| m3.smart.operatorProofRegistry | Sorted signed TSV registry in checkout |
| m3.smart.operatorProofDir | Content-addressed proof directory in checkout |
| m3.smart.operatorProofPublicKey | X.509 DER Ed25519 public key in checkout |
| m3.smart.operatorProofPublicKeySha256 | **Independently operator-pinned** lowercase SHA-256 of key bytes |

The private signing key must remain outside source, build output, browser,
and LLM context. Neither the model nor checked-in data may establish its
own trusted signing key.

Registry rows have a trailing LF and this exact seven-column TSV header:

    operator_id    recipe_id    implementation_sha256    contract_sha256    operator_catalog_sha256    proof_receipt_sha256    signature_ed25519_hex

The actual fields are **TAB-separated** (the spaces above are for display).
Rows are strictly sorted by operator ID and limited to 64. All SHA-256 fields
are 64 lowercase hexadecimal digits; signatures are 128 lowercase hex digits.

Each Ed25519 signature covers UTF-8 text consisting of these newline-separated
fields, including the final LF:

    m3.smart.operator-proof-registry.v1
    <operator_id>
    <recipe_id>
    <implementation_sha256>
    <contract_sha256>
    <operator_catalog_sha256>
    <proof_receipt_sha256>

Each row must bind to the exact current approved operator catalogue SHA-256,
a FILE-scoped reviewed OpenRewrite recipe, and the exact implementation JAR
SHA-256 in the existing recipe catalogue.

## Proof chain and output artifacts

All file references are derived exclusively from the validated SHA-256 fields,
using the proof directory provided by the operator; arbitrary paths or URLs
inside the signed rows are never executed or followed.

1. PROOF_DIR/PROOF_SHA.json has exactly: schema, operatorId, recipeId,
   implementationSha256, contractSha256, operatorCatalogSha256,
   stageReceiptSha256, canonReceiptSha256 and status. It has schema
   m3.smart.operator-proof-receipt.v1 and status EXTERNALLY_RATIFIED.
2. PROOF_DIR/STAGE_SHA.json has the existing m3.smart.code-agent-verify.v1
   schema; its status is verified-for-review, recipe name/hash match the row,
   Maven verify and fixed-point replay passed, and promotion/trust flags
   remain false.
3. PROOF_DIR/CANON_SHA.json uses m3.smart.operator-canon-evidence.v1,
   status PASS, matching contract SHA-256, and explicit diffPassed,
   linterPassed, compilerPassed, testsPassed and runtimePassed booleans.
   The artifactDigests object must contain **exactly** these M3 output files:
   STATUS.tsv, FINAL_REPORT.md, RUN_CONTEXT.tsv, PROVENANCE.tsv,
   VERIFY_CONTRACT.tsv and OUTPUT_CONTRACT.tsv.
4. Every output digest is independently loaded as PROOF_DIR/SHA.artifact,
   rehashed and required to exist. A bare PASS assertion with no artifact
   bytes fails closed.

The canonical-evidence JSON in step 3 is an **external sign-off interchange**,
not a claim that this Maven plugin implements all M3 canon proof machinery.
An external proof owner must inspect the actual artifacts before signing.

All input files are restricted to the checkout, with symlinks and oversized
inputs refused by the existing guarded RecipeCatalog.readLocal routine.
Changed public keys, invalid signatures, changed receipts, altered artifacts,
wrong recipe implementations, changed operator catalogues and mismatched
syntactic contracts all fail closed.

## Maven invocation

    mvn io.synexia:m3-smart-maven-plugin:0.1.0-SNAPSHOT:code-agent \
      -Dm3.smart.sourceRoot=src/main/java \
      -Dm3.smart.preparationReport=.reports/preparation.json \
      -Dm3.smart.atomPatternManifest=.reports/atom-pattern.tsv \
      -Dm3.smart.recipeCatalog=.reports/approved-recipes.tsv \
      -Dm3.smart.machineEvidence=target/m3-smart/machine-evidence.tsv \
      -Dm3.smart.operatorCatalog=.reports/approved-operators.tsv \
      -Dm3.smart.operatorProofRegistry=.reports/signed-operator-proofs.tsv \
      -Dm3.smart.operatorProofDir=.reports/proofs \
      -Dm3.smart.operatorProofPublicKey=.reports/trusted-key.der \
      -Dm3.smart.operatorProofPublicKeySha256=REPLACE_WITH_INDEPENDENT_64_HEX_PIN

Use the same proof and operator inputs for the **separate, explicitly approved**
m3:code-agent-verify invocation. Omitting the proof registry leaves the old
nomination and verifier schema/digest unchanged.

## Scope, report and authority

The bounded operatorProofFacts report contains registry/key digests,
signedEntries, matchedEntries, at most 24 matching operator/contract candidates
and the SIGNED_EXTERNAL_ATTESTATION authority. Matching is restricted to
source-bound, visible semantic leaves with the same operator ID and syntactic
contract SHA-256. The full registry is nevertheless checked before nomination.

The flags semanticProofAuthenticated=false and promotionAuthority=false
remain non-negotiable. Signature verification cannot establish effects,
dynamic dispatch, exceptions, API/ABI/JNI compatibility or behavioral
equivalence. No file-to-package or API promotion is authorized.

The new signed-proof-attestation M3 rule is REVIEW_ONLY and does not alter
the locked M3 canon. Only independent proof and serial ratification can
promote a candidate.

## Qualification

MachineOperatorProofRegistryTest uses an ephemeral Ed25519 keypair and
synthetic evidence, exercising valid signatures, key pinning, tamper
detection, required M3 outputs, catalogue binding, recipe implementation
drift, scope refusal and deterministic replay. It is not a qualification
of a real signing authority, a live model, or real OpenRewrite execution.
