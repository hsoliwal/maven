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
| `objc++` | `clang++` | Additional spelling for the existing `objcpp` driver |
| `fortran` | `flang` | LLVM Flang (including separately built Flang-RT when linking) |
| `llvm-ir` | `llc` | LLVM IR code generation |

The language ID chooses a driver; the caller still supplies all flags.
In particular, pass `-x objective-c` or `-x objective-c++` when file
extensions alone do not select the intended Objective-C mode. This provider
does not claim to implement a Fortran or C++ standard library, or to infer
any source/header/module graph.

With no installation or digest configured, the driver is resolved from the
process `PATH` when it executes. This preserves planning without LLVM installed.
A task can set
`arg<TAB>name<TAB>--m3-llvm-bin=<directory>` **once** to choose an explicit
toolchain's `bin` directory. Relative directories are resolved against the
manifest's module workspace; absolute directories are accepted (including
Windows paths with spaces). This selector is removed before calling LLVM.
An empty or duplicate selector fails planning. On Windows the selected
compiler name ends in `.exe`. No path guessing or fallback to another
installation happens when an explicit toolchain is selected.

The original bin selector remains accepted at any argument position, including
after `--`. The additive home and digest options below are recognized only before
the first `--`; otherwise the delimiter and subsequent arguments remain literal.

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
dry-run planning **without assuming LLVM is installed**. The additive
`LlvmCompilerProviderContractTestMain` also checks literal argv preservation
and that incremental dry-run planning creates no output or state.

To execute its native cases, select an installed toolchain explicitly:

```sh
mvn -f tools/m3-polyglot-maven-plugin/pom.xml \
  -Dm3.polyglot.llvm.test.home=/opt/llvm verify
M3_POLYGLOT_LLVM_TEST_HOME=/opt/llvm \
  sh tools/m3-polyglot-maven-plugin/scripts/verify.sh
```

This **test-only** setting points to an installation root containing `bin`.
The test passes the existing `--m3-llvm-bin` task selector. Its setting is
separate from the production `m3.polyglot.llvm.home` selection described below;
without a production installation or digest selection, the provider retains its
PATH default.

The native cases compile C, C++, Objective-C and Objective-C++ to object
files through `TaskRunner`, verify repeated execution even with incremental
state and artifact caching enabled, observe an included header changing
object bytes, and require a real invalid-C failure to reach the caller.
Optional LLVM IR and Fortran cases use the actual `llc` and `flang` drivers.
Missing optional tools are reported as `NOT_RUN`; a present but unusable
configured tool fails. If no test installation is selected, all native
cases report `NOT_RUN`. Expected invalid-source compiler diagnostics are
part of the passing failure-propagation case.

Passing these tests qualifies only the selected installed compiler and
host. It does not prove that the executable was built from the pinned
LLVM fork revision. Independent release qualification still includes the
Maven 17/21 CI matrix, Windows/macOS, cross-compilation, cancellation,
CMake/Ninja/CTest integration, and toolchain/source/ABI provenance checks.
Report each real-tool case from its actual result; planning a `flang` or
`llc` command does not count as executing that compiler.


`LlvmInstallationSelectionContractTestMain` separately checks the installation
home, executable digest, selector precedence and compatibility contracts without
executing a compiler. `LlvmCompilerIntegrationTestMain` retains the additional
installed-toolchain integration cases.

