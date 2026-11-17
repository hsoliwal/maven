<!--
SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# M3DB: per-module Maven build state

The intended owner chain is **M3JDK/M3File source generations → Maven module state →
Maven reactor and original plugin execution**. Each Maven module should have one
durable, versioned state file, not an independent cache invented by each plugin.
The file may hold source and resource digests, typed compiler/ABI facts,
dependency edges, atom/pattern receipts, toolchain/configuration identities,
and output manifests once their original owners have supplied verified facts.

**Owner (decision 55, 2026-11-17).** M3DB converges fully into M3JDK: one owner per
responsibility. The artifact store is `jdk.m3.indexdb.M3IndexDB` (to be renamed by recipe
to `jdk.m3.db.M3DB`); build state has a single owner, `jdk.m3.fs.M3BuildState` v2. Maven
holds only consumer adapters. This document was carried forward from maven #144, whose own
`M3ModuleDb` format was retired; the polyglot plugin's owner-backed module store (from #145,
reconciled in #157) replaced it.

## Implemented opt-in adapter (portable M3 polyglot engine)

The first concrete adapter is `tools/m3-polyglot-maven-plugin`, shared with its
portable CLI. Reuse is explicit:

```sh
mvn -Dm3.polyglot.lifecycle=true -Dm3.polyglot.incremental=true verify
```

`m3.polyglot.incrementalState` defaults to `embedded`: the owner M3IndexDB image
`.m3/module.m3db`. The portable CLI equivalent is `run ... --incremental`.

The OpenRewrite recipe `io.synexia.maven.recipe.EnableM3ModuleDb`
(`META-INF/rewrite/m3-module-db.yml` in the plugin) registers
`m3.polyglot.incremental=true` and `m3.polyglot.incrementalState=embedded` for a
consuming POM without touching lifecycle bindings. It preserves pre-existing property
values (including an explicit `false` or another state backend).

Each module writes `.m3/module.m3db` (with `.m3/module.m3db.lock` and
`.m3/module.m3db.state` for coordination). The file is a ZIP image of the **owner
M3IndexDB** content-addressed `blobs/` and `refs/` layout holding the plugin's
versioned successful-task artifact; each receipt pairs a task name with the SHA-256 of
its verified input generation (task, argv, working directory, declared input trees,
resolved executable bytes, environment digest, local and ancestor Maven configuration,
implementation identity and successful prerequisite generations) and the SHA-256 of its
declared output tree. A relocated module, incompatible schema, foreign or corrupt image
is a **cache miss**. The plugin README lists the other backends (`file`, `sqlite`, `h2`,
`ext-*`) and the remaining `m3db`/`native` binding.

File and directory names and types participate in the digests; empty directories
matter. Symlinks are not followed. Before a task starts, its record and the records of
its transitive dependents are durably revoked; a failed, interrupted or timed-out run
cannot expose an earlier success. After a successful process the inputs are checked
again and the outputs re-measured before an atomic publication under the module lock.
A failed read or write of the optional database cannot turn an original successful
process into a failure. No database is written by a dry-run plan. A module has no
effect on a sibling module's database or selected task graph.

**This adapter still reads and hashes declared input and output bytes to
validate them.** It avoids repeating completed tool actions, not the cost of
every content hash. Only a future integration with authenticated M3File
generations can safely reuse physical digests without rereading files.
File timestamps are never used to assert unchanged content.

## Reaching the owner

The plugin currently carries byte-identical copies of the eight `jdk.m3.indexdb`
owner sources (see `META-INF/M3INDEXDB-PROVENANCE.tsv`). They retire when the plugin
compiles against the owner jar `com.m3:m3indexdb` on stock JDK. On M3JDK the package is
a concealed java.base package; the plugin realm (an unnamed module) reaches it with the
JVM flag `--add-exports java.base/jdk.m3.indexdb=ALL-UNNAMED` set in the launcher,
`.mvn/jvm.config` or `MAVEN_OPTS` (route A; no java.base export is admitted). On M3JDK
the plugin must not also carry the jar, or two copies of the package load.

Every M3IndexDB access goes through one storage interface, `IndexStore`. Its binding is the
platform form when the running JDK has `jdk.m3.indexdb` (a module of that name, or the `java.base`
package exported with the flag above), otherwise the library copy. The `m3db`/`native` state backend
(`.m3/maven-build-db/`) now selects the owner first: the platform form of `jdk.m3.indexdb`. Only
when the JDK lacks it does the backend bind Synexia `com.synexia.mindex.db.MIndexDB` (plugin
context realm first) as the IndexStore `donor` binding. Master's contract tests pin that discovery
and the refusal without fallback, so retiring the donor binding waits for an explicit decision.

## Safety / compatibility boundary

- `m3.polyglot.incremental` defaults to `false`.
- Only plain, declared-input/declared-output polyglot tools are eligible.
  CodeQL, OpenRewrite and extensible `@m3` compiler/interpreter/transpiler/
  atomizer/patternizer providers still execute normally, as do in-place tasks and
  tasks whose outputs overlap another task's outputs.
