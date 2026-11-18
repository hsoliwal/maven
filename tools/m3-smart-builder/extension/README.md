<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Smart Maven core extension: build advice, polyglot execution and persistent module state

`io.synexia:smart-maven-extension` is a Maven core extension with two lifecycle participants:

- `PolyglotLifecycleParticipant`: with `-Dm3.polyglot.lifecycle=true`, every project that has an `m3-build.tsv`
  runs its targets named `validate`, `compile`, `test`, `package` or `verify` in that Maven phase through
  `io.synexia:m3-polyglot-maven-plugin` (execution `polyglot-<phase>`), so one `mvn verify` builds Java and
  non-Java modules alike with their own tools; see the polyglot plugin's README for `m3.polyglot.incremental`.
  `src/it/test_polyglot.sh` proves it with a reactor of a Java module and a Python-built module.
- `InlineClusteringSpy` (package `org.apache.maven.extensions.clustering`): atomizes every Java compilation inline.
  See [Inline code clustering](#inline-code-clustering).
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
| `m3.lifecycle.backend=embedded` | Original source-inlined M3IndexDB in one portable ZIP image; default |
| `m3.lifecycle.backend=file` | Bounded sealed atomic file image through the same M3 transaction owner |
| `m3.lifecycle.backend=sqlite` | Transactional SQLite image in `.m3/module-state.sqlite`; requires an authentic SQLite JDBC driver on the extension classpath |
| `m3.lifecycle.backend=h2` | Transactional H2 image in `.m3/module-state.mv.db`; requires an authentic H2 JDBC driver on the extension classpath |
| `m3.lifecycle.backend=ext-<id>` | Local backend registered through `M3ModuleDbBackendProvider`; preserves the original `id/read/write` service contract |
| `m3.lifecycle.backend=m3db` or `native` | Explicit canonical MIndexDB artifact API; requires that runtime on the extension classpath, with no fallback |
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

## Inline code clustering

`InlineClusteringSpy` is an event spy. When a `compile` or `testCompile` execution of maven-compiler-plugin starts,
the project's dependencies are already resolved and the mojo is not yet configured. At that point the spy adds the
javac plugin `CodeClustering` of `org.apache.maven.plugins:maven-code-clustering-plugin` (see
`tools/code-clustering/README.md`) to that execution's configuration. The POM model and the project's dependencies
are not changed. Every compilation then writes the atom tables while it compiles:

- main sources: `${project.build.directory}/code-clustering/inline`;
- test sources: `${project.build.directory}/code-clustering/inline/test`;
- other executions of the two goals (a multi-release `src/main/java9` execution, for example): an
  `execution-<id>` directory below those, so two executions never merge into one table set;
- sources that annotation processors generate: a `generated` directory below each, so the main tables hold the
  hand-written sources only.

`inline` keeps these tables apart from the `cluster` goal's `target/code-clustering`. No goal runs and no POM
changes.

| Property (user, POM or system) | Effect | Default |
|---|---|---|
| `clustering.inline` | on only when absent or `true`, `yes`, `on`, `1` (any letter case); any other value (`false`, `0`, `no`, `off`, empty) turns it off. A POM property turns off one module | on |
| `clustering.pluginJar` | the plugin jar to use; it must register the javac plugin | the plugin in the local repository |
| `clustering.pluginVersion` | the version looked up in the local repository | `0.1.0-SNAPSHOT` |
| `clustering.minTokens`, `clustering.radius`, `clustering.fileThreshold` | passed to the plugin | the plugin's |

The plugin jar must hold `META-INF/services/com.sun.source.util.Plugin` listing
`org.apache.maven.plugins.clustering.InlineClustering`, and that class: javac fails every compilation
(`plug-in not found: CodeClustering`) when `-Xplugin` names a plugin its processor path does not register. A jar
without them (a `0.1.0-SNAPSHOT` built from a tree without the javac plugin) is logged once and left out; the build
runs as usual. So is a jar whose `InlineClustering.class` is compiled for a newer Java than Maven runs on (javac's
service loader would throw `UnsupportedClassVersionError`), and a jar whose path holds the path separator (`;` on
Windows: the processor path would split there). A jar that passes these checks but misses another class disables the
javac plugin with one warning; the compilation stays green.

How the plugin reaches javac: javac loads plugins from the annotation processor path.

- With `annotationProcessorPaths` configured, the plugin's coordinates are added to them. maven-compiler-plugin
  resolves them through the reactor first, then the local repository. So the spy leaves such an execution alone,
  with the reason logged, when the reactor builds `maven-code-clustering-plugin` (its module's output has no javac
  registration), or when the local repository's jar does not register the plugin.
- Otherwise javac would look for processors on the class path. The spy adds `--processor-path` with the plugin jar
  followed by the execution's own class path, so javac finds the same processors as before.
- Modular sources (`module-info.java`) without `annotationProcessorPaths` get the plugin jar alone as processor path:
  their class path holds module path entries too, whose processors did not run before. If a processor is named or a
  class path element registers one, the execution is left alone.
- On Java 23 and newer, a processor path switches processing on, so the spy adds `-proc:none` unless `proc` or
  `-proc:` is configured or processors are named (`annotationProcessors`, `-processor`): named processors switch
  processing on themselves and must keep running.

The plugin gets the execution's source roots (`roots`) and generated sources directory (`generated`), all paths as
file URIs, which hold no white space (javac splits the plugin argument at white space). It gets `full=true` unless
`useIncrementalCompilation` is `false`: maven-compiler-plugin then compiles all sources or none, so the tables are
replaced and a file no longer compiled (a new `<excludes>`) drops out. With `useIncrementalCompilation=false` it
compiles stale sources only, and the plugin merges. Before every execution it wires, the spy calls the plugin's
`InlineClustering.prune` (from a class loader of the plugin jar, closed afterwards), which drops the rows of sources
that no longer exist: after a source is deleted, a merging execution may find nothing stale, and one that compiles
all sources or none runs no javac when every source of its roots is gone. Prune writes only when a recorded source
is gone. A source newly excluded in the merging mode keeps its rows until a full compilation.

When the spy leaves an execution alone, for any reason below, the off switch and a missing plugin jar included, it
removes that execution's earlier inline tables (`StaleTables`): every file directly in its table directory and in its
`generated` directory, and directories an interrupted write left (`.tables-*`); the `test` and `execution-*`
directories of other executions stay. Otherwise the tables of an earlier build would stay as if this one had
written them, and a later wired build that finds the classes up to date (`Nothing to compile`) would not replace
them. Absent tables are honest; stale ones are not. The INFO line of the skip says `(earlier inline tables
removed)`; when the switch is off, a line is logged only if tables were removed. A merging execution
(`useIncrementalCompilation=false`) would merge its next compilation into no base, so its directory is marked
`INCOMPLETE.txt`; the plugin keeps the marker through merges and removes it when a full compilation replaces the
set (`mvn clean` removes the directory). An execution whose own arguments add `-Xplugin:CodeClustering` keeps its
tables: that configuration writes them.

It leaves an execution alone, logged at INFO with the reason, when the plugin cannot join it safely:

- another `compilerId`;
- a maven-compiler-plugin whose mojo is written against the Maven 4 plugin API (the 4.x line, such as
  4.0.0-beta-5), or one without the `compilerArgs` parameter (before 3.1). Both are told by the mojo's descriptor,
  not by the version: on Maven 4 `MojoDescriptor.getRole()` is `org.apache.maven.api.plugin.Mojo` for a Maven 4 API
  mojo. So any Maven 3 API build is wired whatever its version, such as the fork's own `4.2.0-SNAPSHOT`, which is the
  3.16.0 code and which the fork's `-Pplugins` distribution binds by default. The Maven 4 API line resolves its class
  path through the Maven 4 API, which `MavenProject.getCompileClasspathElements()` does not see at that point
  (class-path processors such as Lombok would be lost), and compiles single sources in its incremental modes; it is
  not wired until a wiring through that API exists;
- `fork` or `executable` (a forked javac), or a JDK toolchain (a toolchain javac): `jdkToolchain`, or a toolchain
  that any plugin stored in the build context (`ToolchainManager.getToolchainFromBuildContext`, which covers
  maven-toolchains-plugin and other selectors). Maven 4's toolchain manager looks the project up in the mojo
  execution scope, which MojoStarted precedes, and throws there; the spy then reads the build contexts where that
  manager keeps them (the session data, by project; `ToolchainContext`). In both cases the javac version is unknown;
- Maven running below Java 17;
- a processor path given in `compilerArgs`, `compilerArgument` or the `compilerArguments` map (`processorpath`,
  `processor-path`, `processor-module-path`, any number of dashes): javac uses the last processor path it is given,
  so adding one would hide the project's processors;
- `-Xplugin:CodeClustering` already configured;
- `annotationProcessorPaths` that cannot name the plugin (see above);
- modular sources with processors named or registered on the class path;
- Java sources too large for this compilation's share of the heap: the module's `.java` bytes times 64 above
  `Runtime.maxMemory()` divided by the build threads (`-T`). The plugin keeps every atom of a compilation and
  clusters them at the end, while javac still holds its trees. On 4,000 small classes (1.1 MB of source, 12,000
  atoms; javac through `javax.tools` as maven-compiler-plugin runs it, JDK 17.0.19, 2026-11-18) javac's heap floor
  was above 40 MB and at most 48 MB alone, above 56 MB and at most 64 MB with the plugin (the plugin's peak is the
  clustering at the end of the compilation, after the atom table was written row by row). 64 bytes per source
  byte is the measured floor with the plugin, rounded up. The log line says how much heap would be needed.

Without a usable plugin jar, the build runs as usual and the spy logs why, once. Any failure in the spy is logged and
leaves the execution as it was (only virtual machine errors other than `StackOverflowError` propagate). The javac
plugin itself never fails a compilation: it holds its atoms softly between javac events, so the collector drops them
before javac would run out of heap, and the plugin then writes no tables. Tables are written when javac runs; a
module that maven-compiler-plugin finds up to date keeps the tables it has. Direct goal calls such as
`mvn compiler:compile` (`default-cli`) start through the same event and are wired too; that path is not tested.

In the fork's distribution the extension is in `lib/ext`, so clustering is on by default for every build it runs,
once `maven-code-clustering-plugin` is in the local repository (the boot repository does not ship it). A project that
pins no compiler gets the distribution's `maven-compiler-plugin:4.2.0-SNAPSHOT`, which is wired (see above).

## Test

`src/it/test_extension.sh <extension.jar> <java-21-home> [<java-17-home>]` runs real offline Maven builds: the
antrun step failing a reactor on dependency resolution without the extension, the hook bound in all three
projects with it, off without the property, a declared plugin left alone, a missing plugin and Maven on Java 17
logged and skipped.

`src/it/test_inline_clustering.sh <extension.jar> <plugin.jar> <java-home> [mvn] [local-repository]` builds a
scratch reactor of thirteen jar modules offline, under a directory with two consecutive spaces, and checks the
following:

- `mvn compile` writes each module's `target/code-clustering/inline` tables with no goal. They are identical to the
  standalone `cluster` scan of `src/main/java` on the same JDK, also for a modular module, for default-package
  files of the same name in two directories, for a package named `build`, and for a module with two source roots
  (against the scan of both); `JNI.tsv` holds the native method;
- a module with `-Xlint:all -Werror` stays green;
- Lombok still runs, from the class path in one module and from `annotationProcessorPaths` in another; a processor
  named in `annotationProcessors` and one found on the class path still run, and their generated sources are in
  `inline/generated`, identical to the scan of `target/generated-sources/annotations`; a module whose processor
  path is in the `compilerArguments` map is left alone, logged, and its processor still runs;
- `mvn test-compile` writes `target/code-clustering/inline/test`;
- a changed source reaches the tables, and a newly excluded source leaves them; with
  `useIncrementalCompilation=false`, a deleted source leaves them although javac does not run;
- `-Dclustering.inline=false`, `FALSE`, `0`, `no`, `off` and empty, and a POM property in one module, turn it off;
- an output path that is a file leaves the build green with a warning;
- a missing plugin jar, a jar without the javac registration (given, or in the local repository), a jar compiled
  for a newer Java and a jar path holding the path separator are logged and left out; a jar missing a class leaves
  the build green with one warning (one, also when the class is needed for every unit); the build stays green;
- the plugin is found in the local repository, also by coordinates; `-T 4` stays green;
- a reactor that builds `maven-code-clustering-plugin` stays green: `annotationProcessorPaths` are left alone, with
  the reason logged, other modules are wired, also with `-T 4`;
- with a scratch local repository: a plugin other than maven-toolchains-plugin (built and installed there) stores a
  JDK toolchain in the build context, and the execution is left alone, logged;
- 840 KB of sources under `-Xmx128m -T 4` are left alone, logged, and the build stays green;
- with `JDK23_HOME` set to a JDK 23 or newer: named and class-path processors still run, and no `-proc:none` is
  added beside `-processor`;
- with `MVN4` set to a Maven 4 `mvn`: maven-compiler-plugin `4.0.0-beta-5` is left alone, logged, and Lombok on its
  class path still runs.

With a local repository, `REPO_TAIL=<repository>` chains another one behind it (`maven.repo.local.tail`), so the
plugin can be installed in a scratch repository while the rest comes from the usual one. Set `KEEP_LOGS=<dir>` to
keep the Maven logs. The whole script also runs with a Maven 4 `mvn` as `[mvn]` (its default maven-compiler-plugin
is 3.16.0).
