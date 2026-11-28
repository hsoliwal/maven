<!-- SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Independent LLVM exact-fork LLD receipt audit (v1)

## Receiving baseline and immutable owners

Maven receiving base: `c8e20d1da6bd20b9522b25ec6cd74d8719b46cb0`.
LLVM source pin: `hsoliwal/llvm-project@591f3d1edf0a9ee718d17a0ee70dd013b3c01908`.

This is a **read-only validation layer** over the existing source-built
Clang/Clang++/llc, LLVM IR opt/llvm-as/llvm-link, and LLD Wasm/PE-COFF
JDK21→JDK17 proof. Maven's original `TaskRunner`, both tool providers,
the native CMake/Ninja owner, the LLD frontend and the two original native
integration classes remain authoritative. No public Java signatures,
native compiler vectors, library source, M3DB/CAS, or cache eligibility may
change through this audit.

## Audit requirements

The proposed `scripts/verify-pinned-llvm-lld-receipts.py` consumes either:
- `--evidence DIR --build DIR` on the **same runner** before the evidence
  artifact upload, with actual native executable SHA-256 checked again; or
- `--archive ZIP` on the **downloaded GitHub Actions artifact**, accepting
  only structural and cross-file identity evidence, never pretending that
  the original binaries were included in the archive.

Both modes must fail closed on malformed, duplicated, missing, extra
owner/driver identities, unknown paths, path traversal, zip symlinks, or
mismatched digests. No files are created or modified by the auditor.
The resulting PASS line is only a consistent-evidence receipt: it is NOT
independent reconstruction of the C++ toolchain.

The source receipt must have exactly one matching source repository,
revision, clean-checkout claim and the six ordered original native
successes: compiler, compiler/JDK17, IR/JDK21, IR/JDK17, LLD/JDK21,
LLD/JDK17. Fortran remains NOT_RUN in this source build.

The binary receipt must enumerate the nine original drivers, exactly
once and in fixed order: clang, clang++, llc, opt, llvm-as, llvm-link,
ld.lld, wasm-ld, lld-link. Each entry consists of its name, SHA-256
and absolute build/bin path, and a nonblank original version line.
The CMake cache must identify `clang;lld`, `X86;WebAssembly`, and
`CLANG_ENABLE_CIR=OFF`. The five source-file checksum receipts include
the two audited LLD CMake files.

For `jdk17-binaries-{before,after}.sha256`, the IR JDK21/JDK17
before/after transcripts, and the LLD JDK21/JDK17 before/after
transcripts, validate expected driver coverage, exact binary path and
source-built SHA-256 agreement. Same-stage before and after must
match; JDK17 must match JDK21; no artificial downgrade to fewer drivers
is permitted. Runner mode additionally hashes the original nine
executables. Archive mode cannot verify absent binary bytes and must
state that limitation.

Require actual original native log signatures:
- `maven-native-run.log` and `maven-native-jdk17.log`:
  C17/C++20, llc/Clang, Objective-C/Objective-C++, and aggregate native
  regression PASS (Fortran may legitimately be NOT_RUN).
- `llvm-ir-jdk{21,17}-native.log`: the original five-stage IR PASS,
  with no NOT_RUN.
- `llvm-lld-jdk{21,17}-native.log`: the original WASM/PE-COFF PASS,
  with no NOT_RUN.

The CI checks the auditor's synthetic negative-test suite before
downloading LLVM, and executes the auditor against the real nine tools
after both JDK lanes and **before uploading** the existing artifact.
If the validation fails, the native job fails; artifact upload retains
the failed logs but does not confer release qualification.

## Admission and evidence

The new script, its regression suite, and this contract are required
literal trigger owners in both symmetrical push/master and pull-request
path lists, enforced by the established strict trigger verifier.
New source/test files each serve exactly one purpose. Existing public
Maven lifecycle and LLVM providers are not patched.

Qualification gates, in order: inventory and immutable pre-hashes;
this documentation; patch diff/whitespace/YAML/Python lint; Python
compile; adversarial synthetic unit tests; runner/archived-fixture
execution; independent published Git blob readback; then hosted source
build and original native runtime CI. A queued, cancelled, failed or
absent hosted run never counts as qualification. Replaying an existing
archive does not establish cross-platform ABI or source-built MLIR,
Flang, target runtime SDK, native artifact-cache restoration, or
reproducible whole-environment provenance.

## Strict archive ownership and contradictory-log refusal (v1 refinement)

The initial independent auditor admitted two incorrect successes: a GitHub
artifact ZIP could carry unrecognized files under the otherwise valid
receipt prefix, and a Maven compilation log could contain both
`BUILD FAILURE` and `BUILD SUCCESS`. Both conditions must fail closed.
They were reproduced against the previously published auditor before
any corrective change was prepared.

The archive member inventory is **closed** to the exact source-build owners:
source/binary/CMake receipts, configuration/build logs, up to three original
bounded Maven Central retry attempts, each original Maven/JDK17 runtime and
native log, and the declared IR/LLD JDK17/21 version, compilation, native
and digest transcripts. New artifact-producing stages must explicitly update
this owner registry and its negative tests before admission. Unknown file
names, duplicate aliases, or noncanonical ZIP separators are not accepted
as harmless extras. Existing original artifact names and contents are not
renamed or removed.

Each required Maven log must have a real `BUILD SUCCESS` witness **and**
must not contain contradictory terminal `BUILD FAILURE`, `[ERROR]`
or an uncaught exception. The required native logs must carry the original
`PASS:` output on an actual completed line; stray marker text and any
terminal `FAIL:`, `BUILD FAILURE` or uncaught exception must fail.
The native source compiler tests deliberately exercise invalid C and IR,
so ordinary compiler `error:` diagnostics from a caught negative test
remain permitted. A `NOT_RUN: Fortran` diagnostic in the compiler lane
remains permitted: source-built Flang is not part of this gate. IR and
LLD native lanes continue to refuse every `NOT_RUN:` result.

These are structural, read-only evidence checks, **not cryptographic proof
that a retained archive contains native compiler binaries**. Runner mode
must still hash the real nine executables. CI gates retain the original
proof order, including existing source-build and binary-hash verification.
The additional negative tests cannot authorize native cache restoration,
cross-platform ABI or full-toolchain provenance.
