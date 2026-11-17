<!--
SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# M3 Polyglot Maven — Ant + Make + CodeQL + OpenRewrite

**Experimental, opt-in**. This is an additive Maven plugin, not a replacement for
Maven Core or the original Ant, Make, OpenRewrite, CodeQL, CMake, Meson, Cargo,
Gradle, MSBuild, Go, Swift and other build tools.

The original compiler/build tool remains the authority for compiling each language.
The universal task graph provides deterministic Ant-style targets and Make-style
prerequisites. **This version does not implement Make's incremental timestamp
semantics:** by default tasks execute after their dependencies every time. Explicit
incremental mode can reuse a declared-input content stamp, with the limitations below. It does not
flatten source inputs, automatically download toolchains, or skip goals using
unverified data. More sophisticated incremental verification belongs to the
source owners and M3JDK/M3FS evidence services.

## Goals and explicit lifecycle activation

- `m3-polyglot:plan` prints the requested dependency-ordered plan; no process executes.
- `m3-polyglot:run` executes the requested DAG. It has no default lifecycle phase.
- `-Dm3.polyglot.skip=true` explicitly skips the run goal.
- `-Dm3.polyglot.dryRun=true` plans instead of executing.
- `-Dm3.polyglot.approveRewriteApply=true` separately authorizes mutating OpenRewrite recipes.
- `-Dm3.polyglot.incremental=true` skips a task whose content stamp is unchanged since its last success: the
  SHA-256 of its definition (tool, working directory, arguments, outputs) and of the bytes of its declared inputs,
  folders included file by file, recorded in `.m3/polyglot-stamps/<task>.sha256`. A task whose declared outputs are
  missing runs again. No timestamps and nothing undeclared take part; declare an upstream task's outputs as this
  task's inputs to have its changes re-run this task. Off by default. A task without both declared inputs and
  outputs always executes; CodeQL and OpenRewrite providers always execute, even in incremental mode.
  Stamps do **not** certify complete inputs, external tool versions, environment or ABI compatibility.

### Any language through Maven's own phases

With the Maven fork's core extension (`io.synexia:smart-maven-extension`, built into the fork's distribution,
or loaded with `-Dmaven.ext.class.path`), `-Dm3.polyglot.lifecycle=true` binds this goal into every project that
has an `m3-build.tsv`: a target named `validate`, `compile`, `test`, `package` or `verify` runs in that phase as
execution `polyglot-<phase>`. `mvn verify` then builds a reactor that mixes Java modules with modules built by
their own tools (Python, Make, Cargo, Go, CodeQL tasks, ...), and a failing native task fails the build. The
property stays explicit because a nested Maven task would otherwise bind again. A project that declares this
plugin itself keeps its own bindings.

```sh
mvn -Dm3.polyglot.lifecycle=true -Dm3.polyglot.incremental=true verify
```

### Mavenizer: migrate a folder built by other tools

`m3-polyglot:mavenize` needs no Maven project. It scans the folder (and sub-folders, four levels deep; build
outputs, dependencies and hidden folders such as `node_modules`, `target`, `build`, `.git` are skipped) for the
build descriptors also known to the fork's external-build service, and writes per project folder a `pom.xml`
(packaging `pom`, its sub-projects as modules) and an `m3-build.tsv` whose targets `validate`, `compile`, `test`
and `package` run the native commands:

