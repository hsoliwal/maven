<!--
SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# Windows MSVC C++ standard-library compatibility (separate host qualification)

The Java CI Windows `windows-latest` jobs on 25 November 2026 failed while compiling the
**unchanged** native Maven C++20 fixture with Clang 20.1.8 and Visual Studio 2026
MSVC STL 14.52: `yvals_core.h` emitted `STL1000: expected Clang 22 or newer`.
This is a native compiler / host C++ STL version conflict, not a Maven tool-provider
selection, OpenRewrite, M3DB or cache bug. Do not bypass the version check using
`_ALLOW_COMPILER_AND_STL_VERSION_MISMATCH`, remove `<iostream>`, skip the native
integration test or claim the older Clang 20 / MSVC 14.52 combination is qualified.

The **opt-in, additive** `m3-llvm-windows-stl-compat.yml` workflow runs on
`windows-latest` and pins the separately obtained upstream host-toolchain
LLVM **22.1.8**, rather than assuming whichever Clang is bundled on the runner.
The Chocolatey package's official installer URL and SHA-256
`16e5709785fef73c854646241c4a92c5cd574318d1b33c63330dd7721903e55c`
are checked against the installed package recipe; six installed executable
SHA-256 values are retained in the host receipt. A real C++20 `<iostream>`
compile/link/execute smoke test must print `42`, then the **unchanged original**
`LlvmCompilerIntegrationTestMain --required` runs against that installation
through Maven's real `TaskRunner`. An absent driver, wrong version, failed
link/run or missing required native evidence is an error, not `NOT_RUN`.

This Windows host-toolchain qualification is independent from the existing
`m3-llvm-fork-source-proof.yml`, whose original Clang, `llc`, `opt`, `llvm-as`
and `llvm-link` are built from the precise source revision
`hsoliwal/llvm-project@591f3d1edf0a9ee718d17a0ee70dd013b3c01908`
under Linux. **A passing Windows host-toolchain proof does not mean LLVM 22.1.8
was built from that fork, nor does it certify a Fortran runtime, cross-target
sysroot/ABI, or full Maven Java CI.** The new workflow does not change
production Maven Core, provider cache eligibility or the full Java CI matrix.

The workflow is intended as a focused prerequisite for a separately reviewed
full-CI toolchain update. Keep its first receiving PR draft until the Windows
hosted job executes and publishes its original unskipped evidence. Existing
Linux exact-source binary receipts and Windows package provenance must remain
distinct; neither can authorize native output-cache reuse.
