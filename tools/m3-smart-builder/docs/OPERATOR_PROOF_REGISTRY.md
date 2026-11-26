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

Legacy catalogue-scoped registry rows have a trailing LF and this exact seven-column TSV header:

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


## V2: stronger source-leaf-scoped attestations

The v1 seven-column format remains accepted and is explicitly reported
as CATALOGUE_ONLY with sourceBindingVerified=false. It **cannot** be taken as
an attestation for a specific Java method, even when its operator and contract
hashes happen to match.

For an operator-signed attestation that applies to one specific source atom,
use the separate **nine-column** registry header:

    operator_id    recipe_id    implementation_sha256    contract_sha256    operator_catalog_sha256    machine_evidence_sha256    semantic_leaf_sha256    proof_receipt_sha256    signature_ed25519_hex

As before, the actual fields are TAB-separated and the file ends with LF.
The v2 domain-separated Ed25519 message contains these lines, with LF after
the last field:

    m3.smart.operator-proof-registry.v2
    <operator_id>
    <recipe_id>
    <implementation_sha256>
    <contract_sha256>
    <operator_catalog_sha256>
    <machine_evidence_sha256>
    <semantic_leaf_sha256>
    <proof_receipt_sha256>

The v2 content-addressed proof receipt uses schema
m3.smart.operator-proof-receipt.v2 and the v1 fields plus
machineEvidenceSha256 and semanticLeafSha256. Its current machine-evidence
digest must match the independently source-validated ledger. The semantic
leaf identity must match a currently visible leaf's ID, operator name and
syntactic contract. V2 rows sort by operator ID **then semantic-leaf SHA-256**,
allowing multiple distinct leaves of the same reviewed operator.

V2 requires the archived stage's **machineRulebookSha256** to match the
current packaged rulebook and its **evidenceBindingSha256** to match the
current `MachineRulebook.bind` digest *before adding signed proof facts*.
This is deliberately a non-circular proof edge: the stage is run before a
signatory creates the proof registry, then the completed signed proof
participates in a fresh code-agent nomination. The pre-proof binding is
computed from the current source-bound preparation, atom, machine, operator,
rulebook and reviewed recipe catalog. It is rechecked before and after
staged replay. A signer cannot re-authorize an old policy or unrelated
nomination just by signing a new proof receipt.

In addition, the archived stage's **afterSourceDigest** must differ from
its beforeSourceDigest when the staged verifier reports changed Java files.
A signature or an asserted test status cannot waive this mechanical gate.
The new `stage-evidence-binding` M3 rule has MECHANICAL authority because
the Maven consumer implements these exact V2 refusal checks. It remains a
source-admission gate, not a behavioral equivalence or promotion claim.

V2 also requires the **real 20-field** code-agent staged-verifier receipt,
not a synthetic minimal stage assertion. The archived stage's
beforeSourceDigest must match the actual current source inventory digest.
The staged receipt must record a real nonzero changed-file count, an existing
OpenRewrite dry-run patch SHA-256, successful recipe execution, Maven verify,
fixed-point replay and the non-promotion flags. The source digest is gathered
from current checkout bytes before nomination and independently rechecked
before final staged verification.

A new or different Java source tree changes the source inventory SHA-256,
machine evidence and/or semantic-leaf identity and invalidates this
attestation. A changed signing key, operator catalogue, recipe JAR, proof
artifact or stage result also invalidates it.

Both formats remain signed **external attestations**, not full semantic proof.
`sourceBindingVerified=true` means only that the declared
source/evidence identities were checked against the current checkout, **not**
that the operator is equivalent, free of effects or safe to promote.
`semanticProofAuthenticated=false` and `promotionAuthority=false`
remain required. The additional `source-proof-scope` law is REVIEW_ONLY;
the locked canonical M3 specification is unchanged.

## Running Java 21 qualification on the PR branch

The existing M3 Smart Maven Builder workflow is already present on the
repository's default branch and has a workflow_dispatch trigger. It is the
currently actionable manual acceptance gate for draft PR #320: it runs a
real Maven plugin clean install, JDK-only compiler/tests, and a real Maven
runtime harness on a GitHub-hosted Java 21 runner.

A repository maintainer with write access can open GitHub Actions, select
**M3 Smart Maven Builder**, choose **Run workflow**, select branch
`feat/m3-semantic-operator-residue-20261126`, and start the run.

An authenticated GitHub CLI equivalent is:

    gh workflow run m3-smart-builder.yml --repo hsoliwal/maven \
      --ref feat/m3-semantic-operator-residue-20261126

    gh run list --repo hsoliwal/maven --workflow m3-smart-builder.yml \
      --branch feat/m3-semantic-operator-residue-20261126 --limit 5

The PR-added `m3-operator-proof-qualification.yml` has stricter packaged
descriptor assertions and log retention. Its workflow_dispatch control
cannot normally be used until that workflow file exists on the default
branch; the pull_request trigger is the expected automatic path.
**No test success is inferred from workflow configuration alone.**
Review completed run logs and artifacts before any merge or M3 promotion.
Runner and GitHub credentials must stay outside the repository, prompts,
and PR comments.


## Strict V2 recipe selection (optional)

With the existing source-bound machine/atom evidence, operator catalogue,
approved recipe catalogue, and a valid V2 signed proof registry, supply
`-Dm3.smart.requireSourceScopedRecipeProof=true` to **both** code-agent and
code-agent-verify. Only recipes that satisfy *both* the existing preparation
pattern counts and a current V2 signed source-leaf attestation may be
nominated. A legacy seven-column V1 attestation is **not** sufficient.
An unrecognized boolean, missing V2 proof or unrelated recipe fails closed.
Strict nominations have a distinct SHA-256 binding, and the verifier refuses
mode-switched replay. The rule is mechanically enforced by Maven and is
packaged as `signed-recipe-admission`.

This controls *eligibility*, not truth. Signed external attestations still
have `semanticProofAuthenticated=false` and
`promotionAuthority=false`. Operator approval, pinned offline
OpenRewrite verification, API/ABI/JNI compatibility and M3 serial canon
ratification remain independent requirements.
