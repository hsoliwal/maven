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

The event file is staged outside the build tree and moved into place when the session ends, so a `clean` in the
same build never meets an open file. Each build stages in a new private directory `maven-build-events-<random>`
under `%LOCALAPPDATA%\Temp` on Windows and `java.io.tmpdir` elsewhere: `rwx------` on POSIX, and on Windows an ACL
that allows only the directory's owner, set as soon as the directory exists and read back (the directory no longer
inherits what its parent grants, such as `Authenticated Users` under `C:\tmp`; a directory that cannot be made
private stops the events for that build). No existing path is written to, so another user can neither stop nor forge
the stream. The published file is written new in its own directory and renamed into place, so it has that
directory's access, not the staging directory's. When a recorded build starts, the previous
build's event file and, in agent mode, its `failure.json`, `failure.md` and `summary.txt` are removed first: a build
that is killed before its session end leaves no older file looking current, and no `summary.txt` means the build did
not finish. Staging directories of killed builds that belong to the same user are removed after a day; nothing
else in the temporary directory is touched and no link is followed.

## Recommended command line

```
mvn -B -ntp -q "-Dstyle.color=never" "-Dmaven.agent=true" verify
```

The `-D` arguments are quoted because Windows PowerShell 5.1 splits an unquoted `-Dkey.with.dots=value` at the first
dot; the quotes work in bash, cmd.exe and PowerShell alike. The same arguments can also go in the `MAVEN_ARGS`
environment variable (Maven 3.9 and 4), which no shell splits: `$env:MAVEN_ARGS='-B -ntp -q -Dstyle.color=never
-Dmaven.agent=true'; mvn verify`. `-Dmaven.agent=true` alone does not turn off colours, transfer progress or
interactive mode: Maven configures its console before extensions load, so `-B -ntp -Dstyle.color=never` stay on the
command line (or in `MAVEN_ARGS` or `.mvn/maven.config`); the agent files and `[agent]` lines never carry colour codes.

