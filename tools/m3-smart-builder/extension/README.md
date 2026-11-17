<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Smart Maven core extension: build advice and any-language builds in every project, no POM change

`io.synexia:smart-maven-extension` is a Maven core extension with build events for coding agents (below, "Build
events for agents") and two lifecycle participants:

- `PolyglotLifecycleParticipant`: with `-Dm3.polyglot.lifecycle=true`, every project that has an `m3-build.tsv`
  runs its targets named `validate`, `compile`, `test`, `package` or `verify` in that Maven phase through
  `io.synexia:m3-polyglot-maven-plugin` (execution `polyglot-<phase>`), so one `mvn verify` builds Java and
  non-Java modules alike with their own tools; see the polyglot plugin's README for `m3.polyglot.incremental`.
  `src/it/test_polyglot.sh` proves it with a reactor of a Java module and a Python-built module.
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

## Test

`src/it/test_extension.sh <extension.jar> <java-21-home> [<java-17-home>]` runs real offline Maven builds: the
antrun step failing a reactor on dependency resolution without the extension, the hook bound in all three
projects with it, off without the property, a declared plugin left alone, a missing plugin and Maven on Java 17
logged and skipped.

## Build events for agents

With `-Dmaven.agent=true` the event spy `org.apache.maven.agent.events.BuildEventSpy` records the build for a coding
agent; without it (or `-Dmaven.events.file=...`) it records nothing.

```sh
mvn -B -ntp -q -Dstyle.color=never -Dmaven.agent=true verify
```

prints a short summary on standard output (also under `-q`; every line starts with `[agent]`) and writes, under
`target/agent/`:

- `build-events.jsonl`: session, project, mojo, located diagnostic, test, test-suite, repeat and session-end events;
- `failure.json` and `failure.md` after a failed build: per failed module its errors with the source lines around
  them, its failed tests with trimmed stacks, and the command that reruns just that failure; skipped modules; the
  build hook's advice when `-Dchromellm.advice=true` asked for it (never trusted);
- `summary.txt`: the printed summary.

Stock Maven under `-q` shows compiler warnings nowhere the extension can read; `-l build.log
-Dmaven.events.log=build.log` adds them from the log. The formats, properties and bounds are in
[docs/agent-build-events.md](docs/agent-build-events.md). Besides `lib/ext` and `MAVEN_OPTS`, the extension also loads
from a project's `.mvn/extensions.xml`. `maven-agent-plugin` (`tools/maven-agent-plugin`) describes a build as
`project.json` and `llms.txt` with the same command spellings (`MavenCommands`).

Tests: `mvn verify` runs the offline unit tests (`*TestMain`: JSON escaping, truncation and field order, event
order under parallel writers, the event cap, diagnostics in compiler, Checkstyle, javac and gcc output, Surefire
reports, stack trimming, the digest byte for byte). `src/it/test_events.sh <extension.jar> <java-home>...` runs real
offline builds of a three-module reactor (a compile error, a failing test, a warning): nothing without the property,
the summary under `-q`, events in order with file, line and column, the digest identical on a rerun, the warning from
the log, skipped modules of a fail-fast build, and a green `clean verify` over the agent files.
