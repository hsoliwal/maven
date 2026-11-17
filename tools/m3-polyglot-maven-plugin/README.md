<!--
SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# M3 Polyglot Maven — Ant + Make + compiler/analysis SPI + CodeQL + OpenRewrite

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
See [M3DB module state](../../docs/M3-MODULE-DB.md) for the durable per-module adapter,
its storage contract, and the pending native Maven/Core admission gates.

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
- `-Dm3.polyglot.incremental=true -Dm3.polyglot.m3db=true` uses the **M3DB module ledger** instead of
  separate task stamps. Every module gets its own `.m3/module.m3db` (plus a coordination `.lock` file).
  One versioned, SHA-256-sealed database stores the successful input/task fingerprint and actual output-tree
  fingerprint for each eligible task. Input and output paths are recursively content-hashed, including file
  and directory identity; timestamps alone do not authorize reuse. Entries are updated under a cross-process
  lock, with atomic replacement so a crash or corrupt/truncated database becomes a cache miss. The database
  is bounded to 256 task entries / 1 MiB per module, does not follow symlinks, and is independent for every
  Maven module. Missing or changed output bytes force a re-run; no result restoration is attempted. A failed
  database read or write never changes the result of a successful original build.
  This is an **opt-in completed-work ledger**, not a cache for arbitrary Maven Mojos. The calling project
  remains responsible for complete declared inputs, tool versions, toolchain, dependency/ABI and environment
  changes; CodeQL, OpenRewrite and M3 compiler/atomizer/patternizer providers continue to run normally.
  If the database cannot verify a result, the original task executes. The old per-task stamp mode and all
  original entry points remain available unchanged. The OpenRewrite recipe
  `io.synexia.maven.recipe.EnableM3ModuleDb` explicitly configures both opt-in properties.

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

# Using the M3 Maven fork with its explicit core extension:
mvn -Dm3.polyglot.lifecycle=true verify

# Use the module-local database for explicitly declared polyglot inputs/outputs:
mvn -Dm3.polyglot.lifecycle=true -Dm3.polyglot.incremental=true -Dm3.polyglot.m3db=true verify

# Using stock Maven 3.9+ / Maven 4 with the installed polyglot plugin:
mvn -Pm3-polyglot verify
```

Mavenizer's generated per-language POMs now include a dormant `m3-polyglot`
profile with `run` executions bound to the native `validate`, `compile`,
`test` and `package` targets present in that folder's `m3-build.tsv`.
The profile is **not** active by default. Stock Maven can execute those
installed native tools through its original reactor without loading the
fork's separate core extension. Pure aggregators have no native executions,
and existing hand-written POMs remain untouched. Install/pin the plugin
before activating the profile. Choose either the profile or the fork's
explicit extension property for a particular build, not both.

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
  --timeout-seconds 900 --incremental --m3db
```

`--timeout-seconds` accepts 1–43200 (default 900). `--m3db` requires `--incremental`;
`--incremental` and
`--approve-rewrite-apply` require `run`; recipe mutation still requires the
separate approval flag. Unknown, duplicate or malformed options fail before
the graph executes. The external host must SHA-256-verify the selected JAR
and retain its own task/diagnostic lifecycle. The Gradle quality-bridge fork
can invoke this exact class without loading Maven Core or copying the task
engine. No separate caching, toolchain download or CodeQL execution is implicit.

## Eclipse (m2e)

The jar carries `META-INF/m2e/lifecycle-mapping-metadata.xml`. When Eclipse m2e imports a project, the `run`
and `plan` executions go to the m2e fork's `org.eclipse.m2e.polyglot` connector, and `mavenize` is ignored. The
connector reads the execution's `manifest` and registers an LSP4E language server for each language its tools
build: rust-analyzer, gopls, clangd, typescript-language-server, pylsp, csharp-ls and sourcekit-lsp, each when it is
on `PATH` and no installed IDE already serves that language. Eclipse never runs the tools; Maven does.

### Read-only Eclipse project model: Core, JDT, CDT, RustDT/Corrosion, PyDev

`m3-polyglot:eclipse-model` is a **new, opt-in, read-only** goal. It needs no Maven project, never executes
an Eclipse builder, compiler or LSP server, and neither edits nor generates `.project`, `.classpath`, `.cproject`,
`.pydevproject` or Maven files. It produces a stable JSON `m3-eclipse-project-v1` snapshot of:

| Eclipse owner | Maven-observable original facts | Ownership boundary |
| --- | --- | --- |
| Core/Resources | project name, ordered nature IDs and builder IDs, project references and linked-resource declarations | Keeps `IProjectDescription` and builder semantics in Eclipse; does not follow linked paths |
| JDT | every `.classpath` entry's attributes, nested access rules and extra attributes | JDT resolves classpath containers, inclusion/exclusion and compiler state |
| CDT | `.cproject` configuration identifiers and names | CDT resolves language settings, toolchains and managed build configurations |
| RustDT / Corrosion | original nature IDs and `Cargo.toml` SHA-256 if present | Rust language tooling/Cargo stays authoritative; unknown nature IDs are preserved verbatim |
| PyDev | `.pydevproject` interpreter/version properties and ordered Python source paths | PyDev resolves interpreter, PYTHONPATH and source folders |

