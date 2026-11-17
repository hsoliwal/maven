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

- main sources: `${project.build.directory}/code-clustering`;
- test sources: `${project.build.directory}/code-clustering/test`.

No goal runs and no POM changes.

| Property (user, POM or system) | Effect | Default |
|---|---|---|
| `clustering.inline` | `false` turns it off (a POM property turns off one module) | on |
| `clustering.pluginJar` | the plugin jar to use | the plugin in the local repository |
| `clustering.pluginVersion` | the version looked up in the local repository | `0.1.0-SNAPSHOT` |
| `clustering.minTokens`, `clustering.radius`, `clustering.fileThreshold` | passed to the plugin | the plugin's |

How the plugin reaches javac: javac loads plugins from the annotation processor path.

- With `annotationProcessorPaths` configured, the plugin's coordinates are added to them. This needs the plugin in
  the local repository.
- Otherwise javac would look for processors on the class path. The spy adds `--processor-path` with the plugin jar
  followed by the execution's own class path, so javac finds the same processors as before. On Java 23 and newer,
  a processor path switches processing on, so the spy adds `-proc:none` unless `proc` or `-proc:` is configured.

It leaves an execution alone, logged at INFO with the reason, when the plugin cannot join it safely:

- another `compilerId`;
- `fork` or `executable` (a forked javac), or `jdkToolchain` or maven-toolchains-plugin (a toolchain javac). In both
  cases the javac version is unknown;
- Maven running below Java 17;
- a processor path given in `compilerArgs`;
- `module-info.java` in the source roots (modular builds take processors from the module path);
- `-Xplugin:CodeClustering` already configured.

Without the plugin jar, the build runs as usual and the spy logs why, once. Any failure in the spy is logged and
leaves the execution as it was. The javac plugin itself never fails a compilation. Tables are written when javac
runs; a module that maven-compiler-plugin finds up to date keeps the tables it has. Direct goal calls such as
`mvn compiler:compile` (`default-cli`) start through the same event and are wired too; that path is not tested.

## Test

`src/it/test_extension.sh <extension.jar> <java-21-home> [<java-17-home>]` runs real offline Maven builds: the
antrun step failing a reactor on dependency resolution without the extension, the hook bound in all three
projects with it, off without the property, a declared plugin left alone, a missing plugin and Maven on Java 17
logged and skipped.

`src/it/test_inline_clustering.sh <extension.jar> <plugin.jar> <java-home> [mvn] [local-repository]` builds a
scratch reactor of four jar modules offline and checks the following:

- `mvn compile` writes each module's `target/code-clustering` tables with no goal. They are identical to the
  standalone `cluster` scan of `src/main/java` on the same JDK, and `JNI.tsv` holds the native method;
- a module with `-Xlint:all -Werror` stays green;
- Lombok still runs, from the class path in one module and from `annotationProcessorPaths` in another;
- `mvn test-compile` writes `target/code-clustering/test`;
- a changed source reaches the tables;
- `-Dclustering.inline=false`, and a POM property in one module, turn it off;
- an output path that is a file leaves the build green with a warning;
- a missing plugin jar is logged;
- the plugin is found in the local repository.

Set `KEEP_LOGS=<dir>` to keep the Maven logs.
