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

Each module writes `.m3/module.m3db` and uses `.m3/module.m3db.lock` for
cross-process coordination. A single database contains up to 256 named task
receipts. Each receipt contains SHA-256 of the task definition and real declared
input bytes, SHA-256 of the successful declared output tree, and a complete
state bit. The binary format is versioned, sorted, size-bounded to 1 MiB, and
sealed with an overall SHA-256. A new version, malformed/truncated file,
failed checksum, missing output or mismatched digest is a **cache miss**.
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
