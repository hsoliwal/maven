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
| `objc++` | `clang++` | Additive alias; identical compiler and argument handling to `objcpp` |
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

### Required native CI verification

The Linux Java 17 and Java 21 matrix explicitly requires installed
`clang` and `clang++` and runs the existing
`LlvmCompilerIntegrationTestMain --required` native regression suite.
It then executes the packaged Maven plugin's separate `examples/llvm`
consumer (C17 compile, dependency file, native link and executable result)
against the runner's installed Clang. The normal contract suites continue
to report `NOT_RUN` for optional `llc` or Flang lanes when absent.

Passing those tests qualifies the **host compiler and Maven integration**,
not binaries built from the pinned `hsoliwal/llvm-project` revision. The
full LLVM fork source/build/ABI and Windows/macOS gates remain separate.
The additional `objc++` spelling does not replace or reinterpret
`objcpp`; both call the original `clang++` driver.

## Maven-owned Clang compilation database for clangd and CDT

The opt-in `llvm-compile-db` goal emits the standard `arguments` array
format from the existing M3 manifest and original
`M3ToolProviders.command` for Clang C/C++/Objective-C compile-only
tasks. CMake remains authoritative for its own
`build/cmake/compile_commands.json`; no CMake files or global `.clangd`
configuration are modified.

Run after installing the plugin in the Maven fork:

```sh
mvn -B -ntp -f tools/m3-polyglot-maven-plugin/examples/llvm/pom.xml \
  io.synexia:m3-polyglot-maven-plugin:0.1.0-SNAPSHOT:llvm-compile-db \
  -Dm3.llvm.home=/path/to/llvm-install
```

The module-owned output is `target/m3-llvm/compile_commands.json`.
Point clangd at `--compile-commands-dir=target/m3-llvm` (relative
to the module) or select this directory in the `.clangd`
`CompileFlags.CompilationDatabase` setting. The existing
`tools/code-clustering-clang` AST owner can consume it via
`clustering.clang.compileCommands`.

The export is limited to the requested M3 target closure and
materialized, declared translation units for C, C++, Objective-C,
and Objective-C++ (including `objcpp` and `objc++`). Each JSON
record preserves the original compiler executable/flags and absolute
working directory, never a reconstructed shell command. To avoid implying
complete compiler output ownership, an eligible task must have a real
`-c` compile mode, exactly one tracked source, an explicit `-o` matching
a declared M3 output, and any `-MF`, `-MJ` or
`-serialize-diagnostics` sidecars separately declared. `-MD`/
`-MMD` without an explicit `-MF`, dangling compiler options,
preprocess-only/syntax-only flags, and response-file arguments
(including those passed via `-Xclang`) are refused.
Link steps,
Flang and LLVM IR are not misidentified as source compiles. Untracked
or ambiguous source operands, missing sources, and response-file
operands are refused. The output is published atomically and
identical bytes preserve its timestamp; output symlinks are refused.

This is **IDE command metadata**, not evidence that a compiler ran,
that the installed tools came from the pinned LLVM source SHA,
or that any native output is cacheable. Complete header, negative
include, generated input, library/pass-plugin/sysroot, ABI and
source-provenance evidence remain independent native build gates.

### Portable CLI for Gradle and Eclipse

The same Maven-owned exporter is also exposed through the original
`PolyglotCli` bridge, **without launching a Maven goal**:

```sh
M3_LLVM_HOME=/path/to/llvm-install java \
  -cp /path/to/m3-polyglot-maven-plugin-0.1.0-SNAPSHOT.jar \
  io.synexia.maven.polyglot.PolyglotCli \
  llvm-compile-db /work/module /work/module/m3-build.tsv all
```

The command returns the two-row, tab-delimited protocol:

```text
M3-LLVM-COMPILE-DB-V1
file<TAB>/work/module/target/m3-llvm/compile_commands.json
```

Gradle and Eclipse invoke **this original Maven implementation**, not
a copied JSON formatter or another task scheduler. Only the selected
target closure is projected. The CLI refuses unrelated `plan`/`run`
flags (`--incremental`, `--provider-reuse`, `--build-cache`) and
validates workspace paths before output creation. Repeating the same
export preserves output timestamps; the portable command performs no
native compiler execution and does not open M3DB or alter M3DB
receipts. The binary provider resolution and compilation database
constraints remain exactly those of the Maven goal.

## Qualification gates

The Java 17/21 offline contract suites verify ServiceLoader inventory,
compiler selection, both bin/home selectors, SHA pinning, invalid/duplicate
inputs, literal argv preservation, and side-effect-free incremental dry-run
planning **without assuming LLVM is installed**. The
`LlvmCompilerSelectionContractTestMain` covers fork-home, symlink, SHA and
CMake configuration contracts. The separately merged
`LlvmCompilerProviderContractTestMain` covers raw original flags, dry-run
purity and optional real native/cache-invalidating compilation.

To execute all three native qualification gates, select an installed toolchain explicitly:

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

