# M3 Smart Builder PR #320 — source-contract qualification packet

**Status:** SOURCE_AUDIT_PASS; RELEASE_HOLD. This report certifies only the
source-level consistency checks described below. It does not certify the
entire Maven plugin, external proof owner, OpenRewrite recipe or semantic equivalence.

**Scope and commit:** [PR #320](https://github.com/hsoliwal/maven/pull/320),
audited head `6ec318f3cf68e26432b9a0fc93e940ff79f9580f` against `master` at
`62499c2051a6152f8e4951728264efb4fd5b1fb8`.
Targets were `tools/m3-smart-builder` and the new scoped Java 21 CI workflow.

## Files changed

This report pass adds only the six files in
`tools/m3-smart-builder/verification/pr320/`.
Earlier PR commits added contract-aware operator hints, source-scoped signed
Ed25519 provenance, staged-verifier bindings, tests and the real-JDK
acceptance workflow. **No Maven core, default lifecycle or Java project source
was changed by this report pass.**

## Exact verification executed

- **42 of 42** read-only cross-file assertions passed against the audited GitHub
  branch: 29-rule sorted rulebook with 4 mechanical admissions; both agent
  options/bindings; both Mojo parameters; v1/v2 signature domain separation;
  source/leaf/staged-digest checks; test registration; CI workflow contract.
- A prior focused Java 21 compiler/regression pass reported **23 registry tests
  passed** on actual GitHub source blobs using minimal dependency stand-ins.
  That earlier run does not substitute for the missing full plugin build.
- The branch contains an acceptance workflow for actual Maven `clean install`,
  generated plugin descriptor inspection, complete `scripts/verify.sh`, and
  Python harness contract tests. **No GitHub Actions result was reported** at
  the time this packet was created.

## Exact blockers and failed/unexecuted gates

- Local container has Java 21 but **no Maven executable** and outbound GitHub
  and Maven Central DNS is unavailable. Full Maven packaging could not run.
- OpenRewrite Maven Plugin **6.66.0** was released 2026-11-15; current upstream
  distribution is authenticated Code Genome Project Maven, not anonymous
  Maven Central. Real offline recipe execution requires independently approved
  and hash-pinned JARs and transitive dependencies. No credentials are stored.
- No complete end-to-end real OpenRewrite transform, independent semantic
  equivalence, API/ABI/JNI behavior proof, or serial M3 promotion was performed.
- GitHub PR mergeability is **not** treated as a successful test.

## Artifact paths and next gates

The six mandatory M3 report artifacts are siblings of this report and indexed
in `OUTPUT_CONTRACT.tsv`. `PROVENANCE.tsv` records **Git SHA-1 object IDs**,
not SHA-256 file digests; operator proof artifacts remain separately SHA-256
content-addressed.

Before promotion: run the Java 21 acceptance workflow; examine actual Maven
plugin descriptor and logs; provision and test a real pinned OpenRewrite
6.66.0 recipe; prove contract/behavior compatibility; ratify serially.
**This PR must remain a draft until those gates are satisfied.**