Each present recognized descriptor also gets a per-file SHA-256 digest; optional JDT/CDT/PyDev `.settings` files
are fingerprinted without attempting to override their preferences. The parser fails closed on malformed XML,
DOCTYPE/external entities, oversized descriptors and symlinked descriptor files. It never treats a nature ID as
permission to run that builder. The model is an **interoperability surface**, not an implementation of the Eclipse
Resources API, ECJ, CDT scanner discovery or PyDev's runtime.

```sh
mvn io.synexia:m3-polyglot-maven-plugin:0.1.0-SNAPSHOT:eclipse-model \
  -Dm3.polyglot.eclipse.dir=/work/existing-eclipse-project

# Portable API/CLI, without Maven, Eclipse or OSGi on the runtime classpath:
java -cp /verified/m3-polyglot-maven-plugin.jar \
  io.synexia.maven.polyglot.EclipseModelCli /work/existing-eclipse-project
```

The opt-in OpenRewrite registration recipe `io.synexia.maven.recipe.EnableEclipseModelInspection` only adds this
plugin to a consuming POM; it does not bind goals, modify IDE metadata or install toolchains. Test full m2e/PDE,
JDT, CDT and PyDev round trips independently before treating any live workspace behavior as supported.

## Public M3 tool provider SPI (v1)

The Maven-owned polyglot engine exposes one **opt-in, versioned** Java 17
`ServiceLoader<M3ToolProvider>` contract for these operations:

| Capability | What an extension supplies | Built in |
| --- | --- | --- |
| `compiler` | Original compiler argv and build outputs | `@m3:jdk:compiler:java` delegates to the running JDK/M3JDK's `jdk.compiler` |
| `interpreter` | Original interpreter argv, possibly with no output | SPI only; provider required |
| `transpiler` | Source-to-source translation argv | SPI only; provider required |
| `atomizer` | AST-backed atom/effect inventory argv | SPI only; provider required |
| `patternizer` | Structural, logic and pattern-evidence argv | SPI only; provider required |

The manifest `task` tool value is `@m3:<provider-id>:<capability>:<language>`.
All names are lowercase, validated and unambiguous. The registry fails closed on
unknown or duplicate IDs, unsupported capabilities/languages, incompatible SPI
versions, malformed service descriptors and invalid argv. The `plan` command
validates provider resolution without starting a subprocess. Provider tasks are
**never** skipped by the lightweight incremental stamp heuristic, which does
not model provider classpaths, ABI or environment.

Only the `interpreter` operation may omit output declarations. The others need
separate declared inputs and outputs, and overlapping source/output paths are
refused. This is a declaration gate, **not** an isolation boundary; installed
Java provider implementations and explicitly invoked native tools must be
trusted. A passing compiler or patternizer does not prove that generated
transformations preserve behavior: apply the canonical OpenRewrite recipe,
compiler, tests, contract-risk and semantic-evidence gates separately.

To install a third-party provider, put its jar (compiled against the public
`io.synexia.maven.polyglot` API) on the polyglot engine's **runtime classpath**
and include its class name in
`META-INF/services/io.synexia.maven.polyglot.M3ToolProvider`.
For Maven this means a declared plugin dependency; for the portable CLI or
Gradle's JavaExec host it means an explicit, version-pinned classpath entry.
Nothing is silently downloaded or introspected from an IDE. Runtime
classpath/provider provenance must be managed by the consuming build.

For example, after compiling the plugin and selecting a JDK (or M3JDK) with
`jdk.compiler`:

```text
M3-POLYGLOT-TASKS-V1
task	compile	@m3:jdk:compiler:java	.
arg	compile	-d
arg	compile	target/classes
arg	compile	src/main/java/Example.java
input	compile	src/main/java/Example.java
output	compile	target/classes/Example.class
```

`-Xplugin:M3AST` and other compiler-supported options can be forwarded as
additional `arg` records when that installed compiler actually supplies them.
The class output folder must exist or be created by an explicit predecessor.
To inspect the installed providers without running any task:

```sh
java -cp /verified/m3-polyglot-maven-plugin.jar \
  io.synexia.maven.polyglot.PolyglotCli providers
```

The output starts with `M3-POLYGLOT-PROVIDERS-V1` and then sorted, tab-separated
`provider`, ID, capability list and language list records. This is the same
Maven-owned capability contract Gradle and eventual thin Eclipse plugins can
query. **JDT/CDT/RustDT/PyDev are not replaced here**; their native compilers,
builders, language servers and Eclipse resource/event interfaces remain owners
until independently verified proxy adapters exist.

The shared fact path remains **M3File (identity/digest/deltas) → M3String
(immutable shared source atoms/ranges) → M3AST → atom/pattern evidence**.
This plugin does not claim to implement or copy those authoritative structures;
providers should exchange qualified IDs/digests and reference original source
owners. The `io.synexia.maven.recipe.EnableM3ToolProviderSPI` OpenRewrite
recipe opt-in registers this Maven plugin in a consuming POM without binding
any goal to the default lifecycle.

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

`verify.sh` compiles the pure Java engine with `--release 17 -Xlint:all -Werror` on a full JDK,
or with `-source 17 -target 17` on reduced JDK images without `javac`/`ct.sym`
(the fallback cannot certify Java 17 API availability). It runs six standalone contract suites
(graph, quality providers, M3 tool SPI, Mavenizer, Eclipse model and CLI).
This is not Maven plugin-descriptor
qualification, real CodeQL extraction, real OpenRewrite application, or full
Maven reactor/m2e PDE verification. Those need independent CI execution.
