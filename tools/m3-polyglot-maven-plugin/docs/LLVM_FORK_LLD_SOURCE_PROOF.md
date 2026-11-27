<!-- SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Pinned LLVM LLD source-built Wasm/COFF qualification

Status: **additive draft; native success must be established by the exact-head CI run**.
Maven receiving base: `6efb7b09d35b377f817f6da156efef47a915d4de`.
Source donor: `hsoliwal/llvm-project@591f3d1edf0a9ee718d17a0ee70dd013b3c01908`.

## Existing owners and immutable contracts

`tools/m3-polyglot-maven-plugin` owns task graph ordering, original
`LlvmCompilerProvider` dispatch, module receipts and output cache. The optional
`tools/m3-llvm-tools-provider` owns the original LLVM tool selections.
Its existing `LlvmWasmCoffIntegrationTestMain --required` checks a real
five-node Clang/Wasm and Clang/PE-COFF graph, native object and linker products,
mutation/failure/recovery and unconditional native execution with cache opt-ins.
Do not replace these owners or change the `M3ToolProvider` SPI.

## Donor source facts

At the pinned LLVM fork revision, `lld/tools/lld/CMakeLists.txt` creates
original target `lld` and symlinks `ld.lld`, `lld-link`, `ld64.lld` and
`wasm-ld`. The donor LLD source includes ELF, COFF, and Wasm drivers.
The original source build only enables the X86 target back end, whereas
compiling the freestanding Wasm C fixture requires the LLVM WebAssembly backend.

## Required additive source-built gate

1. Extend the **same** sparse checkout with `lld`, require its original
   `CMakeLists.txt` and `tools/lld/CMakeLists.txt` during clean, exact
   source admission before creating build/evidence output directories.
2. Preserve LLVM X86/Clang and `CLANG_ENABLE_CIR=OFF`; add
   `LLVM_ENABLE_PROJECTS=clang;lld` and
   `LLVM_TARGETS_TO_BUILD=X86;WebAssembly`. Build one original `lld`
   target alongside `clang`, `llc`, `opt`, `llvm-as`, `llvm-link`.
   Assert `ld.lld`, `wasm-ld`, `lld-link` are executable and record actual
   SHA-256s of **nine** named LLVM executables in the original binary receipt.
3. After the existing Clang and LLVM IR JDK 21 receipts, compile the unchanged
   optional provider and `LlvmWasmCoffIntegrationTestMain` with Java 17
   release, original Maven classes and `-Xlint:all -Werror`. Execute the
   existing native test under **JDK 21**, explicitly selecting the built
   LLVM installation; require the complete real test PASS signature.
4. After existing JDK 17 Clang and LLVM IR receipts, execute that same test
   under **JDK 17**, reusing the **same** original LLVM binaries.
5. The standalone new LLD verifier checks clean exact source, required
   predecessor receipts, unmodified original nine-binary SHA-256 evidence,
   original provider ServiceLoader entry, active JDK/Maven version, and
   before/after plus JDK 21-to-17 hash transcripts. Append
   `source_built_llvm_lld_wasm_coff_native_jdk21<TAB>PASS` or
   `source_built_llvm_lld_wasm_coff_native_jdk17<TAB>PASS` **only** after
   actual test exit 0 and all signature/identity checks succeed.
6. Isolate temporary test classes under `RUNNER_TEMP`, remove them on exit.
   Existing evidence archive retains only logs, SHA-256 manifests, and
   receipts. Do not ship or silently promote LLVM binaries.
7. Extend the existing strict source-proof PR/push trigger lists and required
   source-owner registry for this document, the new helper and its tests, and
   the original Wasm/COFF native regression. Maintain symmetric exact triggers
   and execute the new negative tests **before LLVM fetch**.

## Qualification scope and refusal

The test validates LLVM Wasm and PE/COFF **freestanding output formats** on a
Linux X86 runner, not Windows execution, browser/WebAssembly execution,
cross-platform runtime ABIs, target SDK/runtime linkage, or reproducible
whole-toolchain closure. It does not establish source-built Flang, MLIR,
or arbitrary LLVM native artifact-cache eligibility. LLD/Clang sources
remain Apache-2.0 WITH LLVM-exception; no donor C++ code is copied.
A queued, cancelled, failed, or absent exact-head run is **not success**.
