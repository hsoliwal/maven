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

## Implemented opt-in adapter (portable M3 polyglot engine)

The first concrete adapter is `tools/m3-polyglot-maven-plugin`, shared with its
portable CLI. These two properties must both be explicit:

```sh
mvn -Dm3.polyglot.lifecycle=true -Dm3.polyglot.incremental=true \
  -Dm3.polyglot.m3db=true verify
```

The OpenRewrite recipe `io.synexia.maven.recipe.EnableM3ModuleDb` registers
the opt-in properties for a consuming POM without touching lifecycle bindings.
It preserves pre-existing property values (including explicit `false`).

`-Dm3.polyglot.m3db=true` is the same backend as `-Dm3.polyglot.incrementalState=indexdb`
(CLI: `--m3db` or `--state-backend indexdb`). Each module writes `.m3/module.m3db` and uses
`.m3/module.m3db.lock` for cross-process coordination. The file is an original M3IndexDB archive
(content-addressed `blobs/` and `refs/`) holding one versioned `successful-tasks` artifact with up to
256 named task receipts. Each receipt contains SHA-256 of the task definition, resolved executable,
environment, local Maven configuration, prerequisite generations and real declared input bytes, and
SHA-256 of the successful declared output tree. The archive is bounded (128 KiB receipt, 256 KiB file)
and every artifact is verified against its content digest. A new version, malformed/truncated file,
failed checksum, missing output or mismatched digest is a **cache miss**.

## One storage owner

Every Maven project database in this fork stores through one small interface,
`io.synexia.maven.polyglot.IndexStore` (named, content-addressed, versioned artifacts). The one owner
of the storage logic is M3IndexDB: on M3jdk21 the `jdk.m3.indexdb` module of the running JDK, on a
stock JDK 17/21 the library form of the same source carried by the polyglot plugin
(`META-INF/M3INDEXDB-PROVENANCE.tsv`, Apache-2.0). The module task ledger (`indexdb`), the sealed
module image of the `m3db` backend and the per-project RAG database of `tools/project-db` all use it.
See [tools/project-db](../tools/project-db/README.md) for the per-project build database that ingests
this task state together with test reports, the effective POM, the dependency tree, code-clustering
tables and build events, and answers questions over them with provenance.
File and directory names and types participate in the digest; empty directories
matter. Symlinks are not followed. A successful process is recorded with an
atomic file replacement while a module lock is held. A failed read/write of the
optional database cannot turn an original successful process into a failure.

On a hit, the previously executed tool process is not started. On a miss, the
original tool executes in normal dependency order and its successful outputs
are remeasured. The default mode remains unchanged, as do previous per-task
`.m3/polyglot-stamps/*.sha256` receipts when M3DB is not enabled. No database
is written by a dry-run plan. A module has no effect on a sibling module's
database or selected task graph.

**This adapter still reads and hashes declared input and output bytes to
validate them.** It avoids repeating completed tool actions, not the cost of
every content hash. Only a future integration with authenticated M3File
generations can safely reuse physical digests without rereading files.
File timestamps are never used to assert unchanged content.

## Safety / compatibility boundary

- Both `m3.polyglot.incremental` and `m3.polyglot.m3db` default to `false`.
- Only plain, declared-input/declared-output polyglot tools are eligible.
  CodeQL, OpenRewrite and extensible `@m3` compiler/interpreter/transpiler/
  atomizer/patternizer providers still execute normally.
- A manifest is not proof of all undeclared environment, toolchain, processor,
  plugin, dependency, ABI, or generated-source inputs. The manifest author
  must explicitly include upstream outputs/dependency artifacts in the task's
  inputs for downstream invalidation. If this is not possible, leave
  incremental reuse disabled for that task.
- No arbitrary Maven `Mojo` is skipped, no Maven Build Cache Extension
  artifact is restored, no network fetch occurs, and no source parser or
  dependency resolver is duplicated.
- `clean` removes build outputs as usual; when a declared output disappears,
  the durable state cannot grant a hit.

## Next native Maven integration gates

Maven Core already has `M3BuildFingerprintService` for typed producer-supplied
configuration, toolchain, dependency and environment digests. A reusable native
per-module state service should accept authenticated `M3PluginEvidenceService`
and M3File/M3AST receipts, keep their original owners, maintain an ABI/reverse-
dependency graph, and expose *advisory* changed/unchanged decisions. The owning
plugin or Maven Build Cache Extension must independently establish complete
input coverage, output restoration and goal-specific effects before admission.
It must refuse unknown annotation processors, dynamic inputs or side effects,
and retain full-reactor execution as the conservative fallback. Until those
gates are qualified, this implementation claims only the opt-in polyglot
module-ledger behavior above.