The summary prints on standard output also under `-q`. With stock Maven, `-q` hides compiler warnings from every
source the extension can read; to record them add `-l build.log -Dmaven.events.log=build.log` (then the summary goes
into the log, like all output). The log is read in the charset Maven wrote it in: UTF-8 when it decodes strictly
(Maven 4 always, Maven 3.9 on Java 18 and newer), else the JVM default charset (Maven 3.9 on Java 17 writes the platform charset, such as
Cp1252 on Windows), else `native.encoding`. The extension is loaded from `lib/ext` (the fork's distribution), from
`.mvn/extensions.xml`, or with `MAVEN_OPTS=-Dmaven.ext.class.path=<smart-maven-extension.jar>` (Maven 3.9; Apache
Maven 4.0.0-rc-7 ignores it there and takes `"-Dmaven.ext.class.path=<jar>"` on the command line).

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
| `maven.agent.messageChars` | 300 | 40..10000 | characters per diagnostic or test message (longer ones end in `...`); a failure message keeps up to four times as many |
| `maven.agent.summaryBytes` | 4000 | 200..1000000 | UTF-8 bytes of the summary |

## build-events.jsonl

Every line starts with `{"v":1,"seq":<n>,"tMs":<ms since the build started>,"type":"<type>"`; the fields of the
type follow in the order below. Unknown values are `null`. At most 20000 events are written; later ones are counted
(`truncated` in `session-end`). `session-start` is always the first line, also for a build that failed before its
session started (a POM error; then `modules` is empty), and `session-end` is always the last.

| Type | Fields |
| --- | --- |
| `session-start` | `maven`, `java`, `os`, `root` (`"."`), `goals[]`, `profiles[]`, `threads`, `offline`, `userPropertyKeys[]` (keys only, never values), `modules[{index, id, path, packaging}]` |
| `project-start` | `index`, `project` (`groupId:artifactId`), `path` |
| `project-end` | `index`, `project`, `path`, `outcome` (`success`, `failure`, `skipped`), `durationMs` |
| `mojo-start` | `project`, `mojo{plugin (g:a:v), goal, execution, phase}` |
| `mojo-end` | `project`, `mojo{...}`, `outcome` (`success`, `failure`, `skipped`), `durationMs`, `error` (the failure message: its non-blank lines joined by LF, cleaned as below, up to 4 x `maven.agent.messageChars` characters) |
| `diagnostic` | `project`, `source` (`compiler`, `log`, `checkstyle`, `pmd`, `cpd`, `spotbugs`, `model`), `severity` (`ERROR`, `WARNING`, `INFO`), `file`, `line`, `column`, `rule` (rule id or javac `-Xlint` key), `message`, `mojo` (`prefix:goal`) |
| `test` | `project`, `class`, `method`, `outcome` (`passed`, `failed`, `error`, `skipped`), `durationMs`, `exception`, `message`, `stack[]` (trimmed), `location{file, line}` |
| `test-suite` | `project`, `class`, `tests`, `failures`, `errors`, `skipped`, `durationMs` |
| `repeat` | `project`, `severity`, `rule`, `key`, `count`, `example` |
| `session-end` | `outcome`, `durationMs`, `projects{total, success, failure, skipped}`, `diagnostics{error, warning, info, collapsed}`, `tests{run, failed, errors, skipped}`, `firstFailure{project, mojo}`, `digest`, `events`, `truncated` |

Sources of the facts:

- lifecycle events: Maven's `ExecutionEvent`s; repository events are dropped. Projects a fail-fast build never
  started end as `skipped` just before `session-end`.
- `diagnostic`: located lines of a failed mojo's message and, with `maven.events.log`, of the log: Maven compiler
  `file:[line,col] msg` (with its `symbol:` and `location:` lines), Checkstyle `[WARN] file:line:col: msg [Rule]`,
  javac `file:line: error: msg`, gcc/clang `file:line:col: warning: msg`, and javac's `file: warnings found and
  -Werror specified` (a diagnostic with the file and no line or column). Diagnostics are deduplicated on file, line,
  rule and message; after `maven.agent.maxRepeat` of one rule (or one message) per project and severity the rest are
  counted in one `repeat` event. A POM or model failure (before any project ran) gives one `model` diagnostic per
  located model problem: the POM file, line, column, severity and message. In a `-T` log, where the headers of
  modules interleave, a diagnostic whose file belongs to a module takes that module's last `--- ... @ <artifactId>
  ---` header as its mojo (and so its source `compiler` or `log`). U+0085, U+2028 and U+2029 end a line like LF, a
  line is read up to 8192 characters, a failure message up to 64 KB, and every line pattern runs in linear time.
- `test` and `test-suite`: the Surefire or Failsafe `TEST-*.xml` reports written during that mojo run, in the mojo's
  own `reportsDirectory`. Stacks keep the exception line, frames of the test's package root, `Caused by:` lines and
  the root cause; framework runs become `... n framework frames omitted`.
- Messages lose credentials and terminal colours: the userinfo of a URL (found from its `://` whatever precedes the
  scheme, also with `/` or `@` in the password, in every URL of a comma-separated list) becomes `***@`; the value of
  a `password=`, `passwd=`, `pwd=`, `passphrase=`, `token=`, `secret=`, `api_key=`, `access_key=`, `private_key=`,
  `login=`, `credentials=` or `signature=` pair (any case, as in a JDBC URL) becomes `***`, and so do an
  `Authorization:` header's value and a bearer token. The root's absolute path in every spelling (`/C:/r/`, `C:/r/`,
  `C:\r\`) is removed, the local repository (`maven.repo.local`, else `~/.m2/repository`) becomes `<local-repo>` and
  the home directory (`user.home`) `~`, so no file names the user. A long message is redacted within a bounded window
  first (a URL cut at the window's end before its `@` is masked after its `://`), and only then shrunk and clipped;
  every redaction pattern runs in linear time.

## failure.json

```
{"v":1, "outcome", "goals":[], "launcher":{"sh", "powershell", "cmd"},
 "failures":[{"project", "path", "kind", "mojo", "message", "helpReference",
  "diagnostics":[{"file", "line", "column", "severity", "rule", "message",
                  "context":{"startLine", "markLine", "lines":[]}}],
  "tests":[{"class", "method", "exception", "message", "stack":[]}], "reproduce", "omittedProperties"}],
 "skippedProjects":[], "advice":{"status", "source", "trusted":false, "text"},
 "limits":{"printed", "omittedDiagnostics", "omittedTests"}}
```

- `failures` are in reactor order. `kind` comes from the failed mojo: `compile`, `testCompile` (compiler plugin),
  `test` (Surefire), `integration-test` (Failsafe), `model` (a POM or model failure, whose located problems are the
  `diagnostics`), else `other`. `message` is the whole failure message, its non-blank lines joined by LF (a
  dependency, plugin or rule failure keeps its reason), cleaned as above and bounded to 4 x
  `maven.agent.messageChars`. `helpReference` is the `[Help]` link Maven's own exception handler gives; Maven 4's
  bare page name (`MojoFailureException`) is written as the link Maven 3.9 prints
  (`http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException`).
- `diagnostics` are the project's ERROR diagnostics, each with `maven.agent.context` source lines around it (tabs as
  four spaces, 240 characters per line). `tests` are the failed tests.
