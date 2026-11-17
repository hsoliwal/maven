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
# Build events for agents: file formats (version 1)

The core extension's event spy (`org.apache.maven.agent.events.BuildEventSpy`) writes these files when a build runs
with `-Dmaven.agent=true`, or only the event file with `-Dmaven.events.file=<file>`. Without either property it does
nothing. Every path in every file is relative to the build root (the top-level POM's directory) and uses `/`. Files
are UTF-8 with LF line ends.

| File (default under `target/agent/`) | Written | Content |
| --- | --- | --- |
| `build-events.jsonl` | every recorded build | one JSON object per line, in `seq` order |
| `failure.json`, `failure.md` | agent mode, failed build (removed after a green one) | the failure digest |
| `summary.txt` | agent mode | the summary also printed on standard output |
| `project.json` | `mvn agent:describe-project` (maven-agent-plugin) | modules, goals, commands |

The event file is staged in the temporary directory and moved into place when the session ends, so a `clean` in the
same build never meets an open file.

## Recommended command line

```
mvn -B -ntp -q -Dstyle.color=never -Dmaven.agent=true verify
```

The summary prints on standard output also under `-q`. With stock Maven, `-q` hides compiler warnings from every
source the extension can read; to record them add `-l build.log -Dmaven.events.log=build.log` (then the summary goes
into the log, like all output). The extension is loaded from `lib/ext` (the fork's distribution), from
`.mvn/extensions.xml`, or with `MAVEN_OPTS=-Dmaven.ext.class.path=<smart-maven-extension.jar>`.

## Properties

| Property | Default | Bounds | Meaning |
| --- | --- | --- | --- |
| `maven.agent` | `false` | | events, digest, summary |
| `maven.events.file` | `<maven.agent.dir>/build-events.jsonl` | | event file; setting it alone records events only |
| `maven.events.stdout` | `summary` with `maven.agent`, else `none` | `none`, `summary`, `events` | standard output |
| `maven.events.log` | none | | the `-l` log of this build, read at the end for located warnings |
| `maven.agent.dir` | `target/agent` | | relative to the root |
| `maven.agent.tests` | `failed` | `failed`, `all` | which tests become `test` events |
| `maven.agent.printLimit` | 20 | 0..1000 | diagnostics and tests per failure in digest and summary |
| `maven.agent.maxRepeat` | 3 | 1..1000 | diagnostics of one rule per project and severity before `repeat` |
| `maven.agent.stackLines` | 12 | 1..200 | trimmed stack lines per test |
| `maven.agent.context` | 2 | 0..10 | source lines before and after a diagnostic |
| `maven.agent.messageChars` | 300 | 40..10000 | characters per message (longer ones end in `...`) |
| `maven.agent.summaryBytes` | 4000 | 200..1000000 | UTF-8 bytes of the summary |

## build-events.jsonl

Every line starts with `{"v":1,"seq":<n>,"tMs":<ms since the build started>,"type":"<type>"`; the fields of the
type follow in the order below. Unknown values are `null`. At most 20000 events are written; later ones are counted
(`truncated` in `session-end`), and `session-end` is always the last line.

| Type | Fields |
| --- | --- |
| `session-start` | `maven`, `java`, `os`, `root` (`"."`), `goals[]`, `profiles[]`, `threads`, `offline`, `userPropertyKeys[]` (keys only, never values), `modules[{index, id, path, packaging}]` |
| `project-start` | `index`, `project` (`groupId:artifactId`), `path` |
| `project-end` | `index`, `project`, `path`, `outcome` (`success`, `failure`, `skipped`), `durationMs` |
| `mojo-start` | `project`, `mojo{plugin (g:a:v), goal, execution, phase}` |
| `mojo-end` | `project`, `mojo{...}`, `outcome` (`success`, `failure`, `skipped`), `durationMs`, `error` (first message line) |
| `diagnostic` | `project`, `source` (`compiler`, `log`, `checkstyle`, `pmd`, `cpd`, `spotbugs`), `severity` (`ERROR`, `WARNING`, `INFO`), `file`, `line`, `column`, `rule` (rule id or javac `-Xlint` key), `message`, `mojo` (`prefix:goal`) |
| `test` | `project`, `class`, `method`, `outcome` (`passed`, `failed`, `error`, `skipped`), `durationMs`, `exception`, `message`, `stack[]` (trimmed), `location{file, line}` |
| `test-suite` | `project`, `class`, `tests`, `failures`, `errors`, `skipped`, `durationMs` |
| `repeat` | `project`, `severity`, `rule`, `key`, `count`, `example` |
| `session-end` | `outcome`, `durationMs`, `projects{total, success, failure, skipped}`, `diagnostics{error, warning, info, collapsed}`, `tests{run, failed, errors, skipped}`, `firstFailure{project, mojo}`, `digest`, `events`, `truncated` |

Sources of the facts:

- lifecycle events: Maven's `ExecutionEvent`s; repository events are dropped. Projects a fail-fast build never
  started end as `skipped` just before `session-end`.
- `diagnostic`: located lines of a failed mojo's message and, with `maven.events.log`, of the log: Maven compiler
  `file:[line,col] msg` (with its `symbol:` and `location:` lines), Checkstyle `[WARN] file:line:col: msg [Rule]`,
  javac `file:line: error: msg`, gcc/clang `file:line:col: warning: msg`. Diagnostics are deduplicated on file, line,
  rule and message; after `maven.agent.maxRepeat` of one rule (or one message) per project and severity the rest are
  counted in one `repeat` event.
- `test` and `test-suite`: the Surefire or Failsafe `TEST-*.xml` reports written during that mojo run, in the mojo's
  own `reportsDirectory`. Stacks keep the exception line, frames of the test's package root, `Caused by:` lines and
  the root cause; framework runs become `... n framework frames omitted`.
- Messages lose `user:password@` of URLs and terminal colours, and the root's absolute path in every spelling.

## failure.json

```
{"v":1, "outcome", "goals":[], "failures":[{"project", "path", "kind", "mojo", "message", "helpReference",
  "diagnostics":[{"file", "line", "column", "severity", "rule", "message",
                  "context":{"startLine", "markLine", "lines":[]}}],
  "tests":[{"class", "method", "exception", "message", "stack":[]}], "reproduce"}],
 "skippedProjects":[], "advice":{"status", "source", "trusted":false, "text"},
 "limits":{"printed", "omittedDiagnostics", "omittedTests"}}
```

- `failures` are in reactor order. `kind` comes from the failed mojo: `compile`, `testCompile` (compiler plugin),
  `test` (Surefire), `integration-test` (Failsafe), else `other`. `helpReference` is the `[Help]` link Maven's own
  exception handler gives.
- `diagnostics` are the project's ERROR diagnostics, each with `maven.agent.context` source lines around it (tabs as
  four spaces, 240 characters per line). `tests` are the failed tests.
- `reproduce` reruns just that failure: `mvn -pl a -am compile` (or `test-compile` for a file under `src/test`),
  `mvn -pl b -am -Dtest=fix.BTest#adds -Dsurefire.failIfNoSpecifiedTests=false test`,
  `mvn -pl b -am -Dit.test=... -Dfailsafe.failIfNoSpecifiedTests=false verify`, or the failed mojo's phase.
  `./mvnw` replaces `mvn` when the root has the wrapper.
- `advice`: `not-requested` unless the build ran with `-Dchromellm.advice=true`; then the build hook's post-build
  phase runs once for the first failed project and gives `none` (no provider), `available` (with `text`), `failed`,
  or `skipped` (Maven below Java 21, or the hook plugin not in the local repository). Advice is never trusted.
- No durations, timestamps or absolute paths: a rerun of the same failure writes the same bytes, and so does
  `failure.md`, which renders the same data.

## Summary

Each line starts with `[agent] `: a header with outcome, duration and counts; per failure the errors, failed tests
and the reproduce command; skipped projects; the warnings of a green build; and the files written.