Run `sh tools/m3-polyglot-maven-plugin/scripts/verify.sh` for offline
Java 17-language-level contract tests. Full Maven plugin descriptor/build,
multi-module reactor, Windows, Maven Build Cache Extension and M3JDK21/M3File
integration require separate CI qualification.

## Union of #143, #144 and #145 (one owner)

Three lanes implemented per-module state on the same afternoon: #143 (merged before this union), #144 and #145.
Where two implemented the same thing, the more complete one is kept and the other's tests run against it.

| Source | Item | Kept as |
| --- | --- | --- |
| #143 | `ModuleStateDb` `file` backend (`.m3/module-state-v1.bin`, ctime + file-key stat reuse) | kept unchanged; its stat reuse is a behaviour neither #144 nor #145 has |
| #143 | `m3db` backend (sealed image via reflection on `com.synexia.mindex.db.MIndexDB`) | kept name, image and directory; storage now through `IndexStore` (works on any JDK instead of failing without the Synexia jar) |
| #143 | `m3.polyglot.incrementalState`, `--state-backend legacy\|file\|m3db` | kept; adds `indexdb` |
| #143 | `ModuleStateDbContractTestMain`, README section, ignore rules | kept |
| #143 | Maven build of the plugin (descriptor) | fixed: QDox 2.2 reads `sealed` as a keyword; the parameter is renamed. The unused `FileLock` variable failed `-Werror` in `verify.sh` and is now checked |
| #144 | `M3ModuleDb` (`.m3/module.m3db`, own sealed format) | retired; the same file and purpose are owned by #145's `M3DbModuleStore` (revocation, prerequisite generations, context binding, run-long lock, original M3IndexDB format) |
| #144 | `-Dm3.polyglot.m3db=true`, `--m3db`, `TaskRunner.run(..., boolean moduleDb, ...)` | kept as the switch for the `indexdb` backend |
| #144 | a failed database write never fails a successful build | kept (`M3DB NOT-RECORDED` is logged); a failed revocation still stops the task (#145) |
| #144 | `moduleDbPersistsAndInvalidatesPerModule`, CLI `--m3db` cases | ported onto `M3DbModuleStore.FILE`; all pass |
| #144 | this document, `EnableM3ModuleDb` recipe, `verify.sh` RunMojo stub compile, ignore rules | kept |
| #145 | `M3DbModuleStore`, `InputStamps` v2, vendored `jdk.m3.indexdb` with provenance, license and notice | kept as the owner of the `indexdb` backend |
| #145 | `incremental=true` alone switches to M3IndexDB | not taken: the default stays `legacy` (master, #143 and #144); #145's store is selected explicitly and its tests select it |
| #145 | lock file on every real run; revocation of an older success by a nonincremental run | the store is opened for `indexdb` and whenever `.m3/module.m3db` exists, so revocation is kept and default runs create no `.m3/` |
| #145 | engine identity from the M3IndexDB code source | binds the `jdk.m3.indexdb` module name and version on M3jdk21 (no code source there), the library bytes otherwise |
| #145 | Windows tool resolution, `ModuleStateContractTestMain`, `M3DbModuleStoreContractTestMain`, provider re-execution test, recipe provenance JSON | kept; the input stamp also binds the file Windows launches for an explicit path without extension |
| #145 | `TaskEngine`/`Mavenizer` assertions rewritten for its default | master's assertions restored for the default backend; #145's added where they hold |
| new | `IndexStore`, `IndexStores`, `LibraryIndexStore`, `PlatformIndexStore`, `IndexStoreContractTestMain` | one storage interface; the platform binding is proven with a real module layer |
| new | `ModuleStateView` | public read-only view of every backend's task state, for `tools/project-db` |
| new | `tools/project-db` | per-project build database over the same `IndexStore` |

com.synexia #18285 (merged) holds the qualification and regression recipe crate for #145's files; its hash-pinned
snapshots of the files changed here (TaskRunner, InputStamps, RunMojo, README, pom, verify.sh, M3DbModuleStore, tests)
describe #145, not this union.
