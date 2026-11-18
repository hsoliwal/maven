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
| Sealed file | `file` | Preserves `.m3/module-state-v1.bin` and atomic SHA-256-sealed publication; the file holds the same IndexStore image as `embedded`, so an image written before that change starts cold once |
| Runtime M3IndexDB | `m3db` or `native` | Preserves `.m3/maven-build-db/`, an IndexStore directory written by the runtime's own engine: the running JDK's `jdk.m3.indexdb` (M3jdk21, module or exported `java.base` package) first, else the reflective `MIndexDB.open`, `findArtifact` and `putArtifact` runtime on the plugin classpath; never the library copy carried by the plugin |
| Legacy configuration alias | `legacy` | Uses embedded M3IndexDB; old per-task stamps never authorize a skip and the first upgraded build establishes new evidence |
| SQLite JDBC | `sqlite` | Preserves `.m3/module-state.sqlite` and local transaction sidecars; requires an explicit `org.sqlite.JDBC` runtime dependency |
| H2 JDBC | `h2` | Preserves `.m3/module-state.mv.db` and H2 sidecars; requires an explicit `org.h2.Driver` runtime dependency |
| External portable storage | `ext-*` | Preserves the public `M3ModuleDbBackendProvider` SPI; the selected trusted provider must be installed and independently qualified |

For the recognized **native M3IndexDB blob/ref directory layout**, the `m3db` backend now
performs bounded, conservative mark-and-sweep compaction under the existing
module-wide lease. Replacing a successful-build artifact may leave the old
content-addressed blob unreachable; maintenance inventories *all* refs, verifies
that their artifact names, digests, sizes and live blob SHA-256 values agree,
and deletes only unreachable blobs. The pre-publish pass also reclaims orphaned
blobs from a previous interrupted update, before evaluating the native store
size budget. The post-publish pass runs only after independent artifact readback
confirms the new state. No deletion occurs if a ref is corrupt, a symbolic link
is encountered, or a future/unrecognized native layout is detected. This
does **not** implement compaction for an unknown platform/donor storage format,
nor does it change the pinned original M3IndexDB engine. If a filesystem does
not durably persist a directory deletion, an orphan may reappear after power
loss but the live reference remains intact. The isolated JDK17 test checks
orphan reclamation, several live refs, idempotence, corruption refusal and
12 consecutive real library-backed native updates.

One owner holds the module state: `M3DbModuleStore` keeps the receipt codec, lease, generation marker and
revocation; every backend is an implementation of the one `ModuleStateBackend` interface. The built-in
backends store through the one storage layer `IndexStore` (M3IndexDB: the platform form on M3jdk21, the
library form otherwise); JDBC and `ext-*` are explicit external engines verified by read-back.
`ModuleStateView` reads the active backend's receipts without publishing, revoking or rotating state, for
`tools/project-db`.

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
dependents. The reverse dependency graph is prepared **once** in M3 compact
compressed-sparse-row (CSR) arrays, rather than rediscovering every edge before
each tool invocation. A successful task stages its verified input/output receipt
in compact parallel arrays. The module publishes **one sealed database image
after the complete selected DAG succeeds**, instead of rewriting it once per
task. A later task failure discards uncommitted staged successes; affected old
receipts were already durably revoked before execution. The separate public
`ModuleStateDb.record` method keeps its immediate-commit behavior for ordinary
Maven lifecycle and project database clients. This borrows RocksDB-style batched
writes while retaining the original M3DB authority, no extra backend or format.

Failed, interrupted, timed-out and explicit nonincremental executions cannot leave
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