The additional `LlvmCompilerIntegrationTestMain` uses that test installation
to exercise real C17/C++20 **compile, link and run** through the Maven DAG,
two separately configured outputs, Clang depfiles, header changes, a newly
appearing `__has_include` header absent from the prior depfile, deleted output
recovery, native compiler failure/recovery, and provider cache exclusion.
It checks a task working directory and paths containing spaces. When `llc`
is installed, a two-task `llvm-ir -> llc object -> clang link` plan runs the
resulting executable; it never substitutes Clang's `-x ir` for the merged
`llvm-ir -> llc` contract. Objective-C/Objective-C++ compilation and optional
Flang execution are also exercised. Use `--required` when invoking this
test's main class directly to refuse absent host Clang/Clang++; by default an
unconfigured host without these tools reports `NOT_RUN`.

See the [packaged Maven example](../examples/llvm/README.md) for an explicit
compile/link task graph and a header/depfile pair, without any automatic
LLVM installation or lifecycle binding.

CI now requires a separate **six-language object-compilation gate** on both Java
17 and 21. Ubuntu 24.04 installs the versioned distribution packages
`llvm-18`, `clang-18`, and `flang-18`. With
`M3_LLVM_REAL_TEST_HOME=/usr/lib/llvm-18`, the existing
`LlvmRealTestsMain` must compile six actual nonempty object files:
C, C++, Objective-C, Objective-C++, Fortran (using `flang` or `flang-new`),
and LLVM IR (through original `llc`). Absent tools, compilation failures,
or a missing final PASS line fail the workflow rather than reporting a
successful optional skip. Each Java lane retains native driver/package SHA-256
and the full test result as an Actions artifact. These system-installed
Ubuntu binaries have **not** been built from the audited
`hsoliwal/llvm-project` revision; the latter remains a separate release gate.

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
and [LLVM license](https://github.com/hsoliwal/llvm-project/blob/591f3d1edf0a9ee718d17a0ee70dd013b3c01908/LICENSE.TXT).

## Selection edge cases and planning qualification

The adapter consumes its bin, home and digest directives at every argument
position, including after `--`; the delimiter and other compiler arguments
retain their original order. Relative home/bin paths use the module workspace,
while relative compiler arguments use the task's working directory.

Home-based Fortran selection tries `bin/flang`, then `bin/flang-new` when
`flang` is not a regular file. Explicit bin and unconfigured PATH selection
keep the original `flang` spelling. Unpinned legacy bin and default PATH
commands permit planning before the tools are installed; a digest requires a
selected directory and locally available executable bytes.

`LlvmInstallationSelectionContractTestMain` supplements the existing suites
with property/environment precedence, all-position directives, directory-required
digest pins, legacy planning, symlink and Flang-fallback checks. Its driver and
literal-argument checks cover both admitted Objective-C++ spellings. These
planning checks execute no compiler and add no production selection rule.

## Depfile and compilation-database evidence

The packaged consumer declares the output produced by `-MD -MF <file>`. The
runner checks that declared output exists; it does not parse the depfile to skip
compilation. A new header earlier in an include-search path, or a changed
`__has_include` result, can affect compilation without changing a file listed in
the old depfile. Provider tasks and their unverified dependents therefore retain
the current execution policy.

For C++ modules, the pinned
[module documentation](https://github.com/hsoliwal/llvm-project/blob/591f3d1edf0a9ee718d17a0ee70dd013b3c01908/clang/docs/StandardCPlusPlusModules.md)
describes P1689 module ordering separately from companion `-MD -MF` header
evidence. P1689 scanning needs the compilation database's `output` field. These
consumer tasks do not add scanner scheduling or native cache eligibility.

Individual Clang tasks may pass `-MJ <fragment-path>` to emit compilation-database
fragments, with a distinct declared output for each task. Assemble the objects
into a valid JSON array and remove the final separator before its closing
bracket; the compiler provider does not perform that aggregation. The pinned
[database contract](https://github.com/hsoliwal/llvm-project/blob/591f3d1edf0a9ee718d17a0ee70dd013b3c01908/clang/docs/JSONCompilationDatabase.md)
preserves working directory, source, original argv and multiple configurations
per source. Prefer unescaped JSON `arguments`; do not split shell-escaped command
strings on spaces.

After configuring the existing Clang clustering plugin, use the shared toolchain
and the original native compilation database:

```sh
mvn code-clustering:cluster \
    -Dm3.llvm.home=/work/llvm-install \
    -Dclustering.sourceDirectories=src/main/c,src/main/cpp \
    -Dclustering.clang.compileCommands=build/cmake/compile_commands.json
```

An explicit `clustering.clang.executable` or `CLANG` setting takes precedence for
the front end; choose it consistently with the compilation database. The native
build continues to own that database and the existing Clang front end owns AST
evidence. An executable digest alone does not identify Clang's resource directory,
shared libraries, linkers, runtimes, SDKs, sysroots or plugins; those inputs need
their own toolchain evidence.
