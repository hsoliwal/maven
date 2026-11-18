<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Project database (`maven-project-db-plugin`)

A per-project build database. It stores the build data a project already produces and answers questions about it,
CPU-only and deterministic. Every answer line carries its provenance: **file, line and build id**.

```sh
mvn verify | tee build.log                     # any build, green or red
mvn project-db:ingest -Dproject-db.buildLog=build.log
mvn project-db:query -Dproject-db.question="why did the build fail"
```

The goal prefix `project-db` resolves when `org.apache.maven.plugins` is a plugin group (the default); otherwise use
`org.apache.maven.plugins:maven-project-db-plugin:0.1.0-SNAPSHOT:ingest`.

## One storage owner

All storage goes through `io.synexia.maven.polyglot.IndexStore`, the one small interface the polyglot plugin defines:
named, content-addressed, versioned artifacts. The single owner of the storage logic is **M3IndexDB**: the
`jdk.m3.indexdb` module of the running JDK on M3jdk21, otherwise the library form of the same source carried by the
polyglot plugin (Apache-2.0, see its `META-INF/M3INDEXDB-PROVENANCE.tsv`). The ingest goal logs which one is bound
(`storage platform` or `storage library`). The polyglot module state stores through the same interface (its embedded
and file images and its m3db directory); see [docs/M3-MODULE-DB.md](../../docs/M3-MODULE-DB.md).

Artifacts in the store: `builds` (the build list), `catalog/<build>` (each source with its content hash and the build
that first ingested that content), `chunks/v1/<sha256>` (the parsed chunks of one source content) and
`derived/<build>` (changes since the last green build, flaky tests).

## Where the store lives

| Option | Store |
| --- | --- |
| default | `target/project-db` of the execution root (removed by `clean`) |
| `-Dproject-db.persistent=true` | `.mvn/project-db` of the execution root (survives `clean`) |
| `-Dproject-db.directory=DIR` | `DIR`, relative to the execution root |

Every store directory gets a `.gitignore` of `*`, so it is never committed; this repository also ignores
`**/.mvn/project-db/`.

## What is ingested (per module, when present)

