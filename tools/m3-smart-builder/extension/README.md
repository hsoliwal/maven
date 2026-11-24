<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Smart Maven core extension: build advice, polyglot execution and persistent module state

`io.synexia:smart-maven-extension` is a Maven core extension with two lifecycle participants:

- `PolyglotLifecycleParticipant`: with `-Dm3.polyglot.lifecycle=true`, every project that has an `m3-build.tsv`
  runs its targets named `validate`, `compile`, `test`, `package` or `verify` in that Maven phase through
  `io.synexia:m3-polyglot-maven-plugin` (execution `polyglot-<phase>`), so one `mvn verify` builds Java and
  non-Java modules alike with their own tools; see the polyglot plugin's README for `m3.polyglot.incremental`.
  `src/it/test_polyglot.sh` proves it with a reactor of a Java module and a Python-built module.
- `AdviceLifecycleParticipant`, below.

## Persistent state for ordinary Maven Java goals

`M3LifecycleExecutionStrategy` implements Maven's existing `MojosExecutionStrategy` SPI. With
`-Dm3.lifecycle.incremental=true`, it stores successful goal receipts in the same module database
used by the polyglot runner. The embedded backend owns one `.m3/module.m3db` image per module;
small sibling lock/session files coordinate writers and detect interrupted transactions.
Lifecycle and polyglot receipt keys have separate namespaces. Disabling lifecycle reuse revokes
only lifecycle receipts, so it does not erase an enabled polyglot build's state.

The original Maven plugin executes every cache miss. Reuse is admitted for this reviewed matrix:

| Plugin | Versions | Reusable goals |
| --- | --- | --- |
| maven-resources-plugin | 3.3.1 | resources, testResources |
| maven-compiler-plugin | 3.13.0, 3.14.1 | compile, testCompile |
| maven-jar-plugin | 3.4.1, 3.4.2 | jar |

Parent module fingerprints also exclude output directories of nested Maven
reactor projects, using the original `MavenSession` project metadata rather
than guessing by directory name. Nested project sources and POMs still
participate in conservative parent input identity; explicit dependency
artifacts remain hashed through the original goal-closure path. A parent
source/resource root that contains (or is contained in) a nested reactor
output directory is **not qualified for reuse**: that overlap must take
the original Maven execution path rather than hiding real input bytes. This avoids
re-hashing generated child module output trees as parent-module inputs.

For measured qualification, Maven's existing debug logger now reports the
actual `M3DB SHA-256 before/after` and `outputs before/after` counts as
`files` and `payload bytes` for eligible lifecycle goals. For example,
run `mvn -X -Dm3.lifecycle.incremental=true verify` and inspect the M3DB
scan lines. These are actual content reads by the Maven SHA scanner, not
elapsed-time estimates or metadata-derived claims; the counts do not alter
any existing receipt digest or the one-file-per-module M3DB format.
A verified unchanged cross-process invocation still reads source bytes
until M3File supplies a qualified gap-free generation contract.

The module-root fingerprint excludes database-owned files and directories
under `.m3`, the `.m3-watch` checkpoint and `.git` at any nested reactor depth,
without hashing the M3DB parent directory marker. Ordinary user files such as
`.m3/user-config.json` remain content-hashed; explicitly declaring internal
state inputs is refused. In particular, writes to `.m3/module.m3db` or the separate
M3File checkpoint `.m3-watch/state.bin` must not invalidate the source
generation of another goal. Ordinary source and dependency bytes remain hashed.
The `m3:machine-fingerprints` MIndexDB analysis store
(`.m3/maven/mindexdb/**` and its sibling `.m3/maven/mindexdb.lock`) is
M3-owned state too: it is excluded at any reactor depth, the `.m3/maven`
directory marker is not hashed, and analysis-cache writes do not invalidate
the lifecycle receipts of that module or its parents. Ordinary user files
beside it, such as `.m3/maven/notes.txt`, remain content-hashed.

The adapter resolves the goal's concrete dependencies through Maven, preserves classpath order,
and hashes dependency bytes before looking up success. Its key also covers module sources and
resources, effective POM/configuration, ancestor POMs, Maven settings/toolchains, environment,
Java runtime contents, plugin implementation/dependency bytes and explicitly declared extra inputs.
Archive inputs include the contents of both `manifestFile` and `pomPropertiesFile`, even outside the
module or under its build directory.
It verifies actual output contents on every lookup. A hit restores the main JAR artifact when
appropriate, generated-source root bookkeeping and Maven lifecycle phase state, allowing reactor
dependents to resolve the same outputs. Each goal has its own lease, including when Maven 4 supplies
phase fragments; independent modules can build concurrently.