- A manifest is not proof of all undeclared environment, toolchain, processor,
  plugin, dependency, ABI, or generated-source inputs. The manifest author
  must explicitly include upstream outputs/dependency artifacts in the task's
  inputs for downstream invalidation. If this is not possible, leave
  incremental reuse disabled for that task.
- No arbitrary Maven `Mojo` is skipped by this adapter, no network fetch occurs, and
  no source parser or dependency resolver is duplicated.
- `clean` removes build outputs as usual; when a declared output disappears,
  the durable state cannot grant a hit.

## Next native Maven integration gates

Maven Core already has `M3BuildFingerprintService` for typed producer-supplied
configuration, toolchain, dependency and environment digests; it stays a fact
producer for the owner build state, not a store. A reusable native per-module
state service should accept authenticated `M3PluginEvidenceService` and
M3File/M3AST receipts, keep their original owners, maintain an ABI/reverse-
dependency graph, and expose *advisory* changed/unchanged decisions. The owning
plugin or Maven Build Cache Extension must independently establish complete
input coverage, output restoration and goal-specific effects before admission.
It must refuse unknown annotation processors, dynamic inputs or side effects,
and retain full-reactor execution as the conservative fallback.

Run `sh tools/m3-polyglot-maven-plugin/scripts/verify.sh` for offline
Java 17-language-level contract tests.

## One owner, one storage layer (union with master after #157)

