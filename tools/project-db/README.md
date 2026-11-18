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

## Real Gradle consumer TestKit in Maven's CI

The project database's cross-fork workflow retains two JDK 17/21
matrices: the source-owned Maven engine/DB contract and the **actual
Gradle TestKit consumer**. Each JDK Maven owner job builds and verifies
the original Maven polyglot `IndexStore` and project database **once**.
Only after those tests succeed does the job stage both original JARs,
compute a `SHA256SUMS` receipt and upload them together in
`qualified-maven-jars-jdk-<java>`. The matching Gradle job declares
`needs: project-db-native-gradle`, downloads its own JDK's qualified
artifacts, and runs `sha256sum -c SHA256SUMS` **before** executing the
consumer. It no longer reruns Maven `install` and `verify` a second time.

The consumer checks out **reviewed immutable Gradle revision**
`e8dbf558fcc8a83c580914fbcc4cb10fe4281eb6`, with a sparse
checkout of only its wrapper and quality-bridge sources. The Gradle
wrapper pins the 9.9.1 binary distribution by SHA-256.

The Gradle job sets `M3_POLYGLOT_JAR` and `M3_PROJECT_DB_JAR`
to the exact downloaded, SHA-256-verified Maven JAR bytes before executing:

~~~sh
./gradlew --no-daemon --stacktrace --console=plain \
    -p m3/quality-bridge test --tests '*M3ProjectDb*' --rerun-tasks
~~~

This runs Gradle's original TestKit cases for an actual source-pinned
Maven engine, including ingestion of a **Gradle-only** JUnit failure
without a POM, source-line/build provenance, and changed/unchanged
source handling. The wrapper and its plugins still need network access
on the Actions runner. Failed or successful TestKit XML reports are
retained as workflow artifacts for review.

The two source-built JAR artifacts and `SHA256SUMS` are retained
for seven days; Gradle TestKit XML/HTML reports for fourteen days.
This handoff reduces duplicate Maven build computation without creating
a new storage owner or allowing the Gradle job to continue after a
failed Maven owner job. A receipt authenticates the transferred artifact
bytes; it does not prove a compiler ABI, a native toolchain closure,
a Gradle build-cache hit, or behavioral equivalence on Windows/macOS.

The immutable Gradle SHA is intentionally not `master`. A newer
Gradle bridge source revision requires an explicit pin-update and
fresh JDK 17/21 acceptance. Maven's own project-DB tests, Gradle's
normal build cache, and Gradle TestKit are separate authorities; no
cross-fork test automatically grants build-skip or release permission.

## Attribution

`Retriever` and `Tokens` adapt the BM25 formula and token rule of Synexia's `Bm25Index` (M3JDK21,
`jdk.m3.llm.projection`), and `HashVectors` the FNV-1a constants of Synexia's `M3LaneFnv1a` (M3JDK21 `m3/indexdb`).
Synexia files are offered under Apache-2.0 OR EPL-2.0; this module takes them under Apache-2.0. Decisions per
candidate: [FOSS_REUSE_DECISION.tsv](FOSS_REUSE_DECISION.tsv).
