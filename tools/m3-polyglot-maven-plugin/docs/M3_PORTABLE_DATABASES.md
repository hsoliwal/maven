<!-- SPDX-License-Identifier: Apache-2.0 -->
# M3 portable module-state storage — contract and donor evaluation

Status: **opt-in, additive, no Maven Core lifecycle change**. This contract applies only to the
existing `tools/m3-polyglot-maven-plugin` module-state cache. Public `TaskRunner.run`
overloads, default Maven behavior, `legacy` stamps and compiler/provider contracts stay intact.

## Existing owners

The Maven fork implements a versioned, SHA-256-sealed `ModuleStateDb` image (magic `M3MS`)
with the `file` backend and the portable backends below. Under decision 55 (2026-11-17) the `m3db`
backend is `M3DbModuleStore`, which persists through the M3JDK owner `jdk.m3.indexdb.M3IndexDB`
(`.m3/module.m3db`); the earlier reflective binding to Synexia `com.synexia.mindex.db.MIndexDB`
is retired. The two formats are distinct; neither is read as the other.

Each portable adapter stores the **unchanged, existing binary image**. A backend cannot mark
a task cacheable, invent source identity, skip a tool, infer dependencies or change lifecycle.

## Donor matrix — patterns borrowed, no source copied

| Portable engine | Useful principle | Implementation boundary |
| --- | --- | --- |
| SQLite | Local transactional single-file database, rollback journal, durable commit | Optional JDBC adapter; external driver, local URL only, no implicit WAL |
| H2 MVStore | Multiversion persistent storage and modular typed maps | Optional JDBC adapter; driver required; native file/lock naming retained |
| LMDB | Module-scoped single-writer coordination and consistent reads | Whole-run file lock; no LMDB source/native code copied |
| RocksDB / LevelDB | Crash recovery, WAL and explicit compaction policy | Backend SPI for separately qualified providers; no vendor JAR |
| MapDB | Pluggable Java serializer/storage abstraction | Explicit backend SPI, canonical sealed-image payload |
| DuckDB | Vectorized analytical indexing | Reserved for offline analysis, not hot build-state lookup |
| Maven Build Cache Extension | Lifecycle-oriented module cache and output restore | Remains the owner of ordinary Maven Mojo caching |

No external library source is copied. External backend providers are explicitly installed,
operator-trusted plugin code, registered through ServiceLoader, named `ext-*`; this contract
does not assert that they are verified, safe, or available.

## Storage, concurrency, and identities

One module root owns one store:
- `file`: `.m3/module-state-v1.bin`
- `m3db`: `.m3/module.m3db` (owner M3IndexDB image) and `.m3/module.m3db.lock`
- `sqlite`: `.m3/module-state.sqlite`
- `h2`: `.m3/module-state.mv.db` (and engine-managed transient files)
- `ext-*`: explicitly qualified module-local backend

A separate `.m3/module-state.run.lock` is held across lookup, fingerprinting, tool
execution and committed publication. A second cooperating process for the same module
must refuse or serialize; independent module roots are independent. No network filesystem
or external process bypassing the coordination protocol is assumed safe.

A hit requires: opt-in, an eligible ordinary task with complete declared inputs/outputs,
matching definition/tool/context fingerprint, a previously completed success, matching
actual output tree bytes, and absence of concurrent conflicting execution. A missing
output never hashes to a reusable successful record. Before execution, revoke stale task
and dependent success records durably; on failure never reinstate them. Recheck source
identity after execution before recording success.

An output receipt is **not** an artifact restore facility; deleted outputs trigger an
ordinary build. File size or mtime alone cannot authorize reuse. Current POSIX
ctime/file-key memoization is conditional, not universal proof of content immutability.

## Backend failure and security

Driver and provider ids are fixed or validated; unknown/missing drivers fail explicitly.
No arbitrary JDBC URL, credentials, remote host, model-selected path or root/home writes.
Use strict module-local paths, no symlinks for state/locks, bounded images and atomic
transactions. The SQLite adapter requests DELETE journal and synchronous FULL. H2 and
SQLite must be independently qualified with their real runtime drivers; JDK-only unit
tests and offline stubs do not establish database engine interoperability or durability.

Preserve the sealed on-disk v1 image across all backends; no automatic migration or
claim of equivalence based only on matching filenames. Existing competing PRs #144
and #145 require separately reviewed three-way diffs and cannot be silently combined.

## Verification

Order: inventory -> contract -> scoped patch -> diff/lint -> compile at --release 17 ->
contract tests -> Maven descriptor/integration -> Maven 4 runtime -> replay/benchmark.
Never call a gate PASS unless executed. This change does not qualify the full Maven
Build Cache Extension or a zero-rehash universal M3File generation oracle.

## Donor references

- https://www.sqlite.org/wal.html
- https://www.sqlite.org/copyright.html
- https://h2database.github.io/html/mvstore.html
- https://h2database.github.io/html/license.html
- https://github.com/facebook/rocksdb
- https://github.com/jankotek/mapdb
- https://maven.apache.org/extensions/maven-build-cache-extension/