- `reproduce` reruns just that failure: `mvn -pl a -am compile` (or `test-compile` for a file under `src/test`),
  `mvn -pl b -am "-Dtest=fix.BTest#adds" "-Dsurefire.failIfNoSpecifiedTests=false" test`,
  `mvn -pl b -am "-Dit.test=..." "-Dfailsafe.failIfNoSpecifiedTests=false" "-Dtest=NoUnitTestSelected"
  "-Dsurefire.failIfNoSpecifiedTests=false" verify` (no unit test runs), `mvn validate` for a model failure, the
  failed goal itself when it ran from the command line (execution `default-cli`, for example `mvn -pl . -am
  org.apache.maven.plugins:maven-agent-plugin:0.1.0-SNAPSHOT:llms-txt`), the failed mojo's phase, or the build's own
  goals when no mojo failed (a mistyped phase: `mvn verfy`; in a failed project `mvn -pl . -am verfy`).
  - Before `-pl` every command repeats the build's command line: `-f <pom>` when the build ran in another directory
    or with another POM file name (the path is relative to the directory the build ran in, so run the command from
    there; otherwise run it from the build root); `"-P<id>,-<id>"` with the active profiles not defined in the
    settings (the settings activate their own) and the deactivated ones. On Maven 4 every id is written optional,
    `"-P?<id>,-?<id>"`: Maven 4 stops with a missing-profile error when no project of the `-pl`/`-am` selection
    defines a required id, which is the case for a failure in another module than the profile's; Maven 3.9 reads
    `?` as part of the id, so it gets the plain ids (and only warns about a missing profile). The running Maven's
    version (`maven.version`) decides.
  - Then `"-Dname=value"`, sorted, with only the user properties the build reads, an allowlist: those the failed
    mojo reads (`${name}` in its descriptor's parameter expressions and default values or in its execution
    configuration), those the reactor's POM files read (`${name}` anywhere in the files as written, before Maven
    interpolated a property into them) or that activate a profile of those POMs or of the settings, and the skip
    flags with a boolean value (`skipTests`, `skipITs`, `maven.test.skip`, `maven.test.skip.exec`,
    `maven.test.failure.ignore`, `<name>.skip`). Even then a property is left out when its name holds a secret word
    (`pass`, `pwd`, `secret`, `token`, `credential`, `auth`, `key`, `login`, `session`, `cookie`, `private`,
    `bearer`, `jwt`, `signature`, `hmac`, `webhook`) or a secret part between `.`, `_`, `-` or a camel hump (`pw`,
    `pin`, `otp`, `sig`, `salt`, `dsn`, `cred`, `creds`); when its value holds a credential URL, a `password=`-like
    pair, a known token prefix (`ghp_`, `github_pat_`, `glpat-`, `xoxb-`, `sk-`, `AKIA`, `eyJ`...), a webhook host
    (Slack, Discord, Office), a signed URL query (`sig=`, `X-Amz-Signature=`) or a random-looking run of 24 or more
    letters and digits; when its value holds an absolute path; and when a shell would change its value.
    `omittedProperties` counts the user properties left out, and `failure.md` and the summary add
    `(N user properties omitted)` after the command when there are any, so an agent knows the rerun lacks them. Not
    counted, and never repeated, are the agent's own settings (`maven.agent*`, `maven.events.*`, `style.color`,
    `maven.ext.class.path`, `chromellm.advice`), the test selection the command sets itself (`test`, `it.test` and
    their `failIfNoSpecifiedTests`), the properties Maven 4 sets itself (`session.topDirectory`,
    `session.rootDirectory`), and what Maven's configuration sets, which a rerun reads again:
    `maven-user.properties` or `maven.properties` in the installation's `conf/`, in `~/.m2` or in the project's
    `.mvn/` (Maven 4 copies their entries, such as `aether.conflictResolver.impl`, into the user properties), and the
    `-D` options of `.mvn/maven.config` and of the `MAVEN_ARGS` environment variable.
  - Every `-D` and `-P` argument is double-quoted; any other argument is bare only when it holds nothing but
    `A-Z a-z 0-9 _ . / : + = -` (and `@` or `#` after its first character), and is double-quoted otherwise, so a
    path or goal with white space, `< > * ? [ ] ~ { } , & | ; ( ) ^`, a leading `#` or a leading `@` stays one
    inert argument. Every argument holds only characters that bash, cmd.exe and PowerShell all keep unchanged inside
    double quotes (no quote, backtick, `$`, `%`, `!`, backslash, control character, or typographic dash or quote
    U+2013 to U+201E, which PowerShell reads as `-` and as quotes): a nested test class's `$` becomes `*`; a test
    method with another character (a display name, a quote, a backtick) is selected by its class; a test class with
    another character is left out; a module path with one is selected by its id; a `-f` path or goal with one is
    left out.
  - The launcher is `./mvnw` when the directory the build ran in has the Maven Wrapper, else `mvn`. `./mvnw` is how
    bash (and PowerShell) starts the wrapper but no command in cmd.exe, so `launcher` gives each shell's spelling:
    `{"sh":"./mvnw","powershell":".\\mvnw.cmd","cmd":".\\mvnw.cmd"}` (all three `mvn` without the wrapper; cmd.exe
    gets `.\mvnw.cmd`, not `mvnw.cmd`, which it does not find when `NoDefaultCurrentDirectoryInExePath` is set). In
    another shell replace the command's first word by that shell's spelling; `failure.md` says so too.