Master already had one logic owner of module state when this union started: `M3DbModuleStore`
(#145, reconciled in #157), with `ModuleStateDb` as its public facade. It still had several storage
implementations as private inner classes, and two of them bypassed M3IndexDB's storage layer: the
`file` image was written as raw bytes and the `m3db` image through reflection only. Verifier
finding MEDIUM-1 of round 1 counted them as a second module-state owner and a second store
implementation. The table records each choice; "master" means `origin/master` at `302a5598f`.

| Item | Master | This branch | Why | Proof |
| --- | --- | --- | --- | --- |
| Module-state owner | `M3DbModuleStore`: codec, lease, generation marker, revocation; `ModuleStateDb` facade | unchanged: one owner and one facade | master already merged #143/#144/#145 into it | every master suite passes with its own counts or more (below) |
| Backend interface | private `Storage` with five inner classes | package interface `ModuleStateBackend`; implementations `ImageStateBackend`, `DirectoryStateBackend`, `JdbcStateBackend`, `ProviderStateBackend` | one interface, backends as implementations, the owner shrinks from 814 to 501 lines | `M3DbModuleStoreContractTestMain`, `BackendStateContractTestMain` |
| `embedded` (alias `legacy`) | M3IndexDB used directly inside `.m3/module.m3db` | `ImageStateBackend` over `IndexStore` in the same ZIP image; same artifact name, kind and version, same bounds | one storage layer; uses the platform engine on M3jdk21 | master test still opens the image with the original M3IndexDB |
| `file` | raw sealed receipt bytes, atomic file write at `.m3/module-state-v1.bin` | the same `IndexStore` image as `embedded`, at the same path, name and switch | the file backend was the store implementation outside IndexStore (MEDIUM-1). An image written before this change is a one-time cache miss: the existing invalid/foreign-image path, which the suites cover | `ModuleStateDbContractTestMain` and `BackendStateContractTestMain` cases for `file` |
| `m3db` (alias `native`) | reflection onto `com.synexia.mindex.db.MIndexDB` only; refuses without it | `DirectoryStateBackend` over `IndexStore` with the runtime's own engine: the platform `jdk.m3.indexdb` first (decision 55), else the donor MIndexDB; never the library copy; still refuses without either | decision 55 names the owner store; master tests pin no-fallback and context-realm discovery, so both are kept | `IndexStoreContractTestMain`: child JVMs with `jdk.m3.indexdb` as a module and as a patched, exported `java.base` package run the CLI twice (second `UP-TO-DATE`), the facade and the view; the library engine reads the directory they wrote |
| `sqlite`, `h2` | `JdbcStorage` over `M3ModuleJdbcStorage` | `JdbcStateBackend`, same code | external engine chosen explicitly (#147); union keeps it | `BackendStateContractTestMain` (driver absent); `M3JdbcDbContractTestMain` only with `M3_JDBC_DRIVER_CLASSPATH` (NOT RUN here) |
| `ext-*` | `ExternalStorage` over the SPI | `ProviderStateBackend`, same lookup and read-back | public SPI (#147) kept | `M3PortableDbContractTestMain` |
| Storage layer | none (two direct M3IndexDB uses) | `IndexStore`, `IndexStores` (platform, library, donor), used by module state and `tools/project-db` | one storage interface | `IndexStoreContractTestMain` |
| Engine identity in input stamps | code sources of `InputStamps` and `M3IndexDB` | also `IndexStores.engineIdentity`; a platform engine contributes its module and runtime version instead of a code source | on M3jdk21 with `java -cp`, `M3IndexDB` resolves to `java.base` and has no code source, which failed every incremental run | the `java.base` child JVM runs incremental tasks |
| `.m3` in consumer projects | relied on the repository's root `.gitignore` | the module lease writes `.m3/.gitignore` (`*`) and reserves it from task inputs and outputs | LOW-6; master's one changed assertion: the `.m3` listing in `M3DbModuleStoreContractTestMain` counts four entries (database, lock, marker, self-ignore file) instead of three, still with no task stamp files | `M3DbModuleStoreContractTestMain`, `ModuleStateDbContractTestMain` (reserved path), e2e `git add -A --dry-run` |
| `ModuleStateView` | absent | reads the receipts of the last clean session's backend through the owner; publishes, revokes and rotates nothing; a held lease fails the read | project database input, one owner | child JVM view check, `ProjectDbTest` |
| Round-1 names | round 1: `legacy` = per-task stamps, `indexdb` = #145 store, #144 `-Dm3.polyglot.m3db`/`--m3db`/`moduleDb` | master names: `legacy` is an alias of `embedded`; `indexdb` is `embedded`; the #144 switches are not re-added | #157/#166 retired them (the `EnableM3ModuleDb` recipe sets `incrementalState=embedded`) | `PolyglotCliContractTestMain` |
| Round-1 commits `e6ad18084`, `88941f5b7`, `701af8a4c` | superseded by #145/#144 merges, #155 (build fix) and #157 | not cherry-picked; only `IndexStore`, `ModuleStateView` and `tools/project-db` are re-applied, resolved against master | union without regression | full suites below |

### Round-1 verifier findings

| Finding | Status |
| --- | --- |
| MEDIUM-1 two module-state owners, second store implementation | closed: one owner, one backend interface, every built-in backend stores through `IndexStore` |
| LOW contract test for the m3db backend through IndexStore | closed: `IndexStoreContractTestMain` child JVMs (module form and `java.base` form) |
| LOW incremental ingest of a removed source | closed: `ProjectDbTest.removedSource` (catalog shrinks by one, nothing re-parsed, reported `is gone since green build`) |
| LOW facet-only queries return nothing | closed: `Query.facetOnly`; `ProjectDbTest.facetOnly` (`kind:flaky`, `module:core`, `kind:dependency`) |
| LOW no RAT gate for `tools/project-db` | closed: `apache-rat-plugin` 0.18 `check` bound to `validate` in its POM (the root run skips module directories) |
| LOW method lengths | new code: every method at most 15 lines. Touched master methods are not longer: `M3ModuleDbRunLock.acquire` 51 to 43, `InputStamps.engine` 27 to under 15. Over-15 methods in the changed master files fell from 14 to 9; `PolyglotCli` is untouched |
| LOW polyglot `.m3` stores not self-ignored | closed: `.m3/.gitignore`; ingest also guards each module's `target/project-db` |

### Proof on this branch (JDK 17.0.19 and 21.0.9, both green)

| Run | Master `302a5598f` (JDK 17 only) | This branch (JDK 17 and 21) |
| --- | --- | --- |
| `verify.sh`: module-state facade / portable DB / module store / persistent state / backend / build-cache state | 91 / 77 / 79 / 66 / 99 / 37 | 92 / 77 / 80 / 66 / 99 / 37 |
| `verify.sh`: IndexStore and m3db backend (new) | absent | 18 (two child JVMs) |
| `verify.sh`: the other suites (task engine, build cache, providers, Mavenizer, Eclipse model, CLI, tool SPI, cargo, ECJ, .NET) | 8, 6, 6, 6, 5, 3, 22, 5, 3, 7 | unchanged |
| `mvn -o install` of the polyglot plugin (all contract executions) | | BUILD SUCCESS |
| `mvn -o verify` of `tools/project-db` (RAT 27 approved, 0 unapproved; `ProjectDbTest`) | absent | 62 checks |
| `tools/project-db/scripts/e2e.sh` | absent | E2E PASS: 13 sources parsed, then 3 parsed and 10 unchanged; 5 questions with file, line and build id; no `.m3` or project-db file addable to git |
| root `mvn -o -N apache-rat:check` | | 165 approved, 0 unapproved |

## Project database (tools/project-db)

`maven-project-db-plugin` (goals `ingest`, `query`; `ProjectDbCli` for the same plus an MCP stdio
stand-in) keeps per-project build data in one `IndexStore` under `target/project-db`, or
`.mvn/project-db` when persistent; each directory it writes carries `.gitignore` `*`. Ingest reads
polyglot receipts through `ModuleStateView`, Surefire/Failsafe reports, the effective POM, the
dependency tree, code-clustering tables, build-event JSONL and the console log. It parses only
sources whose content hash changed. Retrieval is CPU-only and deterministic (BM25, hashing
vectors, exact `key:value` facets, alone or with words). Every answer line carries file, line and
build id. `tools/maven-mcp-server` is not on master, so the module ships the tool definitions, a
call adapter and the stand-in server.

Not run: a real M3jdk21 image (both platform forms are simulated in child JVMs), a positive
donor MIndexDB run (no runtime is available), JDBC drivers, and `tools/maven-mcp-server`.
