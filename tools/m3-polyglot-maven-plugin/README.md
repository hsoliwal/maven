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
semantics:** tasks execute after their dependencies, every time. It does not
flatten source inputs, automatically download toolchains, or skip goals using
unverified data. More sophisticated incremental verification belongs to the
source owners and M3JDK/M3FS evidence services.

## Goals and explicit lifecycle activation

- `m3-polyglot:plan` prints the requested dependency-ordered plan; no process executes.
- `m3-polyglot:run` executes the requested DAG. It has no default lifecycle phase.
- `-Dm3.polyglot.skip=true` explicitly skips the run goal.
- `-Dm3.polyglot.dryRun=true` plans instead of executing.
- `-Dm3.polyglot.approveRewriteApply=true` separately authorizes mutating OpenRewrite recipes.

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