References: [LLVM CMake](https://llvm.org/docs/CMake.html),
[LLVM getting started](https://llvm.org/docs/GettingStarted.html),
[Flang getting started](https://flang.llvm.org/docs/GettingStarted.html),
and [LLVM license](https://github.com/hsoliwal/llvm-project/blob/591f3d1edf0a9ee718d17a0ee70dd013b3c01908/LICENSE.TXT).

## Installation homes and executable digests

The existing `--m3-llvm-bin=<directory>` contract remains available for LLVM
build trees and other explicitly selected bin folders. This extension also
accepts `--m3-llvm-home=<installation>`, selecting `<installation>/bin/<driver>`.
Selection uses the following order:

1. A task's explicit `--m3-llvm-bin=<directory>` or
   `--m3-llvm-home=<installation>`. Supplying both task selectors is refused.
2. The Java system property `m3.polyglot.llvm.home`.
3. The environment variable `LLVM_HOME`.
4. The original unconfigured driver name, resolved through process `PATH` when
   execution begins.

An explicit bin selection takes precedence over the implicit home property or
environment. Empty property/environment values are treated as unconfigured.
Relative bin and home values resolve against the Maven module workspace;
relative compiler arguments resolve against the task's working directory.
Use absolute installation paths for easily reviewed configurations.

A home selection requires the chosen executable to exist and to be executable
on POSIX. A configured home that cannot supply the driver fails instead of
falling back to another installation. Ordinary executable symlinks are accepted,
and `clang++` retains its invocation spelling so its original C++ driver mode
survives. Flang selection uses `flang`, with no fallback to `flang-new`.

An optional task argument `--m3-llvm-sha256=<64 hexadecimal characters>` checks
the selected executable's SHA-256. It can accompany either task selector. When
used without a home or bin selection, it resolves the executable through the
existing runner's task-working-directory/PATH lookup and records that absolute
command for execution. A pin therefore requires a local executable even during
planning. An unpinned legacy bin selection still permits planning before that
tool is installed. Uppercase and lowercase hexadecimal digests are accepted.

Both new options use `--name=value` and are removed from the compiler argv.
Duplicate metadata, malformed values, unknown LLVM metadata before `--`,
conflicting task selectors and digest mismatches fail. Compiler arguments retain
their original order and boundaries. The provider adds no language, optimization,
target, output or linking flags.

**An executable digest does not identify the whole toolchain.** Shared libraries,
Clang's resource directory, scanners, linkers, runtimes, sysroots, SDKs, plugins
and the environment need their own provenance and qualification. The recorded
LLVM source revision also does not prove the origin of a particular installed
binary. This provider adds no toolchain download or automatic upgrade.

From the Maven fork, install the plugin:

```sh
mvn -f tools/m3-polyglot-maven-plugin/pom.xml install
```

In a consuming module with its own `m3-build.tsv`, plan or execute with an explicit
installation home:

```sh
mvn io.synexia:m3-polyglot-maven-plugin:0.1.0-SNAPSHOT:plan \
    -Dm3.polyglot.llvm.home=/work/llvm-install
mvn io.synexia:m3-polyglot-maven-plugin:0.1.0-SNAPSHOT:run \
    -Dm3.polyglot.llvm.home=/work/llvm-install
```

The default manifest is the module's `m3-build.tsv`, and the default target is
`all`. `-Dm3.polyglot.manifest=<path>` and `-Dm3.polyglot.target=<name>` select
another manifest or target. The additive runnable Maven consumer in
`examples/llvm` complements the preserved two-stage IR example in
`examples/llvm-native`.

## Preserve Clang header evidence during compilation

The following TSV example compiles and links a C entry point. Separators are
literal tabs. Supply `src/main/c/hello.c` and create `target/llvm` before running,
or declare an explicit directory-preparation task. Output-directory creation
remains the consuming build's responsibility.

```text
M3-POLYGLOT-TASKS-V1
task	all	-	.
dep	all	native-link
task	native-compile	@m3:llvm:compiler:c	.
arg	native-compile	-c
arg	native-compile	src/main/c/hello.c
arg	native-compile	-MD
arg	native-compile	-MF
arg	native-compile	target/llvm/hello.d
arg	native-compile	-o
arg	native-compile	target/llvm/hello.o
input	native-compile	src/main/c/hello.c
output	native-compile	target/llvm/hello.o
output	native-compile	target/llvm/hello.d
task	native-link	@m3:llvm:compiler:c	.
dep	native-link	native-compile
arg	native-link	target/llvm/hello.o
arg	native-link	-o
arg	native-link	target/llvm/hello
input	native-link	target/llvm/hello.o
output	native-link	target/llvm/hello
```

Choose the output filename, target triple, sysroot, runtime and library flags for
the target platform; a Windows executable normally uses `.exe`. Declare project
headers and generated files as inputs too. A prerequisite can create generated
headers before compilation. Compiler failures, timeouts and missing declared
outputs fail the existing task runner.

`-MD -MF <file>` tells Clang to emit header dependency evidence. The runner checks
the declared file exists but does not parse it to skip compilation. LLVM provider
tasks always execute, including when incremental reuse or output caching is
enabled. They do not restore cached outputs or publish reusable provider
generations. Unverified dependents also execute. Existing M3DB storage, leases,
revocation and ordinary-tool cache eligibility retain their original ownership.

A new header earlier in an include-search path or a changed `__has_include`
result can affect compilation without changing the old depfile's listed inputs.
For C++ modules, the pinned
[module documentation](https://github.com/hsoliwal/llvm-project/blob/591f3d1edf0a9ee718d17a0ee70dd013b3c01908/clang/docs/StandardCPlusPlusModules.md)
describes P1689 module ordering separately from companion `-MD -MF` header
evidence. P1689 scanning needs the compilation database's `output` field. Neither
scanner scheduling nor inferred native cache eligibility is added here.

## Feed the existing Clang atomizer

The existing [Clang code-clustering front end](../../code-clustering-clang/README.md)
owns C-family syntax-tree atoms. Continue obtaining `compile_commands.json` from
the original native build, for example CMake with
`CMAKE_EXPORT_COMPILE_COMMANDS=ON` on a supported generator.

Individual Clang tasks may pass `-MJ <fragment-path>` to emit compilation database
fragments. Use a distinct declared output for each task. A fragment is not a
complete database: assemble the objects into a valid JSON array and remove the
final separator before its closing bracket. The compiler provider does not
perform that aggregation. The pinned
[database contract](https://github.com/hsoliwal/llvm-project/blob/591f3d1edf0a9ee718d17a0ee70dd013b3c01908/clang/docs/JSONCompilationDatabase.md)
preserves working directory, source, original argv and multiple configurations
per source. Prefer unescaped JSON `arguments`; do not split shell-escaped command
strings on spaces.

After configuring the clustering plugin and its existing Clang dependency, point
them at the same installation and native compilation database:

```sh
mvn code-clustering:cluster \
    -Dclustering.sourceDirectories=src/main/c,src/main/cpp \
    -Dclustering.clang.executable=/work/llvm-install/bin/clang \
    -Dclustering.clang.compileCommands=build/cmake/compile_commands.json
```

`m3.polyglot.llvm.home` does not rewrite the clustering front end's independent
settings. Its existing lexical fallback and outcome reporting remain applicable.
Java keeps its JDK/ECJ providers. `llvm-ar`, `clang-scan-deps`, `mlir-opt`, `opt`,
`llc`, coverage and test tools can also remain ordinary explicit native tasks.

The FOSS decision ledger records external command reuse with no LLVM source
copying. Tool-specific license notices, including identified third-party terms,
remain relevant when distributing a separate toolchain. A passing host run does
not establish all Flang, Windows, Android, Apple SDK, cross-target, full LLVM-build
or full Maven-reactor qualification.