| Source | Producer | Chunks |
| --- | --- | --- |
| `.m3/` polyglot task state | `m3-polyglot:run` with `incremental` (any backend), read through `ModuleStateView` from the backend of the last clean session; a module whose state a running build holds fails the ingest | one per recorded task with its input and output digests |
| `target/surefire-reports/TEST-*.xml`, `target/failsafe-reports/TEST-*.xml` | Surefire, Failsafe | one per test case: passed, failed, error, skipped or flaky (a `flakyFailure`/`flakyError` rerun that passed); provenance is the first stack frame in the test class, else the report line |
| `build/test-results/<testTask>/TEST-*.xml` | Native Gradle Java Test tasks | the **same original JUnit case parser** as Surefire, including test status and file/line evidence; the test-task name stays in the report path |
| `target/effective-pom.xml`, else `target/project-db/effective-pom.xml` | `help:effective-pom`, else written by `ingest` from the module model | one per dependency and plugin block |
| `target/dependency-tree.txt` | `dependency:tree -DoutputFile=...` | one per dependency with the path that brings it in |
| `target/code-clustering/ATOM_HASHES.tsv`, `CLUSTERS.tsv` | `code-clustering:cluster` | one per atom (with its source line) and one per set of duplicated atoms, planes listed |
| `target/build-events/*.jsonl`, `target/build-events*.jsonl` | a build-events writer (the smart builder's JSONL events once that lane lands) | one per event |
| `-Dproject-db.buildLog=FILE` | the Maven console | `[ERROR]` lines (noise and rerun repeats removed) and `BUILD FAILURE`, with `file:[line,col]` when present |

### Gradle-only Java projects and modules

The standalone ProjectDbCli now discovers project and module directories with
`pom.xml`, `build.gradle`, `build.gradle.kts`, `settings.gradle`, or
`settings.gradle.kts` (without requiring a POM). It skips the build,
target, .gradle, .m3 and VCS/output directories **before descending into
them**, de-duplicates modules with multiple descriptors, and reads original
Gradle TEST-*.xml artifacts directly from each test task under
build/test-results. This bounded FileVisitor traversal avoids enumerating
large build trees or node_modules in order to reject their descriptors. It never starts Gradle or
reprocesses unchanged report bytes; the original ProjectDb/IndexStore and
SurefireParser remain the only data and parsing owners.

This is **evidence reuse**, not an implicit Maven interpretation of Gradle's
task graph, Gradle's native build cache, compile classpaths, binary ABI,
code coverage, or successful build status. Missing reports remain missing
evidence. Gradle reports without source stack frames keep the report
file and line; reports with resolvable stack frames point to the original
test source file. No Gradle API or XML parsing library is copied.

Example using the standalone CLI and already-built original JARs:

~~~sh
java -cp "maven-project-db-plugin.jar:m3-polyglot-maven-plugin.jar" \
  org.apache.maven.plugins.projectdb.ProjectDbCli ingest /path/to/gradle-project
java -cp "maven-project-db-plugin.jar:m3-polyglot-maven-plugin.jar" \
  org.apache.maven.plugins.projectdb.ProjectDbCli query /path/to/gradle-project \
  "which tests failed" --json
~~~

The optional Gradle-side `m3ProjectDbIngest` / `m3ProjectDbQuery`
entrypoints must supply a source-pinned Maven engine version that contains
this native Gradle discovery extension. The earlier Maven #184 JAR
supports Maven evidence but does **not** include this added report scanner.

**Incremental by content hash.** A source's key is its module and path; its SHA-256 covers the parser version, kind,
path and every file's bytes. An unchanged source is never parsed again, and the same content anywhere is parsed once
(`chunks/v1/<sha256>`). The ingest reports `N parsed, M unchanged`.

**Build status and derived facts.** A build is red when the log says `BUILD FAILURE` or a test failed or errored.
At each ingest, against the last green build: changed code atoms (exact-plane hash), changed test outcomes, changed
polyglot task digests, otherwise new/gone/changed sources (console logs excluded). Flaky tests: a Surefire rerun that
passed after failing, or an outcome that flipped between two builds while the module's clustering atoms were identical
(a test-only change also looks like such a flip).

## Retrieval

`Retriever` is CPU-only and deterministic: BM25 over chunk tokens (k1 1.2, b 0.75), the cosine of signed
feature-hashing vectors (1024 dimensions, unigrams and bigrams), exact facet filters written into the question as
`key:value` (`kind:flaky`, `module:core`, `status:failed`, `artifact:junit:junit`), and a fixed boost for the kind a
question asks about (fail/error -> build errors and failed tests, duplicate/clone -> duplicates, change -> changes,
bring/depend -> dependencies, flaky -> flaky tests). A question of facets alone (`kind:flaky`, `module:core`) returns
the chunks those facets accept, in file and line order. At most two answers come from one file. Ties are broken by
file, line and text. Answers are extractive: each line is stored chunk text plus `(file:line @build)`.

## MCP tools

`META-INF/project-db/mcp-tools.json` defines `project_db_query` and `project_db_ingest`. `tools/maven-mcp-server`
(being landed by its own lane, not on master yet) plugs them in with two calls:

```java
String definitions = org.apache.maven.plugins.projectdb.McpTools.definitions();          // tools/list
String resultJson = org.apache.maven.plugins.projectdb.McpTools.call(name, arguments, projectRoot, store);  // tools/call
```

Until then, the same tools are served over stdio (newline-delimited JSON-RPC 2.0, protocol `2025-06-18`):

```sh
java -cp maven-project-db-plugin.jar:m3-polyglot-maven-plugin.jar \
  org.apache.maven.plugins.projectdb.ProjectDbCli mcp /work/project
```

`ProjectDbCli` also has `ingest <root> [--build-log FILE]`, `query <root> <question> [--k N] [--json]` and `tools`;
all take `--store DIR` and `--persistent`.

## Proof

- `mvn verify` checks the license headers (RAT) and runs `ProjectDbTest`: builds of a two-module fixture with real
  polyglot task state, incremental reuse (unchanged, changed and removed sources), the five questions, determinism,
  facets (with and without question words), store locations, storage binding, MCP and CLI.
- `scripts/e2e.sh` runs a real multi-module Maven build (`src/test/projects/e2e`) twice (green, then red), ingests
  both, and checks the five questions and that every answer has file, line and build id.
- The existing `GradleProjectDbContractTestMain` now also runs the **real ProjectDbCli**
  on a Gradle-only project with a symlinked regular `linked/build.gradle`,
  three excluded output/vendor module descriptors and JUnit failures. It checks
  exact module discovery, one-source red ingestion, file/line 11 provenance,
  omission of excluded reports, zero-parse warm reuse, and non-invalidation
  after an ignored vendor descriptor changes. It uses the unchanged
  `ProjectDb`/`IndexStore` owner and runs through the original `mvn verify`
  execution; it is not a second parser or a Gradle task-cache test.
  On Linux a failure to create the symlink is an assertion failure
  rather than a silent skip; on other filesystems the subcase reports
  an explicit SKIP if symlinks cannot be created.

## Source-qualified CI concurrency

The project-DB Actions workflow uses **pull-request-only supersession**.
A newer commit to the *same* PR cancels obsolete runs under a stable
workflow/PR-number group; other pull requests have distinct groups.
Each `master` push and each manual `workflow_dispatch` receives its own
ref/run-ID group and cannot cancel another source-owner qualification
run. No test stages, Gradle checkout pins, Maven artifacts, JDK matrices,
SHA-256 receipts or acceptance gates are omitted by this policy.

This reduces stale pending PR reviews when many additive M3 branches
arrive together. It is only a scheduler policy: canceled historical
PR runs are **not passed tests**, and the completed Maven and Gradle
JDK17/21 evidence must still be checked for the *exact* source revision.

## Current source-qualified Gradle project-DB TestKit in Maven CI

The cross-fork workflow retains one Maven owner and one Gradle consumer
job per JDK **17 and 21**. The Maven job builds the existing original
polyglot IndexStore/M3IndexDB owner and project database JARs **once**,
runs their original contract suites and prepares a small artifact with
both JARs, a source-commit/JDK-lane receipt, the secure Java 17 JUnit
verifier and a SHA-256 manifest sealing every byte. The Gradle job
declares `needs: project-db-native-gradle`, downloads the *matching
JDK's* artifact and verifies all digests and the source/JDK identity
before executing any Gradle task.

The consumer now uses exactly reviewed, immutable Gradle commit
`c918c9b9dfc3b087987165c43b77dff63cc86fb1`. The original Gradle
9.9.1 wrapper and `m3/quality-bridge` are sparse-checked out **once**,
replacing two prior checkouts and two Gradle `test` invocations.
That version provides all nine source-owned project-DB tests:

| Source-owned Gradle suite | Cases | What it checks |
| --- | ---: | --- |
| `M3ProjectDbRealEngineTest` | 1 | original Maven build-log and provenance query |
| `M3ProjectDbGradleNativeRealEngineTest` | 1 | no-POM Gradle JUnit evidence and warm reuse |
| `M3ProjectDbTaskTest` | 6 | SHA-pinned CLI/task refusals, binary ABI, and configuration-cache replay |
| `M3ProjectDbPrunedSymlinkRealEngineTest` | 1 | Maven #208 module symlink, excluded vendor/output subtrees |

The execution uses `M3_PROJECT_DB_JAR` and
`M3_PRUNED_PROJECT_DB_JAR` for the **same original Maven project-DB
JAR**, and likewise `M3_POLYGLOT_JAR` and
`M3_PRUNED_POLYGLOT_JAR` for the same storage-engine JAR.
All four test classes run in one Gradle invocation. From the Maven
Actions workspace after both Maven JARs have been qualified:

~~~sh
(
  cd consumer-gradle
  ./gradlew --no-daemon --stacktrace --console=plain \
    -p m3/quality-bridge test \
    --tests 'io.synexia.gradle.quality.M3ProjectDbRealEngineTest' \
    --tests 'io.synexia.gradle.quality.M3ProjectDbGradleNativeRealEngineTest' \
    --tests 'io.synexia.gradle.quality.M3ProjectDbTaskTest' \
    --tests 'io.synexia.gradle.quality.M3ProjectDbPrunedSymlinkRealEngineTest' \
    --rerun-tasks
)
sh qualified-maven-jars/verify-gradle-testkit-results.sh \
  consumer-gradle/m3/quality-bridge/build/test-results/test current-nine
~~~

The `current-nine` acceptance mode requires **nine exact testcase
names**, exactly four suites, non-skipped real-engine cases, no failures
or errors, and no unreviewed project-DB suites. The secure Java17 DOM
reader refuses malicious DTDs and external entities, wrong case
ownership, duplicate cases and missing or oversized JUnit XML.
This is a report **admission gate**, not another test runner or parser
for the Maven database.

### Historical qualification and compatibility

The previously reviewed Gradle commit
`e8dbf558fcc8a83c580914fbcc4cb10fe4281eb6` executed six real
TestKit cases on each JDK in Maven Actions run
[44237386509](https://github.com/hsoliwal/maven/actions/runs/44237386509).
The downloaded original JUnit artifacts confirmed **6/6, zero skips**
on JDK17 and again on JDK21. Its Maven-owner jobs failed at an old
two-matrix workflow count guard, so **the whole historical workflow
was not green**. A distinct Maven project-DB source-owner run
[44233563771](https://github.com/hsoliwal/maven/actions/runs/44233563771)
passed the original Maven JDK17/21 tests.

For reproducibility the same Java 17 verifier still supports the
default **six-case** historical mode and standalone
`pruned-symlink` one-case mode. These modes were actually executed
against the historical JUnit archives and retained test fixtures.
The new `current-nine` mode compiled with `javac --release 17
-Xlint:all -Werror` and passed a generated nine-case JUnit fixture;
nine separate malformed/skipped/missing/duplicated reports were
refused. **Those synthetic verifier tests do not mean that the new
nine-case Gradle TestKit has run successfully on GitHub Actions.**

### Limits and provenance

The original Maven JARs and receipts are retained for seven days in
Actions artifacts; the actual Gradle XML/HTML reports for fourteen
days. The immutable Gradle source pin is never a mutable `master`
checkout: future API changes require a reviewed revision and new JDK
qualification. Maven still owns the store, the project DB, SHA-256
catalogs and CPU retrieval; Gradle still owns its TestKit/task model.
No additional compiler ABI, task/cache skip, JNI provider,
configuration-cache correctness outside these tests, or Windows/macOS
equivalence is inferred from this integration.

## Attribution

`Retriever` and `Tokens` adapt the BM25 formula and token rule of Synexia's `Bm25Index` (M3JDK21,
`jdk.m3.llm.projection`), and `HashVectors` the FNV-1a constants of Synexia's `M3LaneFnv1a` (M3JDK21 `m3/indexdb`).
Synexia files are offered under Apache-2.0 OR EPL-2.0; this module takes them under Apache-2.0. Decisions per
candidate: [FOSS_REUSE_DECISION.tsv](FOSS_REUSE_DECISION.tsv).
