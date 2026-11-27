# M3 source-scoped attestation replay — bounded qualification report

**Status: SOURCE_AUDIT_PASS / RELEASE_HOLD.** This is an append-only
verification packet for code commit `13694afd41bc91a596d8bb44504fa09283f142e1` in
[hsoliwal/maven PR #320](https://github.com/hsoliwal/maven/pull/320).
It does **not** assert a full build or independently proven equivalence.

## Files changed

The code batch tightened `MachineOperatorProofRegistry`, `CodeAgentMain`
and `CodeAgentVerifyMain` with a source-scoped V2 admission check that rejects
signed receipts when their stage `machineRulebookSha256` differs from the
current packaged rulebook, or their stage `evidenceBindingSha256` differs from
the current *pre-proof* nomination binding. The latter is deliberately
computed before incorporating signed proof facts to avoid circularity.
A nonzero staged rewrite also requires different before/after source digests.

The M3 rulebook gained the `stage-evidence-binding` MECHANICAL gate (30 laws,
five mechanically enforced). Regression tests cover validly signed stale
policy/nomination receipts, unchanged output, missing pre-proof binding,
a changed but inapplicable recipe catalogue, and missing mandatory rule.
The JDK-only verification script now executes code-agent/proof checks before
unrelated syntax-plane probes, without removing any checks.

## Exactly what was verified

A read-only GitHub source audit of the pending code and published file SHAs
checked nine source/test/resource/script files. All 30 packaged rules were in
strict lexicographic order; five had MECHANICAL authority, and the new
admission-rule implementation, test registration and replay bindings were
present. The seven Java sources scanned passed a comment/string-aware
delimiter-balance check; no trailing whitespace was found.

**These are source-level assertions, not compiler or behavioral proofs.**
Previously reported 23 focused Java 21 assertions applied to an **earlier**
GitHub commit, not this changed code. No new 23/28-test success is claimed.

## Exact blockers

- OpenJDK 21.0.12.1 is installed locally, but Maven is absent and the full
  updated source tree cannot be materialized from the authorized GitHub
  connector into the build container. Outbound GitHub/Maven Central DNS
  requests fail. Therefore full Maven plugin packaging and the complete
  new regression suite were **not executed**.
- The dedicated Java 21 GitHub Actions workflow is committed but no workflow
  run is reported for this PR head at the time of this report.
- OpenRewrite 6.66.0 and approved recipes must be independently provisioned
  with pinned digests into the approved offline repository. No real replay,
  Chrome model session or independent API/ABI/JNI/semantic equivalence gate ran.

## Next proof steps

Run the committed `m3-operator-proof-qualification.yml` acceptance workflow
and inspect the packaged plugin descriptor, compiler output, and focused
regression logs. Provision exact OpenRewrite/recipe artifacts for an
authorized real replay. Compare API/ABI/JNI and observable effects;
ratify in the M3 canon only after the independent proof owner accepts.
**Draft PR #320 remains unmerged.**

Six mandatory M3 outputs accompany this report, with exact statuses in
`STATUS.tsv` and `VERIFY_CONTRACT.tsv`. Provenance Git refs here are SHA-1
object identifiers and are not interchangeable with source or attestation
SHA-256 content hashes.
