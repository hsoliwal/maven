<!--
SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# M3 LLVM Native Conformance — Master-Convergence Contract (2026-11-18)

**Scope:** review/merge resolution for [Maven PR #214](https://github.com/hsoliwal/maven/pull/214).
This is a source/CI convergence, not an LLVM compiler fork build or native cache-admission change.

## Baseline and owners

- Receiving repository: `hsoliwal/maven`; reviewed master starting at
  `41b727e54b318bdc2efb2145527c0f2bd4bfa025`.
- Donor source commit: `hsoliwal/llvm-project@591f3d1edf0a9ee718d17a0ee70dd013b3c01908`
  (upstream tree identity at inspection; it is **not** an attested binary).
- Preserve existing merged work: #198 (compiler), #199 (shared home and executable
  SHA), #203 (LLVM tools provider), #210 (toolchain evidence) and #213
  (native dependency/cache tests and packaged consumer).
- One owner per responsibility: Maven polyglot `TaskRunner` executes;
  existing `M3ToolProvider` dispatches; Clang atomizer extracts AST;
  M3DB/CAS owners store their existing receipts. No second provider,
  parser, scheduler or cache-admission protocol is introduced.

## Reviewed differences and admissible merge

The #214 branch was based on older master and contained a second copy of the
LLVM integration fixture. Current master already contains the **stronger**
`LlvmCompilerIntegrationTestMain`: preserve its offline `llc -> clang`
plan, malformed-optional-tool refusals, and native dependency/cache
invalidation checks. Do not overwrite it with older #214 test text.

Admit only additional, contract-preserving leaves:

1. Add `objc++` as an alias alongside the existing `objcpp` spelling;
   both dispatch to `clang++` with unchanged argument ordering.
2. Extend the existing sorted ServiceLoader contract inventory/driver test.
3. Require `LlvmCompilerIntegrationTestMain --required` in the consolidated
   Ubuntu/JDK17+21 polyglot CI job **after** packaging the plugin, so a
   missing C/C++ driver is a failure rather than an optional skip.
4. Execute the existing packaged `examples/llvm` Maven consumer and verify
   the exact stdout. Preserve its current POM, manifest and fixtures.
5. Keep the manual `m3-polyglot-verify.yml` native requirement consistent
   with the consolidated workflow.
6. Update the existing toolchain guide with explicit cache-exclusion and
   host-qualification boundaries; retain all existing owner documentation.

## Frozen contracts

- `--m3-llvm-bin=<directory>` and legacy PATH selection remain valid.
- `--m3-llvm-home=<root>`, `m3.llvm.home`, `M3_LLVM_HOME` and optional
  `--m3-llvm-sha256=<hex>` retain existing precedence and validation.
- `@m3:llvm:compiler:llvm-ir` invokes `llc`, **not** `clang`.
- `@m3:llvm:compiler:objcpp` stays valid. `objc++` is additive.
- Original flags/argv, working directories, failure propagation, opt-in
  lifecycle and Maven public APIs remain unchanged.
- Provider work is never marked `UP-TO-DATE` or `FROM-CACHE` from generic
  receipts. A positive depfile cannot represent negative `__has_include`
  dependencies or the complete native SDK/compiler closure.
- A real host Clang pass does not establish that the binary was built from
  the pinned LLVM fork. No LLVM source/binaries are vendored.

## Required verification stages

1. Review exact parent/branch heads, source Git blob identities and diff.
2. Patch only approved paths, then check syntax/style and unintended removals.
3. Compile Java with `--release 17 -Xlint:all -Werror`, then full plugin Maven
   verification in CI (JDK 17 and 21).
4. Run actual native C/C++ execution, new-header invalidation, cache-exclusion,
   and the packaged Maven consumer in CI.
5. Check workflows, logs, checksum/attestation and PR mergeability before merge.
6. On Windows/macOS, Flang/llc and exact fork-source binaries, record
   `NOT_RUN` until independently qualified.

**Promotion rule:** a GitHub branch/PR alone is not a green build; do not merge
or claim cross-platform or exact LLVM source/binary provenance without evidence.
