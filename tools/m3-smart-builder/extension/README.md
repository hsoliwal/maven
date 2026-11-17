<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Smart Maven core extension: build advice and any-language builds in every project, no POM change

`io.synexia:smart-maven-extension` is a Maven core extension with two lifecycle participants:

- `PolyglotLifecycleParticipant`: with `-Dm3.polyglot.lifecycle=true`, every project that has an `m3-build.tsv`
  runs its targets named `validate`, `compile`, `test`, `package` or `verify` in that Maven phase through
  `io.synexia:m3-polyglot-maven-plugin` (execution `polyglot-<phase>`), so one `mvn verify` builds Java and
  non-Java modules alike with their own tools; see the polyglot plugin's README for `m3.polyglot.incremental`.
  `src/it/test_polyglot.sh` proves it with a reactor of a Java module and a Python-built module.
- `InlineClusteringSpy` (package `org.apache.maven.extensions.clustering`): atomizes every Java compilation inline.
  See [Inline code clustering](#inline-code-clustering).
- `AdviceLifecycleParticipant`, below.

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
mvn -f tools/m3-smart-builder/extension/pom.xml install
export MAVEN_OPTS="-Dmaven.ext.class.path=$HOME/.m2/repository/io/synexia/smart-maven-extension/0.1.0-SNAPSHOT/smart-maven-extension-0.1.0-SNAPSHOT.jar"
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
compiles stale sources only, and the plugin merges. After a source is deleted such an execution may find nothing
stale and not run javac, so before it starts the spy calls the plugin's `InlineClustering.prune` (from a class
loader of the plugin jar, closed afterwards), which drops the rows of sources that no longer exist. A source newly
excluded in this mode keeps its rows until a full compilation.

It leaves an execution alone, logged at INFO with the reason, when the plugin cannot join it safely:

- another `compilerId`;
- maven-compiler-plugin other than 3.1 or a newer 3.x. Version 4.x resolves its class path through the Maven 4 API,
  which `MavenProject.getCompileClasspathElements()` does not see at that point (class-path processors such as Lombok
  would be lost), and compiles single sources in its incremental modes; it is not wired until a 4.x wiring exists;
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
plugin itself never fails a compilation. Tables are written when javac
runs; a module that maven-compiler-plugin finds up to date keeps the tables it has. Direct goal calls such as
`mvn compiler:compile` (`default-cli`) start through the same event and are wired too; that path is not tested.

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
