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
incremental mode stores verified successful tasks in a persistent **M3IndexDB file per Maven module**.
A later Maven process can reuse that state after checking the declared inputs, build context,
prerequisite generations and exact output contents. Source owners declare the complete inputs
of each cacheable task. The backend choices added to this fork remain available.

## Goals and explicit lifecycle activation

- `m3-polyglot:plan` prints the requested dependency-ordered plan; no process executes.
- `m3-polyglot:run` executes the requested DAG. It has no default lifecycle phase.
- `-Dm3.polyglot.skip=true` explicitly skips the run goal.
- `-Dm3.polyglot.dryRun=true` plans instead of executing.
- `-Dm3.polyglot.approveRewriteApply=true` separately authorizes mutating OpenRewrite recipes.
- `-Dm3.polyglot.incremental=true` enables verified reuse. It remains off by default.
  A task needs separate declared input and output paths and verified prerequisite generations.
  CodeQL, OpenRewrite and pluggable M3 providers always execute, as do their unverified dependents.
- `-Dm3.polyglot.incrementalState=embedded` selects the default original M3IndexDB implementation.
  The existing `file`, `m3db`, `legacy`, `sqlite`, `h2` and `ext-*` values remain accepted
  as described below; `native` is an additional spelling for native MIndexDB.

- `-Dm3.polyglot.buildCache=<folder>` separately enables the existing local artifact output cache.
  It can restore declared outputs in another compatible checkout or after `clean`, and works with or
  without incremental reuse. With both options enabled, the module's up-to-date check runs first.
  Keep this explicit cache folder outside the workspace, for example `~/.m2/polyglot-build-cache`.

### Optional artifact output cache

The output cache preserves the fork's Gradle-inspired content-addressed storage and its
`m3.polyglot.buildCache` / `--build-cache` options. Only fully declared ordinary-tool tasks with
separate input/output paths and verified prerequisites qualify. CodeQL, OpenRewrite and pluggable
M3 provider tasks, in-place tasks and unverified dependents keep executing normally.

The module database and output cache have separate identities. A module receipt is bound to the
canonical workspace directory. An output-cache lookup uses a relocatable identity covering the
resolved executable bytes, arguments, inherited environment, OS/JVM and engine identity, local
Maven configuration, declared input bytes, output declarations and verified prerequisite
generations. Old weak cache keys cannot authorize new module success. A different tool,
environment or prerequisite generation produces a miss. Enabling output caching additionally
declares that the task's outputs can be relocated; a tool that embeds its absolute working directory
in its output is not eligible without an appropriate task-specific relocation contract.

On a hit, the original artifact store verifies the cached file sizes and SHA-256 digests and replaces
only the declared outputs (`FROM-CACHE`). A declared output folder is replaced as a whole, including
empty directories. Before every restore attempt, the same module lifetime lease and durable
transitive invalidation used for execution revoke the old task and dependent receipts. A failed or
partial restore leaves those old receipts invalid. With incremental reuse enabled, the runner checks
the current input/context identity and actual output bytes after restoration before recording fresh
root-bound module evidence. A cache-only run also revokes stale module receipts.

After successful execution with unchanged inputs, outputs are stored (`CACHED`).
`entries/xx/<key>.tsv` lists output folders and files; `blobs/xx/<sha256>` stores each file body once.
Malformed or out-of-scope entries and blobs whose contents no longer match their hashes are misses.
Outputs containing links or special files, and entries over 1 GiB, are not stored (`NOT-CACHED`).
There is no automatic eviction. No remote cache service or automatic toolchain download is implied.

```sh
mvn io.synexia:m3-polyglot-maven-plugin:0.1.0-SNAPSHOT:run \
    -Dm3.polyglot.incremental=true \
    -Dm3.polyglot.buildCache=/work/polyglot-build-cache
```

### Persistent module database