- `advice`: `not-requested` unless the build ran with `-Dchromellm.advice=true`; then the build hook's post-build
  phase runs once for the first failed project and gives `none` (no provider), `available` (with `text`, at most
  32 KB), `failed` (also for a report that is not valid JSON or larger than 4 MB), or `skipped` (Maven below Java 21,
  or the hook plugin not in the local repository). The report is read with a linear JSON string reader, so no text
  can exhaust the stack. The text is redacted like a message (credentials, root, home, local repository). Advice is
  never trusted.
- No durations, timestamps or absolute paths: a rerun of the same failure writes the same bytes, and so does
  `failure.md`, which renders the same data. In `failure.md` the failure message, a multi-line test message with the
  stack, and the advice text each sit in a fenced block longer than any run of backticks in them, and a diagnostic's
  message is kept on one line, so no line of a message can start a section or close a fence.

## Summary

Each line starts with `[agent] `: a header with outcome, duration and counts; per failure the errors, failed tests
and the reproduce command, then `(N user properties omitted)` when it leaves any out; with the Maven Wrapper the
launcher per shell (`launcher: ./mvnw in bash; .\mvnw.cmd in PowerShell and cmd.exe`); skipped projects; the warnings
of a green build; and the files written. Line breaks inside
a message (CR, LF, U+2028, U+2029) are written as the two characters `\n` and other control characters as
`\uXXXX`, so no message can start a line of its own.