| Descriptor | Steps |
| --- | --- |
| `package.json` (npm, or pnpm / yarn / bun by lock file) | install (`npm ci` with a lock file); `run build` and `test` when the scripts define them (not npm's placeholder test) |
| `Cargo.toml` | `cargo build`, `cargo test`, `cargo build --release` |
| `go.mod` | `go build ./...`, `go test ./...` |
| `Makefile` / `GNUmakefile` | `make`, `make test` when a `test:` target exists |
| `CMakeLists.txt` | configure into `build/cmake`, build, `ctest` when testing is enabled |
| `meson.build` | `meson setup/compile/test` in `build/meson` |
| `pyproject.toml` / `setup.py` / `setup.cfg` | `compileall`; `pytest` when configured, else `unittest` over `tests/` |
| `.sln` / `.slnx` / `.csproj` | `dotnet build`, `dotnet test` |
| Gradle build or settings file | `assemble`, `test` (with `gradlew` when the wrapper is there) |
| Bazel `MODULE.bazel` / `WORKSPACE` | `bazel build //...`, `bazel test //...` |
| `Package.swift` | `swift build`, `swift test` |
| `build.xml` | `ant`, `ant test` when a `test` target exists |

A folder with a hand-written `pom.xml` is a Maven project: listed as a module, never touched, not entered.
Existing files are never overwritten; a run over an already mavenized tree reports `KEEP` lines, writes files for
new sub-projects and names them `MISSING-MODULE` where the kept parent pom does not list them yet.
`-Dm3.polyglot.dryRun=true` prints `PLAN` lines and writes nothing. Bare tool names in a manifest are looked up
in the task's folder (wrappers) and on `PATH`, on Windows with the `PATHEXT` extensions (`npm.cmd`,
`gradle.bat`). Generated manifests declare no inputs, so incremental mode never skips their tasks until inputs are
added; a task without declared inputs always runs.

```sh
mvn io.synexia:m3-polyglot-maven-plugin:0.1.0-SNAPSHOT:mavenize -Dm3.polyglot.mavenize.groupId=org.example
mvn -Dm3.polyglot.lifecycle=true verify
```

`src/it/test_mavenize.sh` proves it on a repository of a Node app, a Go module, a Python package, a Make project
and a Rust crate, built and tested by their own tools through one `mvn verify`.

Build with Maven 3.9+ (or a compatible Maven 4 fork) on JDK 17+:

```sh
mvn -f tools/m3-polyglot-maven-plugin/pom.xml verify
mvn -f tools/m3-polyglot-maven-plugin/pom.xml install
```

Then from a project containing `m3-build.tsv`:

```sh
mvn io.synexia:m3-polyglot-maven-plugin:0.1.0-SNAPSHOT:plan
mvn io.synexia:m3-polyglot-maven-plugin:0.1.0-SNAPSHOT:run
```

Without Maven, validate the Java graph/quality planner with:

```sh
sh tools/m3-polyglot-maven-plugin/scripts/verify.sh
```

### Portable Java CLI for Gradle and other build hosts

The plugin artifact also exposes the original no-Maven-runtime entry point
`io.synexia.maven.polyglot.PolyglotCli`. Its four-argument form remains supported;
the new explicit options are accepted in any order:

```sh
java -cp /verified/m3-polyglot-maven-plugin.jar \
  io.synexia.maven.polyglot.PolyglotCli \
  plan /work/project /work/project/m3-build.tsv all

java -cp /verified/m3-polyglot-maven-plugin.jar \
  io.synexia.maven.polyglot.PolyglotCli \
  run /work/project /work/project/m3-build.tsv all \
  --timeout-seconds 900 --incremental
```

`--timeout-seconds` accepts 1–43200 (default 900). `--incremental` and
`--approve-rewrite-apply` require `run`; recipe mutation still requires the
separate approval flag. Unknown, duplicate or malformed options fail before
the graph executes. The external host must SHA-256-verify the selected JAR
and retain its own task/diagnostic lifecycle. The Gradle quality-bridge fork
can invoke this exact class without loading Maven Core or copying the task
engine. No separate caching, toolchain download or CodeQL execution is implicit.

## Strict manifest v1

UTF-8, LF lines, terminal LF and a maximum 1 MiB. One row per tab-separated
instruction. Each task has one `task<TAB>name<TAB>executable<TAB>workingDir`
row. Additional rows are `dep`, `arg`, `input`, and `output` with the task name
and exactly one value. Argument boundaries are never shell interpolated.
An aggregation target uses tool `-` and has no arguments.

The graph is acyclic, sorted consistently and bounded. Only declared task outputs
are checked for existence after successful execution. Inputs and outputs are
project-relative and reject path traversal. Working directories cannot traverse
symlinks. Jobs run serially in this initial version. Timeouts, interruptions,
nonzero exits and missing outputs fail the build.

