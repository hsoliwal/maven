<!--
SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# M3 LLVM/MLIR/LLD tools provider

**Experimental, optional.** Uses the original command line tools from
[hsoliwal/llvm-project](https://github.com/hsoliwal/llvm-project), when
installed separately. Source revision inspected on 2026-11-18:
`591f3d1edf0a9ee718d17a0ee70dd013b3c01908` (the fork's `main`).
This repository does not embed LLVM code, libraries or binaries. LLVM is
Apache-2.0 WITH LLVM-exception; this Java adapter is original Apache-2.0 code.

## Architecture

The **existing** M3 Maven polyglot plugin still owns the DAG, argv execution,
lifecycle activation, module evidence, M3DB state and optional cache.
This module is a separate JAR that implements its versioned
`ServiceLoader<M3ToolProvider>` interface. Nothing runs unless explicitly
declared in `m3-build.tsv` and invoked via `m3-polyglot:run` or the
separately enabled lifecycle. Maven, Gradle and Eclipse can consume the
**same provider JAR** without a second scheduler or proprietary IR format.

| M3 manifest tool | Original executable | Purpose |
| --- | --- | --- |
| `@m3:llvm-tools:optimizer:llvm-ir` | `opt` | LLVM IR optimization |
| `@m3:llvm-tools:optimizer:mlir` | `mlir-opt` | MLIR pass pipeline |
| `@m3:llvm-tools:assembler:llvm-ir` | `llvm-as` | LLVM IR to bitcode |
| `@m3:llvm-tools:transpiler:mlir` | `mlir-translate` | Explicit MLIR translation |
| `@m3:llvm-tools:linker:llvm-ir` | `llvm-link` | LLVM bitcode linking |
| `@m3:llvm-tools:linker:elf` | `ld.lld` | ELF object linking |
| `@m3:llvm-tools:linker:coff` | `lld-link` | COFF object linking |
| `@m3:llvm-tools:linker:macho` | `ld64.lld` | Mach-O object linking |
| `@m3:llvm-tools:linker:wasm` | `wasm-ld` | WebAssembly object linking |
| `@m3:llvm-tools:interpreter:lit` | `llvm-lit` | LLVM lit test runner |
| `@m3:llvm-tools:interpreter:filecheck` | `FileCheck` | LLVM test output matcher |

These are **tool dispatchers**, not new LLVM implementations. An installed
LLVM distribution may omit some tools. MLIR tools and LLVM's Python-based
`lit` runner must be installed separately when needed. The target OS/ABI,
linker arguments, SDK, standard libraries, sysroot and target triple remain
explicit user-selected compiler/linker inputs; **do not infer** compatibility
from the manifest language labels. The `elf/coff/macho/wasm` identifiers
select actual LLD driver names, not cross-compilation settings.

## Toolchain selection and binary pinning

Task-local selectors, consumed during planning:

- `--m3-llvm-bin=<path-to-bin>` — select the binaries directory directly.
- `--m3-llvm-home=<path-to-install-root>` — select `<root>/bin`.
- `--m3-llvm-sha256=<64-hex>` — optionally SHA-256-check the actual executable
  bytes. On mismatch, planning fails, without launching that executable.

A bin and home selector cannot be combined, nor repeated. Relative paths
resolve from the module workspace; absolute paths and paths with spaces work.
The selected path must be a regular executable file; a missing explicit tool
never falls back to another PATH installation. Symlinks are followed when
checking the actual installed executable bytes. Unknown `--m3-llvm-*`
directives are rejected before `--`; `--` and everything following it are
forwarded exactly as original tool arguments.

Without task-local selectors, installation roots are checked in order:
`m3.polyglot.llvm.home`, `m3.llvm.home`, `M3_LLVM_HOME`, then `LLVM_HOME`.
Those roots are shared with the existing LLVM compiler/Clang AST integrations
when enabled. Otherwise the ordinary tool name is passed to the runner for
PATH resolution. A SHA pin without a selected root requires a real executable
on PATH and fails if none is found.

Never compute an executable fingerprint from its filename or `--version`
string; the optional pin hashes the actual executable contents. The pin does
**not** attest a complete LLVM source build, its runtime libraries, headers
or external dependencies.

## Installation

First build the original Maven plugin JAR, then the additive provider JAR:

```sh
mvn -B -ntp -f tools/m3-polyglot-maven-plugin/pom.xml install
mvn -B -ntp -f tools/m3-llvm-tools-provider/pom.xml verify
mvn -B -ntp -f tools/m3-llvm-tools-provider/pom.xml install
```

Add the provider as a **plugin dependency**, not a Maven Core dependency:

```xml
<plugin>
  <groupId>io.synexia</groupId>
  <artifactId>m3-polyglot-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <dependencies>
    <dependency>
      <groupId>io.synexia</groupId>
      <artifactId>m3-llvm-tools-provider</artifactId>
      <version>0.1.0-SNAPSHOT</version>
    </dependency>
  </dependencies>
</plugin>
```

A direct portable Java CLI can instead put both verified JARs on its classpath.
`java -cp '...m3-polyglot...jar:...m3-llvm-tools-provider...jar'
io.synexia.maven.polyglot.PolyglotCli providers` lists the installed roles.

See [example/llvm-ir](example/llvm-ir) for a 3-stage IR pipeline.
It uses three ordinary tasks, outputs in the module root, and original LLVM
binaries. The Maven plugin has no implicit phase binding and requires user
opt-in for `plan` or `run`.

## Deterministic toolchain evidence (M3-LLVM-TOOLCHAIN-V1)

The standalone Java 17 class `LlvmToolchainEvidence` scans **only explicitly
selected installed binaries** and produces sorted, root-relative content
evidence. It does not execute the tools, download LLVM, or modify the LLVM
installation. The byte sequence is stable across checkout relocation when
the same tools, symlink targets and bytes are present.

The example below runs with the already installed optional provider JAR,
outside Maven Core. The LLVM installation must contain a `bin` directory:

```sh
java -cp m3-llvm-tools-provider.jar io.synexia.maven.llvm.LlvmToolchainEvidence \
  scan /opt/llvm-fork /work/llvm-toolchain.tsv clang,clang++,llc,opt,ld.lld

java -cp m3-llvm-tools-provider.jar io.synexia.maven.llvm.LlvmToolchainEvidence \
  verify /opt/llvm-fork /work/llvm-toolchain.tsv
```

The input tool list is a strict, nonempty subset of documented LLVM driver
names. Unknown, repeated, missing, non-executable and externally symlinked
tools are refused. Existing toolchain files are read only. Evidence is
published atomically in an **existing directory outside the selected
toolchain**; an output symlink or alias back into the toolchain is refused.
An unsupported atomic move is a failure, not permission to publish an
unsealed partial result.

The deterministic TSV shape is:

```text
M3-LLVM-TOOLCHAIN-V1
tool<TAB>name<TAB>relative-invoked-path<TAB>relative-real-target<TAB>size<TAB>sha256
```

Each `tool` row names the original executable as invoked (for example
`bin/clang++`) and the resolved file whose *bytes* were hashed (often
`bin/clang` because Clang++ is a symlink). Paths are relative to the
installation so the manifest does not embed personal filesystem roots.
SHA-256 covers the complete executable bytes, not `--version` or a
filename. The manifest version, sorted rows, exact bytes and symlink targets
are checked again by `verify`. An incomplete requested toolchain is an
error, not a partially qualified success.

**Boundary:** a manifest is an exact tool selection snapshot, not proof
that those bytes were compiled from the claimed LLVM Git SHA. It does not
describe a complete native build dependency closure, compiler plugins,
sysroot/runtime libraries, target ABI, environment, flags, headers,
Fortran modules, or source-compatibility. M3DB/Gradle can retain this
manifest as **advisory provenance**, but it cannot authorize skipping
native tasks or restoring their outputs until a separate verified
producer-specific cache admission design exists. The existing provider
runner continues to execute all provider tasks.

A native tool task can additionally demand this exact manifest at plan
time. For example, in a `m3-build.tsv` optimizer task declare:

```text
arg<TAB>optimize<TAB>--m3-llvm-home=/opt/llvm-fork
arg<TAB>optimize<TAB>--m3-llvm-evidence=/work/llvm-toolchain.tsv
```

The `--m3-llvm-evidence=<path>` option is consumed by the existing
`@m3:llvm-tools` provider and never forwarded to LLVM. Before returning
the original command vector it re-verifies all manifest rows and **requires
that the executable selected for the task is among them**. The task must
also have an explicit or shared LLVM installation root (or an explicit
`bin` directory ending in `bin`); a bare PATH executable cannot silently
acquire provenance from some other installation. A missing manifest,
unrecognized selected driver, changed binary or escaping symlink fails
planning. This is an optional security/evidence gate: without the option,
existing tasks are unchanged, and **no provider work becomes cacheable**.

The offline Java 17 suite exercises sorting, content changes,
canonical verification, root-relative paths, symlinks, external-target
refusal, missing/duplicate tools and atomic publication. Its success does
not imply real LLVM binaries or cross-platform execution were tested.

## M3 invariants and limitations

- Non-interpreter operations require disjoint declared source and output
  paths through the existing M3 provider SPI. `lit`/`FileCheck` are
  interpreter operations and may omit output declarations.
- All provider tasks execute on each requested build even when the caller
  enables generic M3DB incremental state or output caching: compiler
  dependency closure, negative header lookup, generated source, LLVM pass
  plugins, link scripts, runtime libraries, toolchain resources, target ABI
  and environment are **not** a verified complete cache identity.
- `tools/code-clustering-clang` already owns Clang JSON AST extraction from
  `compile_commands.json`. No second AST parser is introduced here.
- Native dependency truth remains with the actual compiler's depfiles,
  CMake/Ninja build graph and IDE toolchain owner; no unconditional skip is
  justified by a parsed depfile alone.
- LLVM source/IR transformations are never silently applied to source files:
  the manifest records their inputs and separate outputs, and recipe-first
  OpenRewrite retains Java source mutation authority.

## Real LLVM IR pipeline regression (opt-in host, required in Ubuntu CI)

The separate `LlvmNativePipelineIntegrationTestMain` reuses the **installed**
`@m3:llvm-tools` provider, `TaskManifest`, and `TaskRunner` without
introducing another tool scheduler or parser. It first verifies that a
five-node graph can be planned with no filesystem side effects:

```text
source.ll --opt -passes=instcombine--> source.opt.ll --llvm-as--> source.bc
helper.ll -----------------------------------------llvm-as--> helper.bc
source.bc + helper.bc -----------------------------llvm-link--> linked.bc
linked.bc ----------------------------------------------opt -S--> linked.ll
```

The test then runs the native tools and verifies the resulting IR defines
both functions, including the helper's returned value. It repeats with
M3DB incremental and artifact-output cache options enabled and requires
the **actual LLVM tools to execute every time**. A helper edit must appear
in linked IR; invalid LLVM IR must fail; restoring valid IR must recover.
It also checks every declared stage output is nonempty. These are
native correctness and **cache-exclusion**, not cache-admission, tests.

Run on a qualified host with `opt`, `llvm-as` and `llvm-link` installed:

```sh
mvn -f tools/m3-polyglot-maven-plugin/pom.xml install
mvn -f tools/m3-llvm-tools-provider/pom.xml verify

# Required external LLVM tools, selected as one installation:
M3_LLVM_PIPELINE_HOME=/path/to/llvm-install java \
  -cp 'tools/m3-llvm-tools-provider/target/test-classes:tools/m3-llvm-tools-provider/target/classes:tools/m3-polyglot-maven-plugin/target/classes' \
  io.synexia.maven.llvm.LlvmNativePipelineIntegrationTestMain --required
```

Without a selected/installed toolchain, the Maven `verify` suite still
runs the read-only graph validation and clearly reports `NOT_RUN` for the
real pipeline. An explicit but incomplete toolchain fails, including in
optional mode. The isolated Ubuntu 24.04 CI job installs its distribution's
LLVM 18 tools at `/usr/lib/llvm-18` and runs this class with `--required`
on Java 17 **and** Java 21. This qualifies the distribution LLVM runtime,
not the independently audited fork source.

The run does not use native JNI bindings; executable invocations are
individual `ProcessBuilder` argument vectors. Neither the LLVM pass
pipeline nor the result format is reimplemented in Java. For the pinned
`hsoliwal/llvm-project` fork, set `M3_LLVM_PIPELINE_HOME` to a separately
built matching installation and record its binary evidence first.

## Testing and release gate

`sh tools/m3-llvm-tools-provider/scripts/verify.sh` runs Java 17 source
compilation and deterministic offline command/SPI contracts without any
LLVM installation. `mvn -f tools/m3-llvm-tools-provider/pom.xml verify`
runs the same contract suite using the packaged Maven dependency JAR.

Qualify production release separately against the exact built fork revision
on Windows, Linux and macOS, toolchain SHA pins, JDK17/21, LLD target ABIs,
MLIR dialects/passes, LLVM `lit`/FileCheck fixtures, CMake/Ninja outputs,
Clang AST equivalence, Maven/Gradle/Eclipse bridges, and full Maven reactor.
`verify` of this adapter alone is **not** a guarantee of those gates.