A packaged Maven plugin/JDK M3 engine has one stable class realm per module run.
For packaged JAR-backed engines, the scanner now captures the **exact digest
update transcript** of the loaded engine once and replays that byte sequence
for every task fingerprint. It does not replace the engine source hash with a
different digest, so **existing local and relocatable cache keys stay bit-for-bit
identical**. Engine implementations loaded from mutable class directories
(typical of IDE/source builds) retain the original full rehash on each pass.
Task inputs and outputs are still fully rehashed independently; no filesystem
timestamp is allowed to authorize a runner cache hit.

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
Use `m3db` or `native` only on M3jdk21 or with the MIndexDB runtime present in the plugin realm.
The phase-binding command in the next section additionally requires the smart core extension,
the installed plugin and phase-named manifest targets. The shipped provider example is a
reviewable CodeQL/OpenRewrite plan; its providers deliberately do not demonstrate cache hits.

To adopt this in a consuming POM, run the OpenRewrite recipe `io.synexia.maven.recipe.EnableM3ModuleDb`
(`META-INF/rewrite/m3-module-db.yml`, carried from #144; see `docs/M3-MODULE-DB.md`). It adds
`m3.polyglot.incremental=true` and `m3.polyglot.incrementalState=embedded` and keeps any value the
POM already sets.

The module lease writes `.m3/.gitignore` (one line, `*`) when it creates `.m3`, so a consuming project
commits none of `.m3/module.m3db`, its lock and marker, `.m3/module-state-v1.bin`, `.m3/module-state-*`,
`.m3/module-state.*` or `.m3/maven-build-db/` even without its own ignore entries. An existing
`.m3/.gitignore` is kept as it is.
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
| `Cargo.toml` | `cargo build`; `cargo test --no-fail-fast` with surefire reports (below); `cargo build --release` and, for a `[package]`, `cargo package` (a `.crate` in `target/package`); `--locked` on every step when `Cargo.lock` exists |
| `go.mod` | `go build ./...`, `go test ./...` |
| `Makefile` / `GNUmakefile` | `make`, `make test` when a `test:` target exists |
| `CMakeLists.txt` | configure into `build/cmake` with `compile_commands.json`, build and `ctest` in the `Debug` configuration, CTest JUnit XML into `target/ctest`, normalised into `target/surefire-reports`; a `.clangd` pointer (below) |
| `meson.build` | `meson setup/compile/test` in `build/meson` |
| `pyproject.toml` / `setup.py` / `setup.cfg` | `compileall`; `pytest` when configured, else `unittest` over `tests/` |
| `.sln` / `.slnx` / `.csproj` / `.fsproj` / `.vbproj` | `dotnet restore` (`--locked-mode` next to `packages.lock.json`), `dotnet build`, `dotnet test` with TRX results turned into surefire reports, `dotnet pack` into `target/nuget` (see [.NET](#net-test-reports-and-nuget-packages)) |
| Gradle build or settings file | `assemble`, `test` (with `gradlew` when the wrapper is there) |
| Bazel `MODULE.bazel` / `WORKSPACE` | `bazel build //...`, `bazel test //...` |
| `Package.swift` | `swift build`, `swift test` |
| `build.xml` | `ant`, `ant test` when a `test` target exists |

Test reports. CTest writes its JUnit XML itself (`--output-junit`, CMake 3.21+) to `target/ctest/ctest-junit.xml`,
and the `ctest-test` task runs it through `@m3:surefire:interpreter:junit`, which converts the file into
`target/surefire-reports/TEST-ctest.xml`: CTest names its suite "(empty)", writes a disabled test with no `skipped`
element (surefire readers would count it as passed) and a test it could not run as skipped; the report has the
suite `ctest`, the disabled test skipped and the test that could not run an error (`JUnitReport`, ported from the
native-build plugin). Cargo has no stable report format (libtest's JUnit and JSON formats need
nightly), so the `cargo-test` task uses the built-in `@m3:cargo:interpreter:rust` provider: it starts the
`libtest` format of the surefire report runner (`SurefireReportMain`, the same command as
`@m3:surefire:interpreter:libtest <reports> <tool> <argument>...`) in a JVM of the running JDK, which runs the
original `cargo test` (`CargoTestReport`), echoes its output, reads the
stable libtest lines (`test <name> ... ok|FAILED|ignored`, the `---- <name> stdout ----` failure sections, the
`test result:` line, cargo's `Running` and `Doc-tests` lines) and writes one surefire report per test binary
(`TEST-<crate>_<source>.xml`, `TEST-<crate>_doc-tests.xml`) into `target/surefire-reports`, next to cargo's own
`target/debug`. A binary that ends without a result line gets an error case when cargo names it in
`error: test failed, to rerun pass ...` or fails without naming any binary (a crash); a passing `[[test]]
harness = false` binary, which prints no libtest lines, is one passed case. Cargo's exit status is kept:
failing tests fail the build after their reports are written. The report writer (`SurefireXmlWriter`,
`TestSuiteReport`) is the native-build plugin's, ported here so both use one copy; it is the only surefire writer
of this plugin, for cargo test and TRX alike, and replaces each report file in one move.

clangd and CDT. A mavenized CMake folder also gets a `.clangd` file (`CompileFlags: CompilationDatabase:
build/cmake`), so clangd, and CDT's LSP editor (cdt-lsp, which reads and writes the same key), find the compile
database without a copy at the root. CMake writes `compile_commands.json` only with Makefile and Ninja generators:
set `CMAKE_GENERATOR=Ninja` where the default is Visual Studio. An existing `.clangd` is kept. CMake presets,
toolchains, MSVC environments and staging stay with the native-build plugin.

A folder with a hand-written `pom.xml` is a Maven project: listed as a module, never touched, not entered.
A folder with `settings.gradle` / `settings.gradle.kts` is one Gradle build and is not entered either: its
included projects, `buildSrc` and any build below it (test fixtures, samples with their own settings) are run by
that build, once, with its own wrapper. Eclipse buildship, mavenized per sub-folder, ran Gradle for 25 modules,
mixed the wrapper with the `gradle` on `PATH` and made `buildSrc` and its test fixtures reactor modules.
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
| `compiler` | Original compiler argv and build outputs | `@m3:jdk:compiler:java` delegates to the running JDK/M3JDK's `jdk.compiler`; `@m3:ecj:compiler:java` runs ECJ's batch compiler ([ECJ](#ecj-the-eclipse-compiler-for-java)) |
| `interpreter` | Original interpreter argv, possibly with no output | `@m3:surefire:interpreter:trx` runs a test tool and reports its TRX results; `@m3:surefire:interpreter:libtest` runs cargo test and reports its output; `@m3:surefire:interpreter:junit` runs CTest and normalises its JUnit XML |
| `transpiler` | Source-to-source translation argv | `@m3:surefire:transpiler:trx` converts TRX results into surefire reports |
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

### ECJ, the Eclipse Compiler for Java

`@m3:ecj:compiler:java` compiles Java with ECJ's own batch compiler (`org.eclipse.jdt.internal.compiler.batch.Main`)
on the running JDK, the compiler JDT uses in Eclipse. ECJ is EPL-2.0: this plugin never bundles, copies or
downloads it. The build names the jar, first found:

1. a `--m3-ecj-jar=<path>` task argument, relative to the project;
2. the `m3.polyglot.ecj.jar` system property (also for the portable CLI);
3. ECJ on this plugin's own class path, as a plugin dependency, the way maven-compiler-plugin gets it for
   `compilerId=eclipse`:

```xml
<plugin>
  <groupId>io.synexia</groupId>
  <artifactId>m3-polyglot-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <dependencies>
    <dependency><groupId>org.eclipse.jdt</groupId><artifactId>ecj</artifactId><version>3.45.0</version></dependency>
  </dependencies>
</plugin>
```

`--m3-ecj-sha256=<hex>` pins the jar's bytes; a mismatch fails the plan. Task arguments are javac's, mapped as
plexus-compiler-eclipse maps maven-compiler-plugin settings:

| javac | ECJ |
| --- | --- |
| `-Werror` | `-failOnWarning` |
| `-g` | `-g -preserveAllLocals` |
| `-nowarn`, `-Xlint:none` | `-warn:none` |
| `-Xlint:deprecation,-unchecked,rawtypes` | `-warn:+deprecation,-unchecked,+raw` (also `serial`, `fallthrough`, `finally`, `static`; other keys keep ECJ's defaults) |
| `-proceedOnError` | `-proceedOnError:Fatal` |
| `-Xmaxerrs <n>` | `-maxProblems <n>` |
| `--class-path`, `--source-path`, `--source`, `--target`, `--processor-path` | `-classpath`, `-sourcepath`, `-source`, `-target`, `-processorpath` |
| `-properties <file>` (JDT `org.eclipse.jdt.core.prefs`) | the same; the file must exist |
| `-Xdoclint*`, `-Xdiags:*`, `-Xmaxwarns <n>` | dropped: they shape diagnostics only |
| `-XD*`, `-implicit:*`, `-Xpkginfo:*`, `-Xprefer:*`, `-J*` | refused: no ECJ equivalent |

Every other argument (`-d`, `-cp`, `--release`, `-encoding`, `-parameters`, `-proc:*`, `--module-path`, ECJ's own
`-log`, `-warn:...`) passes unchanged. ECJ cannot load javac plugins, so the inline atomizer (`-Xplugin:M3AST`)
fails the plan under ECJ unless the task also says `--m3-atomizer=skip`, which drops it explicitly; compile with
`@m3:jdk:compiler:java` to run it. Atomizing the same sources with the code clustering plugin does not depend on the
compiler. ECJ's `-log <file>.xml` keeps its diagnostics; the analyzer report adapter's `ECJ` format turns them
into the same report rows as javac's (`JAVAC` format). A failing compile fails the task with ECJ's exit status.

```text
task	compile	@m3:ecj:compiler:java	.
arg	compile	--release
arg	compile	17
arg	compile	-d
arg	compile	target/classes
arg	compile	-log
arg	compile	target/ecj.xml
arg	compile	src/main/java/demo/Ok.java
input	compile	src/main/java/demo/Ok.java
output	compile	target/classes/demo/Ok.class
```

### .NET: test reports and NuGet packages

A mavenized .NET folder runs `dotnet test --logger trx --results-directory target/dotnet-test-results` through
`@m3:surefire:interpreter:trx`. That provider removes old `*.trx` files from the results folder, runs the tool,
converts the TRX files it wrote into `target/surefire-reports/TEST-<class>.xml`, and ends with the tool's exit
status: failing tests fail the build and still leave their reports, as with surefire. A TRX `UnitTestResult` is
one test case of its `TestMethod`'s class; `Passed`, `PassedButRunAborted`, `Completed` and `Warning` pass,
`Failed` fails, `Error`/`Timeout`/`Aborted`/`NotRunnable` are errors, `NotExecuted` and other outcomes are skipped;
MSTest data rows (`InnerResults`) are not separate cases. A failure's message is the TRX error message followed by
its stack trace: the first line is the `message` attribute, the whole text the element's text. A TRX file with a
document type declaration is refused. `@m3:surefire:transpiler:trx <results> <reports>` only converts.

`dotnet pack --output target/nuget` runs in `package`. The `m3-polyglot:attach` goal attaches every `.nupkg` and
`.snupkg` there to the project (type `nupkg`/`snupkg`, classifier the package id read from its `.nuspec`), so
`install` and `deploy` carry them with the POM; two packages of one id fail the goal. The generated profile binds
`attach` after the package run. The fork's lifecycle extension binds only `run` targets, not `attach`.

Limits: each folder with a project file is its own Maven project, so a solution folder and the project folders
below it build and test the same projects more than once. Projects on Microsoft.Testing.Platform need
`--report-trx` instead of `--logger trx`. `--locked-mode` is added only when the folder itself holds
`packages.lock.json`.

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
