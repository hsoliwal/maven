<!--
SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# LLVM fork as an M3 Maven compiler/toolchain donor

**Status: experimental, opt-in compiler command adapter.** This document describes
the first integration with [hsoliwal/llvm-project](https://github.com/hsoliwal/llvm-project)
and the boundaries for subsequent LLVM/MLIR/LLD work. LLVM source snapshot
inspected 2026-11-18: `591f3d1edf0a9ee718d17a0ee70dd013b3c01908`
(`main` at inspection, not a floating runtime dependency).

Nothing in Maven Core, its standard lifecycle, Maven's public API, or the
upstream LLVM/CMake/Ninja build graph is replaced. No LLVM source code,
libraries, binaries, downloads or automatic toolchain installation are bundled.
LLVM's own code is Apache-2.0 WITH LLVM-exception; this new Java adapter is
independently authored Apache-2.0 code with no copied LLVM implementation.

## What is implemented

The existing `M3ToolProvider` SPI offers `@m3:llvm:compiler:<language>`.
The tool is the **original LLVM executable**, with argument-vector execution
(no shell). The provider has no automatic lifecycle phase; add a manifest task
or explicitly bind the normal M3 polyglot lifecycle as usual.

| Language ID | Original driver | Source/output ownership |
| --- | --- | --- |
| `c` | `clang` | Clang |
| `cpp` | `clang++` | Clang |
| `objc` | `clang` | Clang, with the caller's source-language flags |
| `objcpp` | `clang++` | Clang, with the caller's source-language flags |
| `fortran` | `flang` | LLVM Flang (including separately built Flang-RT when linking) |
| `llvm-ir` | `llc` | LLVM IR code generation |

The language ID chooses a driver; the caller still supplies all flags.
In particular, pass `-x objective-c` or `-x objective-c++` when file
extensions alone do not select the intended Objective-C mode. This provider
does not claim to implement a Fortran or C++ standard library, or to infer
any source/header/module graph.

By default the driver is resolved from the process `PATH`. A task can set
`arg<TAB>name<TAB>--m3-llvm-bin=<directory>` **once** to choose an explicit
toolchain's `bin` directory. Relative directories are resolved against the
manifest's module workspace; absolute directories are accepted (including
Windows paths with spaces). This selector is removed before calling LLVM.
An empty or duplicate selector fails planning. On Windows the selected
compiler name ends in `.exe`. No path guessing or fallback to another
installation happens when an explicit toolchain is selected.

The existing M3 provider policy refuses undeclared inputs/outputs and
overlapping source/output paths, validates argv, and does not classify
provider-owned compiler work as incremental or output-cache reusable.
Do **not** treat this provider's mere presence as complete dependency evidence:
transitive `#include` files, Fortran modules, sysroots, runtime/linker inputs,
response files and generated headers must be qualified before caching can be
considered. LLVM's own CMake/Ninja/depfile engine remains authoritative for
its native incremental compilation.

## A simple two-stage LLVM IR build

`examples/llvm-native/m3-build.tsv` contains two ordered tasks:

1. `compile`: `clang -S -emit-llvm hello.c -o hello.ll`;
2. `codegen`: `llc -filetype=asm -o hello.s hello.ll`.

Copy the example to a writable workspace with Clang and `llc` installed,
then plan without executing either tool:

```sh
java -cp /verified/m3-polyglot-maven-plugin.jar \
  io.synexia.maven.polyglot.PolyglotCli \
  plan /work/llvm-native /work/llvm-native/m3-build.tsv codegen
```

Execute only when the installed LLVM toolchain is intended:

```sh
java -cp /verified/m3-polyglot-maven-plugin.jar \
  io.synexia.maven.polyglot.PolyglotCli \
  run /work/llvm-native /work/llvm-native/m3-build.tsv codegen
```

For an isolated build of your LLVM fork, first build the chosen tools in
the original repository using CMake/Ninja (outside the Maven plugin):

```sh
git clone https://github.com/hsoliwal/llvm-project.git
cd llvm-project
git checkout 591f3d1edf0a9ee718d17a0ee70dd013b3c01908
cmake -S llvm -B build -G Ninja \
  -DLLVM_ENABLE_PROJECTS="clang;flang;lld;mlir" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \
  -DLLVM_PARALLEL_LINK_JOBS=2
cmake --build build --target clang clang++ llc flang
```

The exact target set depends on the selected LLVM build and host. Configure
all required runtimes separately (especially Flang-RT for linking Fortran).
Set `--m3-llvm-bin=<absolute path to llvm-project/build/bin>` on **each**
manifest compiler task when not using `PATH`. This option is an explicit
argument, not a global Maven installation setting.

## Existing Maven owners to reuse

- `tools/m3-polyglot-maven-plugin` remains the Maven/Gradle-compatible DAG
  runner and M3DB receipt owner.
- `tools/code-clustering-clang` already consumes `compile_commands.json`
  and Clang JSON AST dumps. Do not duplicate it with a second native parser.
- `mavenize` already handles `CMakeLists.txt`, CMake's
  `compile_commands.json`, CTest and `.clangd` for CDT/clangd consumers.
- `docs/M3-EXTERNAL-BUILD-BRIDGE.md` retains the original external
  build-owner delta/reconciliation contract. IDE input is advisory, not
  authorization to skip the native compiler.

The next stages should add explicit, separately tested tooling for Clang
diagnostics, LLD linking, `opt` pass pipelines, MLIR transforms, LLVM
`lit`/FileCheck evidence, target triples/sysroots, depfile import,
module dependency graphs, and reproducible native artifact/toolchain receipts.
They are **not implemented by this provider**. OpenRewrite remains the
recipe mutation authority, and M3 AST atomization/patternization is a
separate contract-preserving pass.

## Qualification gates

The Java 17 contract suite verifies ServiceLoader inventory, command
selection, selector isolation, invalid/duplicate inputs, and side-effect-free
dry-run planning **without assuming LLVM is installed**. Independent release
qualification still requires the Maven 17/21 CI matrix, real Clang/Clang++
C/C++ and Objective-C fixtures, Flang, `llc`, Windows paths, cross-compilation,
exit codes/cancellation, diagnostics, CMake/Ninja/CTest integration,
and toolchain/source/ABI provenance differential checks. Do not report
those real-tool checks as passed until they execute.

References: [LLVM CMake](https://llvm.org/docs/CMake.html),
[LLVM getting started](https://llvm.org/docs/GettingStarted.html),
[Flang getting started](https://flang.llvm.org/docs/GettingStarted.html),
and [LLVM license](https://github.com/hsoliwal/llvm-project/blob/main/LICENSE.TXT).