By default, each module owns one **`.m3/module.m3db`** file. This ZIP contains the
**original M3IndexDB** content-addressed blob/ref format with a versioned successful-task artifact.
The plugin includes the exact eight-file Apache-2.0 implementation from a pinned public M3JDK21
revision, with the original package, license and notice. Consumers need no separate database JAR
for this default. See `src/main/resources/META-INF/M3INDEXDB-PROVENANCE.tsv` for the source seal.

| Backend | Selection | Persistent storage and requirements |
| --- | --- | --- |
| Embedded original M3IndexDB | `embedded` (default) | One `.m3/module.m3db` archive; implementation is included |
| Sealed file | `file` | Preserves `.m3/module-state-v1.bin` and atomic SHA-256-sealed publication |
| Native MIndexDB | `m3db` or `native` | Preserves `.m3/maven-build-db/` and the real reflective `MIndexDB.open`, `findArtifact` and `putArtifact` integration; its runtime must be on the plugin classpath |
| Legacy configuration alias | `legacy` | Uses embedded M3IndexDB; old per-task stamps never authorize a skip and the first upgraded build establishes new evidence |
| SQLite JDBC | `sqlite` | Preserves `.m3/module-state.sqlite` and local transaction sidecars; requires an explicit `org.sqlite.JDBC` runtime dependency |
| H2 JDBC | `h2` | Preserves `.m3/module-state.mv.db` and H2 sidecars; requires an explicit `org.h2.Driver` runtime dependency |
| External portable storage | `ext-*` | Preserves the public `M3ModuleDbBackendProvider` SPI; the selected trusted provider must be installed and independently qualified |

Explicit native, JDBC and SPI selections fail when their required implementation is unavailable.
They never silently fall back to another backend. Switching backends establishes a new generation so that returning
to an older image cannot revive a success predating intervening work or a failed attempt.

All backends share the same verified runner and the same module lifetime lock:

| Reuse condition | Evidence checked before skipping |
| --- | --- |
| Same module | Canonical module directory identity |
| Same task and prerequisites | Tool, arguments, working directory, output declarations and successful dependency generations, including aggregation targets |
| Same inputs | Sorted, separately framed file digests, paths, sizes and kinds; directory identity includes additions, deletions and empty directories |
| Same execution context | Resolved executable bytes/path, full inherited process environment digest, OS/JVM properties and plugin/database implementation identity |
| Same local Maven configuration | Module and ancestor POMs and manifests, plus `.mvn/maven.config`, `jvm.config`, `extensions.xml` and `toolchains.xml` |
| Valid outputs | Exact file and directory digests, including missing, added or modified output files |

Environment values are hashed in memory. Reusable task receipts contain identities and digests,
not the environment values or source/output payloads. A relocated module, incompatible schema
or corrupt state starts with a miss. Older `.m3/polyglot-stamps/*.sha256` files and weaker state
schemas are not accepted as successful evidence. Existing v1 images from the file/native/JDBC/SPI
backends start cold under the root-bound schema-2 receipt protocol; the first successful upgraded
build records fresh evidence in the selected backend. No old success is translated into a new one.

Before launching a task, the runner durably revokes its earlier success and its transitive
dependents. Failed, interrupted, timed-out and explicit nonincremental executions cannot leave
an earlier success reusable. Inputs and context are checked again after successful execution.
If they changed while the tool ran, the command is reported `UNCACHED` and later runs execute
it again. Independent unchanged tasks retain their records.

The stable `.m3/module-state.run.lock` covers validation, execution and publication across all backend
choices. A second process using the same module is refused with an explicit `already in use`
error; separate modules have independent locks. `.m3/module.m3db.state` coordinates backend and
session generations. Interrupted sessions cannot revive an old backend image. File publication
forces a temporary image before atomic replacement; a filesystem without atomic replacement
cannot publish reusable state. Coordination files are not additional task databases. Dry runs
create no state. A nonincremental real run can create coordination files and revoke earlier
state. All state and coordination paths are reserved from declared task outputs.

