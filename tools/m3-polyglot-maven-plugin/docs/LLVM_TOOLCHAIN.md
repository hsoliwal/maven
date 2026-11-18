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

For a shared fork installation, `-Dm3.llvm.home=<root>` or `M3_LLVM_HOME=<root>`
selects the installation root (containing `bin/`) for compiler tasks and the
existing Clang AST front end; individual tasks may override with
`--m3-llvm-home=<root>`. An explicit `--m3-llvm-bin` selector wins over that
global setting, but an explicit task home and bin cannot be used together.
`--m3-llvm-sha256=<64 hexadecimal digits>` verifies the selected executable's
real bytes before invocation, including when `clang++` is a symlink. This pin
requires a selected home or bin; the original PATH behavior remains unchanged
when neither is set. The provider never injects extra language flags.

With a global LLVM home, `mavenize` also selects its `clang` and `clang++` in
new CMake configuration tasks (`CMAKE_C_COMPILER`/`CMAKE_CXX_COMPILER`), so
subsequent `compile_commands.json` evidence comes from the same toolchain.
Already existing native descriptors and build owners are left untouched.

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
cmake --build build --target clang llc flang
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
- `tools/m3-llvm-tools-provider` is the separately installed SPI JAR for
  `opt`, `mlir-opt`, `mlir-translate`, `llvm-as`, `llvm-link`, LLD and LLVM
  test runners. It reuses this same Maven DAG and does not turn compiler
  tasks into an implicit pipeline.

The next stages should qualify Clang diagnostics, linker target ABIs,
MLIR dialects/passes, LLVM `lit`/FileCheck evidence, target triples/sysroots,
depfile import, module dependency graphs, and reproducible native artifact/toolchain
receipts. Those are **not implemented by this compiler provider**. OpenRewrite remains the
recipe mutation authority, and M3 AST atomization/patternization is a
separate contract-preserving pass.

## Qualification gates

The Java 17/21 offline contract suites verify ServiceLoader inventory,
compiler selection, both bin/home selectors, SHA pinning, invalid/duplicate
inputs, literal argv preservation, and side-effect-free incremental dry-run
planning **without assuming LLVM is installed**. The
`LlvmCompilerSelectionContractTestMain` covers fork-home, symlink, SHA and
CMake configuration contracts. The separately merged
`LlvmCompilerProviderContractTestMain` covers raw original flags, dry-run
purity and optional real native/cache-invalidating compilation.

To execute both native qualification gates, select an installed toolchain explicitly:

```sh
mvn -f tools/m3-polyglot-maven-plugin/pom.xml \
  -Dm3.polyglot.llvm.test.home=/opt/llvm verify
M3_POLYGLOT_LLVM_TEST_HOME=/opt/llvm M3_LLVM_REAL_TEST_HOME=/opt/llvm \
  sh tools/m3-polyglot-maven-plugin/scripts/verify.sh
```

The test-only `m3.polyglot.llvm.test.home` property (or
`M3_POLYGLOT_LLVM_TEST_HOME` for the standalone verifier) points to the
installation root containing `bin` and exercises the existing
`--m3-llvm-bin` selector. The separate `M3_LLVM_REAL_TEST_HOME` environment
selects the six-language, executable-digest-pinned Maven `TaskRunner` gate;
it also runs through Maven `verify` when configured. Neither test variable
changes the production provider's home/bin/PATH selection rules.

The legacy-bin native cases compile C, C++, Objective-C and Objective-C++ to object
files through `TaskRunner`, verify repeated execution even with incremental
state and artifact caching enabled, observe an included header changing
object bytes, and require a real invalid-C failure to reach the caller.
Optional LLVM IR and Fortran cases use the actual `llc` and `flang` drivers.
Missing optional tools are reported as `NOT_RUN`; a present but unusable
configured tool fails. If no test installation is selected, all native
cases report `NOT_RUN`. The separate strict six-language gate compiles C,
C++, Objective-C, Objective-C++, Fortran and LLVM IR to nonempty object files
with SHA-256 pins, and reports `NOT RUN` if not configured. Expected invalid-C
diagnostics are part of the passing failure-propagation case.

Passing these tests qualifies only the selected installed compiler and
host. It does not prove that the executable was built from the pinned
LLVM fork revision. Independent release qualification still includes the
Maven 17/21 CI matrix, Windows/macOS, cross-compilation, cancellation,
CMake/Ninja/CTest integration, and toolchain/source/ABI provenance checks.
Report each real-tool case from its actual result; planning a `flang` or
`llc` command does not count as executing that compiler.

References: [LLVM CMake](https://llvm.org/docs/CMake.html),
[LLVM getting started](https://llvm.org/docs/GettingStarted.html),
[Flang getting started](https://flang.llvm.org/docs/GettingStarted.html),
and [LLVM license](https://github.com/hsoliwal/llvm-project/blob/main/LICENSE.TXT).
