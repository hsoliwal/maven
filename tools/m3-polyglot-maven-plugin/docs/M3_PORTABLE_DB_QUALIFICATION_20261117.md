<!-- SPDX-License-Identifier: Apache-2.0 -->
# M3 portable module-state — qualification receipt

Date: 2026-11-17. Scope: `hsoliwal/maven`, branch
`feat/m3-portable-db-backends-20261117`, PR #147 (draft).
Base commit: `c71be270dc3acab398185aaad2c5e9583d186f46`.
This is a bounded source-and-contract qualification receipt, **not** a Maven release seal.

## Required gate order and observed state

| Gate | Observed | Qualification |
| --- | --- | --- |
| Repository inventory | PASS | Existing `ModuleStateDb`, `TaskRunner`, `RunMojo`, `PolyglotCli`, test targets and competing PRs #144/#145 inspected |
| Contract before code | PASS | `docs/M3_PORTABLE_DATABASES.md` committed first |
| Exact scope diff | PASS | Initially 13 plugin-local changes; no root/JDK/Resolver sources modified |
| Diff whitespace | PASS | Direct PR diff scan: 0 trailing whitespace errors |
| Maven POM structural count | PASS (limited) | 9 `<execution>` openings and 9 closings; not a full Maven model validation |
| New Java sources/tests lint+compile | PASS | Local JDK 21: `javac --release 17 -Xlint:all -Werror`, no warnings |
| Portable lock/driver refusal tests | PASS | Local `M3PortableDbContractTestMain`: 13 assertions |
| Local-vs-GitHub Git blob identity | PASS | All four newly added Java/test files matched exactly |
| Modified existing Java compilation | NOT_RUN | No complete current fork checkout in local container |
| Full Maven plugin descriptor/reactor | NOT_RUN | `mvn` executable unavailable locally |
| SQLite/H2 JDBC runtime integration | NOT_RUN | Real drivers absent locally; no actual engine throughput/durability claim |
| Native MIndexDB backend integration | NOT_RUN | External runtime not qualified in this packet |
| Windows/replay/performance/ABI | NOT_RUN | Requires targeted executor/CI |
| Hosted CI | QUEUED at submission | GitHub jobs exist but no passing conclusion established |

## Exact Git blob receipts for newly added Java/test files

| File | SHA-1 Git blob (locally and remotely verified) |
| --- | --- |
| `M3ModuleDbBackendProvider.java` | `4b00c2f2d9980d1c40b7e48730da8a100ed457b0` |
| `M3ModuleDbRunLock.java` | `4046493713ede86286d11b83ec766b307771d412` |
| `M3ModuleJdbcStorage.java` | `ed41c3ffe6f98f0cac0a258f077bfe34ac9d44c7` |
| `M3PortableDbContractTestMain.java` | `4c9e668a25834839e1a69d61828f83537ff06e44` |

## Changed-file pre/post identities (Git blob SHA-1)

| File | Original | Current |
| --- | --- | --- |
| `README.md` | `a668391492ce7e6666d79e98928a71e20b25f843` | `acf5db7ba2bff7aa482c333035504570bb6828e8` |
| `pom.xml` | `7d5b0c3af8873373cb4964a808edae8974ba51b6` | `979d3ab91985bc8110003e8aa5688e7fc5172cfe` |
| `scripts/verify.sh` | `bc73070d49711ce93859ffbb9439be925c97f9ed` | `762e1132aaaf8b6d4f894094682b1e932770fa5d` |
| `ModuleStateDb.java` | `22f576af25de464ed75d5f42f67f65e33810dcc8` | `e31eaf3e2fc959d62b2d697220ee668aba832951` |
| `TaskRunner.java` | `1eb3ad52cdd68c00f11059697df08a2b51b07815` | `7a573aa421c569b91a0f3f505870a67b95a42210` |
| `PolyglotCli.java` | `0d1865a5668590f02cff5db8c53fe9d8e6929b79` | `3f9f944bce954b1592bbed6eaa15df32bdefbdfb` |
| `RunMojo.java` | `8c388d57b3677b2341dfbe3935d6112d574262fd` | `743a3e5860450b52f526d5f679e43d30411df065` |
| `ModuleStateDbContractTestMain.java` | `8ffaf76d17e0f7f723f0a0ae359230d25ff21126` | `24a3752875216627838d998f7ef3e92b8d78807d` |

The digest above establishes **Git blob identity**, not semantic equivalence of old and new code.
No upstream donor source was copied into this PR. SQLite, LMDB, RocksDB, H2, MapDB and
DuckDB are reviewed as architectural donors, not claimed as embedded M3 implementations.

## Remaining release blockers

Do not mark this PR ready/merge it until (1) full Java17 plugin compile and test,
(2) real Maven 4 descriptor and reactor smoke tests, (3) actual SQLite/H2 backend
round-trips including failures and SQL transaction rollback, (4) Windows path behavior,
(5) input/output tampering and failure/crash replay, and (6) a three-way compatibility
review with existing PRs #144/#145 are independently verified. An authoritative
M3File content-generation source and Maven Build Cache Extension integration are
additional, separately owned work.