On a content miss, the compiler's `inputFiles.lst` is invalidated while retaining `createdFiles.lst`.
This makes the original incremental compiler re-evaluate changed/deleted sources even when timestamps
were restored. Resources use `overwrite=true` and JAR creation uses `forceCreation=true` for that
execution, then the original configuration object is restored. These local overrides prevent a second
timestamp shortcut from blessing stale bytes after M3 has already detected a content change.
The original goals still own resource deletion/cleanup behavior; `clean` continues to run normally.

**Tests and publication remain live.** Surefire, Failsafe, install and deploy always execute through
Maven's original runner. A test failure still fails the build even if compilation was reused. Unknown
plugins, direct CLI goals, aggregators, forked lifecycles, custom packaging/build extensions, attached
JAR classifiers, unsupported plugin versions and compiler toolchains/argument overrides run normally.
Maven 4's own lazy consumer-POM attachment remains the same live core-owned object; it is recognized
without generating or replacing its file. Custom attachments with that classifier are still declined.
Unknown producers disable lifecycle reuse for the remainder of that project's invocation. Annotation
processors require independently complete declarations; automatic processor providers in dependencies
or the project's own compile/test outputs are declined. Uninspectable compiler classpath archives also
run normally. Explicit `proc=none` remains eligible. The default generated annotation roots are hashed as inputs and outputs, so changes
during compilation prevent publication. Other generated roots and custom resource/filter declarations
are conservatively uncached. Standard compiler configurations must use incremental compilation.

### Activation and portable backend choices

Build and install the state owner before the extension:

```sh
mvn -f tools/m3-polyglot-maven-plugin/pom.xml install
mvn -f tools/m3-smart-builder/extension/pom.xml install
```

The fork distribution includes both JARs in `lib/ext`. From a consuming Java project:

```sh
mvn -Dm3.lifecycle.incremental=true verify
mvn -Dm3.lifecycle.incremental=true verify
```

With a stock Maven 3.9 runtime, load both JARs through `-Dmaven.ext.class.path` (the platform path
separator joins their absolute paths). No consumer POM mutation is needed. The project still pins the
reviewed standard plugin versions; other versions keep their original behavior.

| Property | Meaning |
| --- | --- |
| `m3.lifecycle.incremental` | Explicit opt-in; default false |
| `m3.lifecycle.backend=embedded` | One portable `.m3/module.m3db` ZIP image, written through the current `jdk.m3.db.M3DB` owner on M3JDK, the legacy owner on older images, or the compatible library on stock JDK; default |
| `m3.lifecycle.backend=file` | Bounded sealed atomic file image through the same M3 transaction owner |
| `m3.lifecycle.backend=sqlite` | Transactional SQLite image in `.m3/module-state.sqlite`; requires an authentic SQLite JDBC driver on the extension classpath |
| `m3.lifecycle.backend=h2` | Transactional H2 image in `.m3/module-state.mv.db`; requires an authentic H2 JDBC driver on the extension classpath |
| `m3.lifecycle.backend=hsqldb` | HSQLDB 2.7.4 file database in `.m3/module-state-hsqldb.*`, synchronized log commits and strict recovery; requires `org.hsqldb.jdbc.JDBCDriver` on the extension classpath |
| `m3.lifecycle.backend=ext-<id>` | Local backend registered through `M3ModuleDbBackendProvider`; preserves the original `id/read/write` service contract |
| `m3.lifecycle.backend=ext-derby-v1` | Optional Derby 10.16.1.1 embedded JDBC backend with `derby` + `derbytools` supplied to this core extension's realm. Uses the original M3 sealed image and shuts down the file database between sessions |
| `m3.lifecycle.backend=m3db` or `native` | Explicit native artifact store under `.m3/maven-build-db/`; binds current M3JDK `jdk.m3.db.M3DB` first, legacy `jdk.m3.indexdb.M3IndexDB` next, or an installed MIndexDB donor; never falls back to the library copy |
| `m3.lifecycle.backend=legacy` | Accepted compatibility alias for embedded; old per-task stamps do not authorize reuse |
| `m3.lifecycle.inputs` | Comma-separated extra file/directory inputs, absolute or relative to the module |

All backends share `.m3/module-state.run.lock`, batch revocation, sealed-image validation,
backend-generation isolation and interrupted-session recovery. Switching backend starts cold.
SQLite and H2 store the same bounded integrity-checked receipt image in one transactional snapshot row;
they add no second cache key or lifecycle scheduler. JDBC drivers are optional caller-owned runtimes,
never silently downloaded or selected as fallbacks. See the state owner's
[`portable database contract`](../../m3-polyglot-maven-plugin/docs/M3_PORTABLE_DATABASES.md)
for driver loading, filesystem restrictions, durability and provider requirements.
Environment values are hashed, not stored as plaintext in the
database. The cache contains receipts, not a backup of build outputs: deleted outputs cause execution.
Caching cannot infer network, time, nondeterminism or undeclared external file effects. Use explicit
polyglot tasks for tools whose inputs fall outside the recognized Maven goal contracts.

### Executable acceptance