The `examples/m3-build.tsv` manifest is a **plan-only example**: replace tools,
CodeQL query packs, recipe coordinates and paths before executing. CodeQL databases
are placed outside the Maven `target/` tree (the CodeQL CLI warns against placing
its database in a directory that a build may interfere with). `.m3/` should be
ignored by Git.

## CodeQL provider (`@codeql`)

Exactly five `arg` rows, in this order:

- `create`, CLI executable, language identifier, database directory relative to
  workspace, `none` or `autobuild` build mode.
- `analyze`, CLI executable, database directory, query/suite/pack selector, SARIF
  result path relative to workspace (ending `.sarif`).
- `query`, CLI executable, database directory, source `.ql` file, binary `.bqrs`
  output. Uses original `codeql query run`, suitable for semantic inventories.
- `decode`, CLI executable, existing `.bqrs` input, literal `csv`, destination
  `.csv` path. Uses original `codeql bqrs decode` with CSV field quoting.

For M3 CodeQL's callable, call-dependency and native-boundary inventory queries,
run explicit `query`/`decode` DAG tasks instead of forcing tabular data through
SARIF alert interpretation. CSV fields require a CSV parser; never split rows
blindly on commas. Keep the query/database/CLI versions and input hashes as
provenance. This plugin does not automatically choose, merge or apply recipes
based on those records.

The provider emits `codeql database create` or `codeql database analyze` using
original CLI commands. The `analyze` format is pinned to `sarifv2.1.0`. It does
not interpret QL findings as a green security result merely from exit code 0:
that only means the query process succeeded. Read and govern SARIF separately.
CodeQL `autobuild` may invoke build commands; select it explicitly and only in
trusted projects. `manual` build tracing is **not yet implemented**. An installed
CodeQL CLI is required; this plugin does not bundle or silently fetch it.

The separate [`hsoliwal/codeql`](https://github.com/hsoliwal/codeql) fork contains
M3 Java semantic inventories and review policies. Use its QL suites with the
original CodeQL CLI, respecting the CLI's separate license. Standard security
query packs remain distinct from M3 optimization/inventory queries.

## OpenRewrite provider (`@rewrite`)

Five or six `arg` rows, in order: `preview|check|apply`, Maven executable,
project-relative `pom.xml`, exact rewrite-maven-plugin version, active recipe
identifier, and optional **pinned** `group:artifact:version` recipe artifact.

- `preview` uses the original `dryRunNoFork` and reports candidate changes.
- `check` uses the original `dryRunNoFork` and sets
  `-Drewrite.failOnDryRunResults=true` to make pending recipe changes fail CI.
- `apply` uses the original `runNoFork` and requires
  `-Dm3.polyglot.approveRewriteApply=true` (or the explicit CLI approval flag).

Each invocation also sets `rewrite.failOnInvalidActiveRecipes=true`. The plugin
is not silently upgraded to the latest release. Nested Maven invocation is
explicit and is **not bound to the standard lifecycle by default** to avoid
accidental recursion. Only the original Maven recipe plugin transforms sources.

A CodeQL finding alone cannot establish a safe rewrite. For code-changing
recipes, run diff/lint/compile/tests and compatibility checks after application.
OSS OpenRewrite does **not** support every programming language; for unsupported
languages use the appropriate original transformation provider instead of
claiming generic recipe support.

## Original authorities and license boundaries

Maven Resolver retains dependency resolution, CodeQL the semantic query engine,
OpenRewrite the recipe engine, language-native tools their compilation/linking,
and M3JDK21/M3FS their file/compiler facts. The new plugin is Apache-2.0;
it does **not** incorporate the separately licensed CodeQL CLI binary or the
original CodeQL/OpenRewrite source trees. This is an opt-in integration,
not a license waiver or a guarantee of security/semantic equivalence.

## Qualification

`verify.sh` compiles the pure Java engine with `--release 17 -Xlint:all -Werror`
and runs two standalone main-based test suites. This is not Maven plugin-descriptor
qualification, real CodeQL extraction, real OpenRewrite application, or full
Maven reactor verification. Those need independent CI execution.
