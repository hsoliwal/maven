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

Open under decision 55: the `m3db`/`native` state backend still reflects onto Synexia
`com.synexia.mindex.db.MIndexDB` (`.m3/maven-build-db/`). Decision 55 retires that binding;
`m3db` should select the owner store instead.

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
