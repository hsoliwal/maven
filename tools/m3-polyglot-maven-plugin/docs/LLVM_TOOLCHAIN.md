<!--
SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# LLVM compiler tools in Maven

The built-in `llvm` provider connects the existing polyglot task graph to an
installed LLVM compiler driver. Select it with
`@m3:llvm:compiler:<language>` in `m3-build.tsv`. Maven keeps its existing task
planning, process execution, timeout, failure and declared-output checks; the
selected LLVM driver compiles and links with the arguments supplied by the build.

The source reference is the
[hsoliwal/llvm-project fork at `591f3d1edf0a9ee718d17a0ee70dd013b3c01908`](https://github.com/hsoliwal/llvm-project/tree/591f3d1edf0a9ee718d17a0ee70dd013b3c01908).
The provider adds Java command selection and validation. It imports no LLVM
implementation, bundles no compiler binary and downloads no toolchain.

## Supported commands

| Language identifier | Selected driver | Input selected by the build |
| --- | --- | --- |
| `c` | `clang` | C sources and ordinary Clang driver options |
| `cpp` | `clang++` | C++ sources and ordinary Clang C++ driver options |
| `objc` | `clang` | Objective-C sources, SDK and runtime options |
| `objc++` | `clang++` | Objective-C++ sources, SDK and runtime options |
| `fortran` | `flang` | Fortran sources and options supported by the installed Flang |
| `llvm-ir` | `clang` | LLVM IR or bitcode accepted by the installed Clang |

Only the `compiler` capability is registered. The language identifier chooses
the driver; it does not add `-x`, `-c`, optimization, target or linker flags.
Source extensions and explicit flags retain the compiler's original meaning.
For example, a link task can select `compiler:cpp` and pass object files to
`clang++`, which retains responsibility for its C++ runtime and linker choices.
Other languages and capabilities are refused by the existing provider registry.

The provider uses `flang`, with no fallback to `flang-new`. Choose an installation
that supplies the required executable and language features. Support for an
identifier does not certify every version of that compiler or every target SDK.

## Select and optionally pin the installation

LLVM home selection uses this precedence:

1. A task argument `--m3-llvm-home=<installation>`.
2. The Java system property `m3.polyglot.llvm.home`.
3. The environment variable `LLVM_HOME`.
4. If no home is configured, the existing runner's executable lookup in the
   task working directory and then `PATH`.

With an explicit home, the executable is `<installation>/bin/<driver>`
(`<driver>.exe` on Windows). A configured installation must supply that driver;
an invalid selection fails instead of silently choosing a different installation.
The selected file must exist, and must be executable on POSIX. Ordinary
executable symlinks are accepted. A `clang++` symlink retains its invocation name
so that the compiler can select its original C++ driver behavior.

Relative LLVM home values resolve against the Maven module workspace. Relative
compiler arguments resolve against the task's working directory.

Use an absolute home path for a portable, reviewable task configuration. For
example, after installing this Maven plugin:

```sh
mvn io.synexia:m3-polyglot-maven-plugin:0.1.0-SNAPSHOT:plan \
    -Dm3.polyglot.llvm.home=/work/llvm-install
mvn io.synexia:m3-polyglot-maven-plugin:0.1.0-SNAPSHOT:run \
    -Dm3.polyglot.llvm.home=/work/llvm-install
```

The default manifest is the consuming module's `m3-build.tsv`, and the default
target is `all`. Use `-Dm3.polyglot.manifest=<path>` and
`-Dm3.polyglot.target=<name>` for another manifest or target. Install the plugin
from the Maven fork with:

```sh
mvn -f tools/m3-polyglot-maven-plugin/pom.xml install
```

An optional task argument `--m3-llvm-sha256=<64 hexadecimal characters>` verifies
the SHA-256 of the selected executable's bytes. Calculate the digest from the
actual chosen binary, including the target of an executable symlink, and record
it with the build configuration. The source revision above identifies the donor
reference; it does not by itself prove the provenance of an installed binary.

Both provider arguments use the `--name=value` form and are removed before
invocation. They are recognized only before the first `--`. The `--` delimiter
and every subsequent argument pass through unchanged. All other compiler
arguments retain their original order and boundaries. Duplicate metadata,
malformed values, unknown LLVM metadata and digest mismatches are refused.
Planning validates command selection, including a supplied digest, without
launching the compiler.

**The optional digest covers the selected executable only.** It does not pin
shared libraries loaded by the driver, Clang's resource directory, the scanner,
linker, runtimes, sysroot, SDK, plugins or environment. A source-built release
should separately record and qualify its full installation and target inputs.

## A C compile and link manifest

The following is a TSV manifest: the separators between fields are literal tabs.
Supply `src/main/c/hello.c` containing the project's entry point and create the
`target/llvm` output directory before execution, or declare an explicit directory
preparation task. The provider does not create compiler output directories.

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

The example assumes a host toolchain that can link this executable. Choose the
appropriate output filename, target triple, sysroot, libraries and other driver
flags for the target platform. A Windows executable normally uses `.exe`.
Headers, generated sources and other project inputs should also be declared.
A prerequisite can generate headers before the compiler task. Compiler failures,
timeouts and missing declared outputs fail the existing task runner.

Add per-task installation metadata as ordinary `arg` rows when a task needs a
different home from the module-wide selection:

```text
arg	native-compile	--m3-llvm-home=/work/llvm-install
arg	native-link	--m3-llvm-home=/work/llvm-install
```

Apply an executable digest independently to each task that needs it. Two drivers
in one installation can have different bytes; a digest for `clang` cannot be
assumed to identify `flang`.

## Dependency evidence and the module database

`-MD -MF <file>` asks Clang to preserve a dependency file alongside an object.
The provider forwards these options and the runner checks the declared depfile's
existence. **This integration does not parse depfiles to authorize skipping.**

LLVM provider tasks retain the existing rule for all `@m3` tasks: every selected
task executes, even when module incremental reuse or the artifact output cache
is enabled. They neither restore cached outputs nor publish reusable provider
success evidence. Dependents whose prerequisite evidence is unverified also
execute. The existing module database facade, storage choices, lifetime lease,
receipt revocation and ordinary-tool caching rules retain their ownership.

This behavior matters because a native compilation can read system headers,
generated files, response files, module files and compiler resources outside the
declared source list. Adding a new header earlier in a search path or changing an
`__has_include` result can also change the compilation without changing the
previous depfile's listed inputs. An executable digest and a successful compiler
exit alone are insufficient evidence for native build reuse.

For future scanner integration, preserve the distinction between header reads
and C++ module ordering. The pinned
[standard modules documentation](https://github.com/hsoliwal/llvm-project/blob/591f3d1edf0a9ee718d17a0ee70dd013b3c01908/clang/docs/StandardCPlusPlusModules.md)
describes `clang-scan-deps -format=p1689` for module dependencies, with a companion
`-MD -MF` depfile for headers. P1689 scanning requires an `output` entry in the
compilation database. Scanner output is evidence to inspect; this provider adds
no scanner scheduler or inferred cache eligibility.

## Feed the existing Clang atomizer

The fork already owns a
[Clang front end for code clustering](../../code-clustering-clang/README.md).
It reads per-file compilation flags from `compile_commands.json` and obtains
syntax-tree atoms from Clang. Keep that existing owner for C-family atomization.

For CMake projects, continue using the project's CMake/Ninja configuration with
`CMAKE_EXPORT_COMPILE_COMMANDS=ON` on a generator that supports it. For individual
Clang driver tasks, explicit `-MJ <fragment-path>` arguments produce compilation
database fragments. Declare each fragment as an output and write each task to a
distinct path. A `-MJ` fragment is not by itself a complete compilation database:
assemble the JSON objects into a valid JSON array, removing the trailing separator
before its closing bracket. This provider does not perform that aggregation.

The pinned
[compilation database contract](https://github.com/hsoliwal/llvm-project/blob/591f3d1edf0a9ee718d17a0ee70dd013b3c01908/clang/docs/JSONCompilationDatabase.md)
uses a command's working directory, source file and original argument vector.
Prefer unescaped JSON `arguments` arrays, preserve multiple configurations of the
same source, and retain `output` when available. Do not reconstruct a database by
splitting a shell-escaped command on spaces.

Once the existing clustering plugin and its Clang dependency are configured as
documented there, select the same installation and generated database explicitly:

```sh
mvn code-clustering:cluster \
    -Dclustering.sourceDirectories=src/main/c,src/main/cpp \
    -Dclustering.clang.executable=/work/llvm-install/bin/clang \
    -Dclustering.clang.compileCommands=build/cmake/compile_commands.json
```

`m3.polyglot.llvm.home` configures this compiler provider; it does not silently
rewrite the clustering front end's own settings. The existing lexical fallback
and outcome reporting still apply when that front end cannot parse a file.

## Build the selected LLVM source when needed

An existing qualified installation is sufficient. To build the referenced fork,
check out the exact source revision and preserve LLVM's original CMake build and
Ninja execution. For example, with the source already checked out at the pinned
revision, a separate build directory and explicit install prefix can be
configured as follows:

```sh
cmake -S /work/llvm-project/llvm -B /work/llvm-build -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX=/work/llvm-install \
    '-DLLVM_ENABLE_PROJECTS=clang;clang-tools-extra;lld'
cmake --build /work/llvm-build
cmake --install /work/llvm-build
```

These are optional configuration instructions, not evidence that this repository
built or qualified that toolchain. Select the projects, targets, runtimes and
host prerequisites required by the consuming build. Fortran needs a compatible
Flang installation and its MLIR/runtime dependencies; the example above does not
build Flang. Cross-compilation also requires the target headers, libraries,
sysroot and platform tools described by LLVM's
[cross-compilation documentation](https://github.com/hsoliwal/llvm-project/blob/591f3d1edf0a9ee718d17a0ee70dd013b3c01908/clang/docs/CrossCompilation.md).

Maven can retain CMake/Ninja, `llvm-ar`, `clang-scan-deps`, `mlir-opt`, `opt`,
`llc`, coverage and test tools as ordinary explicit tasks under their original
commands. No additional provider capability, module scheduler, archive builder
or MLIR pipeline is implied by this compiler integration. Java continues through
the existing JDK/ECJ providers; LLVM does not supply a frontend for every language.

## Provenance and qualification

The pinned fork's
[LICENSE.TXT](https://github.com/hsoliwal/llvm-project/blob/591f3d1edf0a9ee718d17a0ee70dd013b3c01908/LICENSE.TXT)
states `Apache-2.0 WITH LLVM-exception` for LLVM, with separate terms for identified
third-party code. Keep the relevant component notices when distributing a built
toolchain. This Apache-2.0 Maven provider uses the command interface and copies
no LLVM implementation. The adjacent `FOSS_REUSE_DECISION.tsv` records that choice.

`LlvmCompilerProviderContractTestMain` checks driver selection, metadata
precedence (including fresh-JVM environment/PATH selection), argument boundaries,
executable validation, digest checks, refusal behavior and read-only plans.
`LlvmCompilerIntegrationTestMain` compiles and runs native fixtures through the
actual task runner, covering headers appearing or changing, separate build
configurations, task working directories, failure recovery and cache exclusion.
LLVM IR and Objective-C-family frontend cases run with the selected Clang;
Fortran compilation and execution run when that installation supplies `flang`.

To run the standalone contracts with a particular installation:

```sh
M3_POLYGLOT_LLVM_HOME=/work/llvm-install \
  sh tools/m3-polyglot-maven-plugin/scripts/verify.sh
M3_POLYGLOT_LLVM_HOME=/work/llvm-install \
  mvn -f tools/m3-polyglot-maven-plugin/pom.xml verify
```

Without an installed Clang toolchain, native integration reports `NOT_RUN`.
Pass `--required` to the integration main to make missing C/C++ tools a failure;
both existing polyglot CI entry points do this after building the plugin. An
explicitly configured invalid toolchain always fails. The test-only environment
variable `M3_POLYGLOT_LLVM_HOME` selects the installation for child-JVM tests;
production builds use the home configuration described above.

The [runnable C example](../examples/llvm/README.md) also exercises the Maven
plugin realm. Each test result describes the toolchain and host it actually ran;
it does not establish Apple SDK, Android, Windows, cross-target, full LLVM-build
or full Maven-reactor qualification.
