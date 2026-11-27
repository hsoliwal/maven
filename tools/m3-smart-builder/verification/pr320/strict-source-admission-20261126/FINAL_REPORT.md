# M3 strict source-scoped recipe admission — qualification packet

**Status: SOURCE_AUDIT_PASS; RELEASE_HOLD.**

Source code commit: e7d36c6c0a7ff7a4de175c53d7d65ab2f65b7ce0. Existing draft [PR #320](https://github.com/hsoliwal/maven/pull/320).

## Exact bounded implementation

Added the opt-in Maven flag m3.smart.requireSourceScopedRecipeProof, with the
same admission checks in code-agent and code-agent-verify. A recipe is eligible
only when it is already operator-reviewed and applicable, AND a current V2
Ed25519 attestation matches a source-bound semantic leaf for that recipe.
V1 catalogue-only signatures or unrelated approved recipes cannot satisfy
strict source-scoped admission. The strict flag domain-separates the nomination
SHA-256 so replay cannot silently omit the flag. Legacy no-strict bindings and
receipts remain unchanged.

Fixed a scale mistake during staging: machine proof admission originally
inspected only the 24 semantic leaves exposed to the model. The final code
retains the complete bounded source-validated semantic inventory (up to 4096
atoms), matches signed V2 evidence against all of it, and independently
records the model-visible matching count. A source-leaf proof omitted from the
LLM display can still qualify its reviewed recipe. Incomplete internal atom
inventories are refused.

The M3 rulebook now contains **31 sorted laws, six mechanically enforced**.
The new signed-recipe-admission rule is enforced rather than only prompted.

## Exact verification executed

A 40/40 GitHub source-contract audit passed on the published source commit.
It checked mechanical rule identities, strict digest domain separation,
source-scoped recipe filtering, real Maven option wiring, full inventory
matching versus bounded model display, test registration and CI wiring,
and unchanged default behavior. No trailing whitespace was found in the
15 audited files.

These were static source assertions. A staging audit initially found
two full-inventory assertions missing due a subsequent Git-tree overwrite.
Both defects were corrected on commit e7d36c6, and the repeated source audit
passed 40/40. Do not treat previous failed static checks as compiler test
results.

## Outstanding release gates

The new Java 21 regression fixtures, full Maven plugin build, actual Maven
descriptor, live OpenRewrite 6.66.0, behavioral contract equivalence and
API/ABI/JNI gates have NOT been executed for the latest source commit.
Local OpenJDK 21 exists but Maven is absent and GitHub source checkout is
not available in the container; no workflow run has been reported for this
draft PR head. Prior scoped 23-test runs applied to older commits and are
not recycled as current proof.

The independent M3 canon owner must review a real transformed code path,
tests, exceptions, ordering, effects, ABI/JNI, and the six M3 proof outputs
before serial FILE -> PACKAGE -> MODULE -> REACTOR -> API promotion. A
signed external attestation authenticates provenance, not behavior.

## Evidence

Six sibling artifacts form the M3 output contract. PROVENANCE.tsv includes
Git SHA-1 object identifiers as such; they must not be confused with
per-file source SHA-256 or M3 content-addressed proof digests.