**Declaration contract.** Use reuse for deterministic tasks whose relevant file inputs and
outputs are completely declared. Declare dependency JARs, compiler libraries, native headers,
external settings, processors, parent POMs outside the directory ancestry, lock files and other
tool resources used by the command. The database does not infer hidden dependencies, network
state, time/randomness or non-file effects. In-place input/output tasks, overlapping output
owners and unknown provider dependencies remain uncached. Inputs must remain stable while
the original tool runs.

The verified task runner still reads declared file contents; saved work is the tool's
compilation or generation. The existing file/native compatibility API retains its guarded
POSIX metadata digest reuse, while that metadata alone cannot authorize a runner cache hit.
Module-state backends record receipts, so missing outputs require execution unless the separately
enabled artifact output cache first restores verified bytes. M3File/M3AST ownership and the watcher's
conservative clean-build fallback remain intact.

After building and installing the plugin, a fully declared ordinary-tool graph can run directly:

```sh
mvn -f tools/m3-polyglot-maven-plugin/pom.xml install
# In the consuming module, using its m3-build.tsv target named all:
mvn io.synexia:m3-polyglot-maven-plugin:0.1.0-SNAPSHOT:run \
    -Dm3.polyglot.incremental=true
# Repeat in a later process: verified unchanged tasks report UP-TO-DATE.
```

To select preserved file storage, add `-Dm3.polyglot.incrementalState=file`.
Use `m3db` or `native` only with the native MIndexDB runtime present in the plugin realm.
The phase-binding command in the next section additionally requires the smart core extension,
the installed plugin and phase-named manifest targets. The shipped provider example is a
reviewable CodeQL/OpenRewrite plan; its providers deliberately do not demonstrate cache hits.

Ignore `.m3/module.m3db`, `.m3/module.m3db.lock`, `.m3/module.m3db.state`,
`.m3/module-state-v1.bin`, `.m3/module-state-*`, `.m3/module-state.*` and
`.m3/maven-build-db/` in the consuming project.
Removing cached state after all builds have stopped forces fresh execution. Never remove or
replace the lock while a process owns the module.

### Optional JDBC and portable storage providers

The `sqlite`, `h2` and `ext-*` selections keep the same content verification, success revocation,
backend generation and module lease as the embedded/file/native choices. They store the common
sealed module image; storage selection does not grant a task or lifecycle goal permission to skip.
No JDBC driver or third-party storage engine is bundled or fetched automatically.

```sh
# With org.sqlite.JDBC explicitly installed in the plugin runtime:
mvn io.synexia:m3-polyglot-maven-plugin:0.1.0-SNAPSHOT:run \
    -Dm3.polyglot.incremental=true -Dm3.polyglot.incrementalState=sqlite
# With org.h2.Driver explicitly installed in the plugin runtime:
mvn io.synexia:m3-polyglot-maven-plugin:0.1.0-SNAPSHOT:run \
    -Dm3.polyglot.incremental=true -Dm3.polyglot.incrementalState=h2
# The existing phase binding also accepts either choice:
mvn -Dm3.polyglot.lifecycle=true -Dm3.polyglot.incremental=true \
    -Dm3.polyglot.incrementalState=sqlite verify
```

The CLI accepts `--incremental --state-backend sqlite`, `h2`, or a registered name such as
`ext-storage-v1`. External provider IDs follow `ext-[a-z0-9][a-z0-9-]{0,47}` and retain the
public `id`, `read(Path)` and `write(Path, byte[])` methods. Register them through
`META-INF/services/io.synexia.maven.polyglot.M3ModuleDbBackendProvider`. Providers are trusted,
module-local persistence implementations; the caller owns cache eligibility and the whole-run lock.

SQLite uses its rollback journal; H2 retains its own file/lock naming. Driver availability and
real-engine interoperability require separate qualification. The JDK-only contract suite verifies
selection/refusal, common module coordination and the store contracts without claiming a missing
SQL engine executed. See [portable database contract](docs/M3_PORTABLE_DATABASES.md) for the
preserved provider boundary, cold migration, donor references and remaining runtime limits.

