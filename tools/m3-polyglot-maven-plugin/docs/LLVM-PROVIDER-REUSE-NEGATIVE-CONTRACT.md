<!--
SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# LLVM Provider Reuse — Negative Admission Contract

Scope: test-only companion to Maven PR #228, based on
`feat/m3-polyglot-provider-reuse-20261118@3eb0ddc1fafa7cd3b8d9732208c4606bd6bbeb07`.

## Frozen behavior

The new `M3ToolProvider.reuseInputs` default method returns an empty
`Optional`; the original SPI v1, compiler command API and runtime behavior
remain compatible. Explicit provider reuse requires a provider-specific
complete input contract. The qualified Java example is not proof that
other compilers have complete dependency evidence.

The built-in LLVM compiler provider intentionally does **not** implement
`reuseInputs`. All seven declared language IDs (C, C++, Objective-C,
`objcpp`, additive `objc++`, Fortran, and `llvm-ir` through `llc`) must
continue to return an empty reuse assertion even when a caller requests
provider reuse. Their original command vectors are unchanged.

The native integration's repeated C17/C++20, negative `__has_include`,
header mutation, output removal and failure/recovery cases must also
execute through the **new** `providerReuse=true` overload, with both module
incremental state and output cache enabled, without any
`UP-TO-DATE`, `FROM-CACHE` or `CACHED` classification for LLVM tasks.
This is a regression gate, not permission to cache LLVM.

## Patch boundaries

- `M3ToolReuseInputsContractTestMain`: add a pure, no-installed-compiler
  regression exercising all seven LLVM language IDs with an explicit
  bin selector, comparing old/new command paths and asserting absent
  provider reuse evidence.
- `LlvmCompilerIntegrationTestMain`: route the existing native cache
  exclusion helper through the new overload with `providerReuse=true`.
  Keep all existing fixture inputs, output assertions and original
  compiler process semantics.
- No production code, public signature, Maven lifecycle, M3DB/CAS
  owner, original LLVM provider, or CI workflow changes.
- Do not vendor native LLVM binaries or reclassify source commit
  `591f3d1edf0a9ee718d17a0ee70dd013b3c01908`
  as proof of the installed binaries.

## Verification and admission

1. Inventory PR #228 and exact Git blob hashes before edits.
2. Produce this contract before patching tests.
3. Review the resulting diff and run style/syntax checks.
4. Compile the fork's actual tests with Java 17 and run the existing
   standalone and Maven verify suites, then real native LLVM tests
   against an installed compiler.
5. Require hosted CI on the new, exact-head companion PR after the
   parent PR is reconciled. A queued/absent check is not a pass.
6. If PR #228 changes its reuse API or test contracts, reconcile by
   explicit diff; never silently overwrite its branch.

This companion does not claim full Maven reactor, exact LLVM fork build,
Windows/macOS, Flang runtime, or cross-target ABI qualification.