`src/it/verify_module_state.py` builds a real two-module Java reactor in separate Maven processes using
an explicit local repository. It covers same-timestamp source/resource edits, transitive consumer
invalidation, output/JAR tampering, corrupt state, always-run tests (including failure), disabled mode,
parallel reactors, install, clean, file backend reuse and stale JAR/skipIfEmpty behavior. It retains all
logs, fixtures and an acceptance JSON. Its additional archive and self-hosted processor regressions
exercise external `pomPropertiesFile` changes and javac discovery from the project's own outputs.
Missing offline dependencies fail the gate. `--prime-repository` permits dependency resolution only
for the first, disabled baseline build; all incremental scenarios remain offline.
Supplying `--sqlite-jar` and `--h2-jar` adds six scenarios per actual engine: cold and fresh-JVM warm
builds, same-timestamp source/downstream invalidation, settled reuse, an original failing test and
recovery. Receipts bind each supplied JAR's SHA-256. An omitted driver is recorded as `NOT_RUN`.

```sh
python3 tools/m3-smart-builder/extension/src/it/verify_module_state.py \
  --maven /path/to/maven/bin/mvn --repository /path/to/approved/repository \
  --extension-jar tools/m3-smart-builder/extension/target/smart-maven-extension-0.1.0-SNAPSHOT.jar \
  --state-jar tools/m3-polyglot-maven-plugin/target/m3-polyglot-maven-plugin-0.1.0-SNAPSHOT.jar \
  --sqlite-jar /path/to/sqlite-jdbc.jar --h2-jar /path/to/h2.jar \
  --work /path/to/new-empty-acceptance-directory
```

These are executable gates, not a claim that every Maven/JDK/OS combination has run. Actual receipts
and source provenance live with the canonical Synexia recipe identified by the product's passive receipt.

When a build runs with `-Dchromellm.advice=true`, the advice participant binds `m3:build-hook` of
`io.synexia:m3-smart-maven-plugin` to every project of the build:

| Execution | Lifecycle phase | Hook phase |
| --- | --- | --- |
| `chromellm-pre-build` | `validate` | `pre-build` |
| `chromellm-post-build` | `verify` | `post-build`, outcome `success` |

with `failOnError=false`, so advice never changes a build result. Nothing is written to any POM. Provider and
summarizer come from the usual `m3.smart.*` properties (`-D`, or a profile in `~/.m2/settings.xml`); without a
provider the hook records the CPU facts only.

- A project that declares `m3-smart-maven-plugin` itself keeps its own binding.
- The extension removes the `chromellm-pre-build` and `chromellm-post-build` executions of `maven-antrun-plugin`
  (the version 2 `chromellm-advice` POM profile) from the projects it binds, so the advice does not run twice.
  That Ant form also resolved each project's dependencies first and failed builds whose reactor siblings were not
  packaged yet; the hook resolves none.
- It does nothing but log why when Maven runs below Java 21 (the plugin's level) or when the plugin is not in the
  local repository. It is compiled for Java 17, so it loads under every Maven runtime in use.

## Use

```sh
mvn -f tools/m3-smart-builder/pom.xml install
mvn -f tools/m3-polyglot-maven-plugin/pom.xml install
mvn -f tools/m3-smart-builder/extension/pom.xml install
export MAVEN_OPTS="-Dmaven.ext.class.path=$HOME/.m2/repository/io/synexia/smart-maven-extension/0.1.0-SNAPSHOT/smart-maven-extension-0.1.0-SNAPSHOT.jar:$HOME/.m2/repository/io/synexia/m3-polyglot-maven-plugin/0.1.0-SNAPSHOT/m3-polyglot-maven-plugin-0.1.0-SNAPSHOT.jar"
mvn -Dchromellm.advice=true -Dm3.smart.llmBackend=bridge \
    -Dm3.smart.chromeLlmJar=/path/to/chromellm-standalone.jar verify
```

`MAVEN_OPTS` set for the user makes every Maven build of that user able to take advice; the property keeps it
off until asked for. Reports: `target/m3-smart/{pre-build,post-build}.json` in each project.

The fork's own distribution carries the extension built in: the root reactor builds this module and
`apache-maven` packages it into `lib/ext`, so the fork's `mvn` needs no `MAVEN_OPTS`, only
`-Dchromellm.advice=true` (and `m3-smart-maven-plugin` installed). Maven 4 loads it through its compatibility
layer; a second copy from `MAVEN_OPTS` does not bind the hook twice.

```sh
mvn -DskipTests install -pl apache-maven -am      # apache-maven/target/apache-maven-<version>-bin.zip
```

## Test

`src/it/test_extension.sh <extension.jar> <java-21-home> [<java-17-home>]` runs real offline Maven builds: the
antrun step failing a reactor on dependency resolution without the extension, the hook bound in all three
projects with it, off without the property, a declared plugin left alone, a missing plugin and Maven on Java 17
logged and skipped.