### Ordinary Maven lifecycle reuse

The existing [smart Maven core extension](../m3-smart-builder/extension/README.md) also
provides a separate opt-in strategy for standard Java lifecycle work. Load that extension
as described in its README, then use:

```sh
mvn -Dm3.lifecycle.incremental=true verify
```

This strategy shares the module database facade and backend selections with the polyglot
runner. `-Dm3.lifecycle.backend=embedded` is the default; `file`, `m3db`, `native`, `sqlite`,
`h2`, registered `ext-*` providers and the `legacy` compatibility alias are also supported. `-Dm3.lifecycle.inputs=path1,path2` adds
file or directory inputs outside the normal model, using absolute or module-relative paths.
Extra declarations add evidence for supported goals; they do not enable reuse for arbitrary
plugin code. Native lifecycle reuse requires MIndexDB and its runtime/native dependencies
in the core extension's runtime realm. JDBC drivers and SPI providers used by lifecycle goals
likewise require an explicit runtime classpath; see the extension README for its setup.

| Standard plugin | Versions admitted for reuse | Goals |
| --- | --- | --- |
| Maven Resources Plugin | 3.3.1 | `resources`, `testResources` |
| Maven Compiler Plugin | 3.13.0, 3.14.1 | `compile`, `testCompile` |
| Maven JAR Plugin | 3.4.1, 3.4.2 | `jar` |

Supported lifecycle results are checked against the effective Maven model and configuration,
resolved dependencies, plugin implementation, runtime, project inputs and goal outputs.
Test execution, installation and deployment still run through Maven. Unsupported plugin
versions, custom or forked work, annotation processors and other unqualified configurations
also retain the original execution path. The extension restores the Maven project bookkeeping
needed after an admitted hit, including the main JAR artifact where applicable. It never
restores missing artifacts from a remote store.

This option applies to ordinary Maven lifecycle goals without requiring an `m3-build.tsv`.
The `m3.polyglot.incremental` option separately controls declared external-tool tasks, while
`m3.polyglot.lifecycle` binds phase-named manifest targets as described next.

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
  --timeout-seconds 900 --incremental --state-backend embedded \
  --build-cache /work/polyglot-build-cache
```

`--timeout-seconds` accepts 1–43200 (default 900). `--state-backend` is available only
with `run --incremental` and accepts `embedded|legacy|file|m3db|native|sqlite|h2|ext-id`. `--incremental`,
`--build-cache <folder>` and `--approve-rewrite-apply` require `run`; recipe mutation still requires the
separate approval flag. Unknown, duplicate or malformed options fail before
the graph executes. The external host must SHA-256-verify the selected JAR
and retain its own task/diagnostic lifecycle. The Gradle quality-bridge fork
can invoke this exact class without loading Maven Core or copying the task
engine. Output caching requires its explicit option; toolchain downloads and CodeQL execution are never implicit.

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
**never** skipped by module database reuse: provider-owned hidden inputs, classpaths and ABI
need their own complete execution evidence.

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
(the fallback cannot certify Java 17 API availability). It runs standalone contract
suites covering the task graph, module-state facade, embedded store, persistent task reuse,
backend selection, portable storage SPI and leases, the preserved artifact output cache and its
module-state integration, quality providers, M3 tool SPI, Mavenizer, Eclipse model and CLI.
Supplying `M3_JDBC_DRIVER_CLASSPATH` additionally runs the positive
H2 and SQLite gate described in the [portable database contract](docs/M3_PORTABLE_DATABASES.md).

`mvn -f tools/m3-polyglot-maven-plugin/pom.xml install` generates the four Maven goal descriptors
and runs those default suites in separate JVMs with their complete test classpath.
Real CodeQL extraction, real OpenRewrite application, full Maven reactor execution and m2e/PDE
behavior have separate runtime acceptance gates.
