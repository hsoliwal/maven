<!--
SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->
# M3 portable module-state storage — contract and donor evaluation

Status: **opt-in and additive**. The public `TaskRunner.run` overloads, portable storage SPI,
explicit backend choices and original tool/compiler behavior remain available. Incremental reuse
stays off by default. The common module-state facade serves declared polyglot tasks and the
separate, explicitly enabled [standard lifecycle strategy](../../m3-smart-builder/extension/README.md).
Storage backends do not decide which work is eligible for reuse.

## Existing owners

The `ModuleStateDb` facade keeps the original `file`, native `m3db`, `sqlite`, `h2` and
`ext-*` choices. Native storage still dynamically calls `com.synexia.mindex.db.MIndexDB`.
The default `embedded` backend uses the exact eight-source `jdk.m3.indexdb.M3IndexDB`
implementation inside a module ZIP. These are separate implementations and storage formats;
`m3db` never silently selects `embedded`. `native` is an explicit alias for `m3db`, while
`legacy` is the documented embedded compatibility alias.

Each adapter stores the same root-bound, versioned, SHA-256-sealed receipt image. The current
schema also binds the backend/session generation used to revoke interrupted or superseded
success. Earlier v1 images and loose per-task stamps start cold: they cannot authorize reuse.
A successful upgraded execution records fresh evidence in the selected backend. A backend
cannot mark work cacheable, invent source identity, infer dependencies or bypass goal admission.

## Donor matrix — patterns borrowed, no source copied

| Portable engine | Useful principle | Implementation boundary |
| --- | --- | --- |
| SQLite | Local transactional single-file database, rollback journal, durable commit | Optional JDBC adapter; external driver, local URL only, no implicit WAL |
| H2 MVStore | Multiversion persistent storage and modular typed maps | Optional JDBC adapter; driver required; native file/lock naming retained |
| LMDB | Module-scoped single-writer coordination and consistent reads | Whole-run file lock; no LMDB source/native code copied |
| RocksDB / LevelDB | Crash recovery, WAL and explicit compaction policy | Backend SPI for separately qualified providers; no vendor JAR |
| MapDB | Pluggable Java serializer/storage abstraction | Explicit backend SPI, canonical sealed-image payload |
| DuckDB | Vectorized analytical indexing | Reserved for offline analysis, not hot build-state lookup |
| Maven Build Cache Extension | Lifecycle-oriented module cache and output restore | Separate existing extension; the bounded M3 strategy has its own opt-in goal admission and does not claim its full functionality |

No external library source is copied. External backend providers are explicitly installed,
operator-trusted plugin code, registered through ServiceLoader, named `ext-*`; this contract
does not assert that they are verified, safe, or available.

## Storage, concurrency, and identities

One module root owns one store:

- `embedded` / `legacy`: `.m3/module.m3db`, containing original M3IndexDB blobs/refs
- `file`: `.m3/module-state-v1.bin`
- `m3db`: `.m3/maven-build-db/`
- `sqlite`: `.m3/module-state.sqlite`
- `h2`: `.m3/module-state.mv.db` (and engine-managed transient files)
- `ext-*`: explicitly qualified module-local backend

A separate `.m3/module-state.run.lock` is held across lookup, fingerprinting, tool
execution and committed publication. A second cooperating process for the same module
must refuse or serialize; independent module roots are independent. No network filesystem
or external process bypassing the coordination protocol is assumed safe.

All backends use this same stable lock and the `.m3/module.m3db.state` generation marker.
Changing backend, an interrupted session, or a disabled incremental execution revokes prior
reusable generations. Returning to an older backend cannot revive its stale records. Keep the
lock file in place while any build may own it. Database files, journals, H2 sidecars, the marker
and current/legacy lock names are reserved from task outputs and cannot establish reusable inputs.

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
transactions. The SQLite adapter requests DELETE journal and synchronous EXTRA; H2 requests WRITE_DELAY=0. H2 and
SQLite must be independently qualified with their real runtime drivers; JDK-only unit
tests and offline stubs do not establish database engine interoperability or durability.

The public `M3ModuleDbBackendProvider` contract retains `id()`, `read(Path)` and
`write(Path, byte[])`; implementations receive the complete sealed image. Providers remain
trusted code with independent local-path, atomic-publication and crash-consistency obligations.
No provider, JDBC engine or native implementation becomes qualified merely because a common
facade or JDK-only contract test accepts its API. The current reconciliation preserves the
portable backend capabilities while establishing new schema-2 success evidence through cold
execution, rather than importing an earlier success from a matching filename.

## Verification

Order: inventory -> contract -> scoped patch -> diff/lint -> compile at --release 17 ->
contract tests -> Maven descriptor/integration -> Maven 4 runtime -> replay/benchmark.
Never call a gate PASS unless executed. This change does not qualify the full Maven
Build Cache Extension or a zero-rehash universal M3File generation oracle.

The default `scripts/verify.sh` and Maven contract executions do not require JDBC driver
artifacts. To exercise the separate positive-driver gate with authentic runtime dependencies:

```sh
M3_JDBC_DRIVER_CLASSPATH=/path/to/h2.jar:/path/to/sqlite-jdbc.jar:/path/to/driver-dependency.jar \
    sh tools/m3-polyglot-maven-plugin/scripts/verify.sh
```

Use the platform classpath separator and include the selected drivers' complete runtime
closure. This explicit gate requires both H2 and SQLite; missing engines are failures rather
than skipped coverage. `M3JdbcDbContractTestMain` loads the supplied drivers through a separate
context class loader and tests actual persistence/recovery separately from the portable SPI
and missing-dependency contracts. No JDBC dependency is added to the plugin's default POM.

## Donor references

- https://www.sqlite.org/wal.html
- https://www.sqlite.org/copyright.html
- https://h2database.github.io/html/mvstore.html
- https://h2database.github.io/html/license.html
- https://github.com/facebook/rocksdb
- https://github.com/jankotek/mapdb
- https://maven.apache.org/extensions/maven-build-cache-extension/
