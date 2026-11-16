<!-- SPDX-License-Identifier: Apache-2.0 -->
# M3 Smart Builder — Apache RAT source/proof audit

This audit is scoped to the Apache RAT 0.18 failure on the Maven baseline
`eb644c9ea11b6fd40809d2e17cbc61410cf94e57`, reproduced unchanged
on inventory PR #46. CI: https://github.com/hsoliwal/maven/actions/runs/43732689092

The original RAT diagnostic identified **46** unapproved files below
`tools/m3-smart-builder`. Exactly **29** are source, test, script, POM or
operator documentation and now have the minimal `SPDX-License-Identifier:
Apache-2.0` annotation. No authorship or copyright ownership is inferred
by this annotation. The top-level `LICENSE` and `NOTICE` are unchanged.

The remaining **17** are historical verification logs, TSV manifests,
JSONL change events or verification reports. They are inputs/evidence, not
executable source. They must retain their exact historical bytes; rewriting
them to inject a license header would invalidate recorded evidence and hashes.
Root `pom.xml` therefore excludes **only these exact 17 pre-existing proof
paths** from the RAT source-header scan (not the parent directory, not any
future proof path, not ordinary source/test/docs files). These exclusions are
not a claim of permission to redistribute third-party data; any release
package must review its evidence contents and rights separately.

## Exact excluded historical proof paths

- `tools/m3-smart-builder/verification/10-toolchain.log`
- `tools/m3-smart-builder/verification/11-lint.log`
- `tools/m3-smart-builder/verification/12-compile-test-runtime.log`
- `tools/m3-smart-builder/verification/13-idempotence.log`
- `tools/m3-smart-builder/verification/14-replay.log`
- `tools/m3-smart-builder/verification/15-source-replay-before.tsv`
- `tools/m3-smart-builder/verification/16-source-replay-result.log`
- `tools/m3-smart-builder/verification/CHANGE_EVENT_LOG.jsonl`
- `tools/m3-smart-builder/verification/DIAGNOSE_SOURCE_SHA256.tsv`
- `tools/m3-smart-builder/verification/DIAGNOSE_VERIFICATION.md`
- `tools/m3-smart-builder/verification/MCP_REPLY_CHANGE_EVENTS.tsv`
- `tools/m3-smart-builder/verification/MCP_REPLY_REPLAY.log`
- `tools/m3-smart-builder/verification/MCP_REPLY_RUNTIME.log`
- `tools/m3-smart-builder/verification/MCP_REPLY_SHA256_AFTER_REPLAY.tsv`
- `tools/m3-smart-builder/verification/MCP_REPLY_SHA256_BEFORE_REPLAY.tsv`
- `tools/m3-smart-builder/verification/PRE_CHANGE_SHA256.tsv`
- `tools/m3-smart-builder/verification/VERIFICATION.md`

## Gate boundaries

- All 29 ordinary sources, tests, scripts, POM and docs remain subject to RAT.
- No license headers were inserted into immutable proof or hash manifests.
- No Java signatures, compiler options, Maven plugin behaviors, runtime logic,
  refactoring/atomization authority or source inventory algorithms changed.
- This remediation is source metadata plus a limited scanner input-selection
  policy. **It does not establish a passing RAT run until current CI executes**.
- The full Smart Builder, Chrome AI, and canonical atom/pattern behavior suites
  remain independent release gates. Do not suppress RAT globally with
  `-Drat.skip=true` as an approval substitute.
