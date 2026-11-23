<!-- SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Exact-fork LLVM source-proof: fail-closed trigger qualification

## Reviewed baseline and ownership

Maven receiving commit: `5f0a0f1bbc60c0d84dccdff2e27fed694881678f`.
Before-change verifier Git blob: `9c859b43e22e5d4313e9acbb94617bffb79d77d8`.
Before-change workflow Git blob: `18914a33fd101dcdd2f5eb1caf3e5af5fd76ddf0`.

This is a verification-only change to the existing
`.github/workflows/m3-llvm-fork-source-proof.yml`; it neither changes
Maven Core, the original LLVM/CMake/Ninja build, compiler arguments,
the public M3ToolProvider SPI, the Clang AST frontend, nor M3DB/CAS
cache-admission rules. The source donor stays pinned to
`hsoliwal/llvm-project@591f3d1edf0a9ee718d17a0ee70dd013b3c01908`.

## Reproduced bypass and required behavior

The original trigger verifier incorrectly accepts a duplicate top-level
`on:` mapping after `jobs:`, a non-master push branch, a restricted
pull-request branch and removal of manual dispatch. A YAML loader can
replace the earlier `on:` on duplicate input, eliminating real source
build scheduling while returning an apparent verification success.

The new verifier must require exactly one canonical top-level `on:`,
`push`, `pull_request`, and `workflow_dispatch`; only
`push.branches: [master]` is admitted, with unrestricted pull requests.
Both path lists must be identical and contain the 17 literal, required
source-owner paths, including the verifier, this document and its
negative regression test. Duplicate, negated, wildcard, aliased or
unknown syntax fails closed. Required source paths and their ancestor
directories must exist without symlinks.

The workflow runs the Python standard-library regression tests followed
by the read-only verifier **before** fetching and compiling LLVM. The
original native refusal checks, including PR #273's unreadable-directory
and failing-find cases, remain byte-identical. No second source-build
system, scheduler or provider is introduced.

## Ordered acceptance and release boundary

1. Inventory the exact Git heads and file blobs; document before code.
2. Apply the minimal patch without changing existing LLVM native steps.
3. Lint/diff check; Python syntax; negative tests; full YAML and Bash
   structural checks; read-only CLI runtime.
4. Verify pre/post SHA-256, Git blob identity and second-run idempotence.
5. Require actual hosted exact-head LLVM 24 Clang/Clang++/llc compilation
   and Maven native integration evidence before qualification or merging.

A synthetic owner-path fixture demonstrates only trigger verification,
not Maven compilation or an LLVM source-to-binary build. A queued,
cancelled or skipped GitHub workflow does not qualify native binaries.
