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
# Apache Maven Polyglot Tool Adapters

Opt-in `M3ToolProvider`s that let the polyglot engine (`tools/m3-polyglot-maven-plugin`) call the fork's own tools
and a few original ones as differs, patchers, analyzers and linters. Each provider plans an argv only; every tool runs
in its own process, so LSP4J, Error Prone or the clustering front ends never load into the engine's JVM.

| Tool value | Arguments | Runs | Reports | One-AST status |
| --- | --- | --- | --- | --- |
| `@m3:git:differ:text` | `trees <git> <before> <after> <out.patch>` (before and after must be declared inputs) or `repo <git> <repository> <revision> <out.patch> [<pathspec>...]` | `GitDiffMain` around the original git with every byte-changing setting pinned; exit 0 with the patch (empty when nothing differs), 2 and no patch when git failed | unified diff | `NO_AST` |
| `@m3:clustering:atomizer:<lang>` | `<clustering.jar> <out-dir> <source-root>...` | `CodeClustering cluster` | `<out-dir>/ATOM_HASHES.tsv` | Java `COMPILER_FRONT_END`, others `SEPARATE_PARSE` |
| `@m3:clustering:differ:<lang>` | `<clustering.jar> <baseline.tsv> <out-dir> <source-root>... [failOn=STATE,...]` | `CodeClustering compare` (structural differ); exit 3 = a failOn state was found, the task fails | `<out-dir>/COMPARE.tsv` | as above |
| `@m3:code-patch:patcher:text` | `plan\|preview\|verify\|apply <mvn> <version> <base-dir> <out-dir> <patch>... [failOn=..] [approvedPlan=<id>]`, `rollback <mvn> <version> <base-dir> <out-dir> <id>` | the code-patch goals through Maven without a POM, `--offline --batch-mode` | `PLAN.tsv`, apply/rollback `RECEIPT.tsv` | `NO_AST` |
| `@m3:javac-plugins:static-analyzer:java` | `<out.jsonl> <out.sarif> parse=second [pp=<jar>]... <javac-arg>...` with one `-d` in the declared outputs | `JavacPluginHost`: one `javax.tools` compilation; `-Xplugin:` analyzers (Error Prone, NullAway through Error Prone) and javac lint on that compilation's AST | build-events lines, SARIF 2.1.0 | `COMPILER_FRONT_END` (a second javac parse) |
| `@m3:lsp:linter:c\|cpp\|objc` | `<out.jsonl> <out.sarif> <deadline-ms> jar=<lsp4j jar>... server=<exe> [server-arg=<arg>]... [settle=<ms>] <file>...` | `LspDiagnosticsMain`: opens each file once at version 1, keeps the server's latest `publishDiagnostics` for it until the session is quiet (clangd) | build-events lines, SARIF 2.1.0 | `LANGUAGE_SERVER` |

## Exit codes and reports

Every analyzer and linter process writes both reports on every outcome. Exit 0: complete. Exit 1 (javac host): the
compilation failed with an ERROR finding. Exit 2: the invocation failed (a javac plug-in or processor not found or
crashed, an option javac refused, a diagnostic that could not be recorded; a language server that could not start,
exited, or did not answer `initialize`; a launcher refused). Exit 4 (linter): partial (files missing or not UTF-8,
files without an answer, or the server still busy at the deadline). Exits 2 and 4 set `executionSuccessful=false` with
a notification per cause; the task fails. The git differ exits 2 without leaving a patch.

## One-AST rule and the javac plug-in host

The host compiles the sources a second time next to the build's own compile, so it runs only on the explicit opt-in
`parse=second` and its catalogue status is `COMPILER_FRONT_END`. Analysis without a second parse puts `-Xplugin:...`
and the processor path on the build's own compile: `@m3:jdk:compiler:java` forwards them unchanged, as does
maven-compiler-plugin. The host gives way once the diagnostics capture runs inside that compile. Its `-d` (exactly
one) and any `-s`/`-h` folder must lie in declared output space and never in a declared input; `@argfiles` are
refused; run directly without `-d`, the host compiles into a temporary folder. Diagnostics on class files inside jars
are findings located by their `jar:` URI; an ERROR without a source (a plug-in not found) is an invocation failure.

## Language-server linter

Servers publish in stages (an early, often empty publish, then the result of a slower check: rust-analyzer flycheck,
JDT LS, ESLint, pyright). The collector therefore waits until every file was answered, no work-done progress is open
(`$/progress` begin to end, the server's own busy signal) and nothing arrived for `settle` ms (default 2000), all
within the deadline; a publish after the snapshot makes the run partial. A server that exits is noticed at once. The
graceful shutdown uses only the time the deadline leaves.

## Text differ

`GitDiffMain` pins line endings, path quoting, prefixes, context, algorithm, indent heuristic, blank context lines,
order file, renames, submodule format, full index lines, the big-file threshold and the user attributes file on the
command line (which wins over system, global and repository configuration), removes the environment variables git
reads for diff output or configuration (`GIT_DIFF_OPTS`, `GIT_EXTERNAL_DIFF`, `GIT_CONFIG_PARAMETERS`,
`GIT_CONFIG_COUNT`/`KEY_n`/`VALUE_n`, `GIT_INDEX_FILE`, ...), sets `GIT_ATTR_NOSYSTEM=1` and the C locale, and calls git
with `--no-ext-diff --no-textconv --no-replace-objects`. System and global configuration files are still read for what
does not change bytes (`safe.directory`). The repository's own `.gitattributes` stay in effect: they are versioned
content, the same on every machine; with `text`/`eol` conversion the patch is in repository form.

- trees mode: git exits 1 both for differences and for errors (after creating an empty output), so both trees are
  checked first (two folders or two files), and exit 1 counts as differences only with no `error:`/`fatal:` line and a
  patch that starts with `diff --git`. Report grade: the paths keep both folder names, which code-patch refuses.
- repository mode: untracked, not ignored files are included as new files: they are added intent-to-add to a copy of
  the index (`GIT_INDEX_FILE`), so the repository's own index is never written.

## Differs and patchers as one chain

code-patch is the only owner of source changes. The refused-by-default effect makes the chain explicit:

```
DIFFER git repo mode (repository-relative patch) --> PATCHER plan (PLAN.tsv, PLAN_ID)
  --> PATCHER verify (COMPARE.tsv, failOn gate, sources untouched)
  --> review PLAN_ID --> PATCHER apply approvedPlan=<id> (WRITES_SOURCES: needs approval) --> RECEIPT.tsv
  --> PATCHER rollback <id> (WRITES_SOURCES) when needed
DIFFER clustering compare (COMPARE.tsv against an ATOMIZER snapshot) gates the result structurally.
```

`apply` and `rollback` declare `WRITES_SOURCES`; the engine refuses them in `run` and plans them in `plan` until it
has a source-mutation approval flag. There is no `expectPlan` option: code-patch has no such check, so verify cannot
be tied to a reviewed plan id (and a declared `verify/<id>/COMPARE.tsv` could be satisfied by a stale file); the
reviewed plan id binds at apply, which code-patch checks.

## Install

Put this jar next to the engine jar: a plugin dependency of `m3-polyglot-maven-plugin` for Maven, or one more `-cp`
entry for the portable CLI and Gradle's JavaExec. Tool jars and executables are named in the task's `arg` rows;
nothing is downloaded. On Windows a `.cmd`/`.bat` file runs through cmd.exe, which expands or splits on
`% ! ^ & | < > "` and line breaks even inside one argv element; such a launcher is refused when it or any value that
reaches it holds one. The check covers the argv the engine starts and the commands the tool processes start
themselves: the `server=` and every `server-arg=` of the linter, and the git executable of the differ.

```sh
java -cp m3-polyglot-maven-plugin.jar:maven-polyglot-tool-adapters.jar io.synexia.maven.polyglot.PolyglotCli providers
```

## Reports

`FindingReports` writes SARIF 2.1.0 (results relative to the `SRCROOT` base or at an absolute URI, rules, invocation
success; columns in UTF-16 code units, recomputed from javac's character position because javac expands tabs) and
build-events v1 `diagnostic` lines. The lines use the vocabulary of the build-events owner
(`org.apache.maven.agent.events.Diagnostic` in the smart builder extension): the rule is a javac warning's `-Xlint`
key, a `[key]` prefix in lower case, else null, and a plug-in's `[CheckName]` prefix stays in the message; the message
is the first line plus javac's `symbol:`/`location:` lines, credentials and colours removed, the root made relative,
at most 300 characters (`EventVocabulary`, ported from the owner, which is not on master yet). Findings are
de-duplicated and sorted, so the same findings give the same bytes.

## Qualification

`scripts/verify.sh` compiles the engine and the adapters with `--release 17 -Werror` and runs
`ToolAdaptersContractTestMain` (argv and run contracts of every provider through the engine's registry),
`FindingReportsContractTestMain` (the report writer, one in-process javac lint compilation, a crashing javac plug-in),
`ToolAdaptersFailureContractTestMain`
(launcher metacharacters, jar-entry and missing-plug-in diagnostics, class output, UTF-16 columns, the build-events
vocabulary, the second-parse opt-in), `GitDifferContractTestMain` (real git: errors against differences, untracked
files, hostile user configuration and environment giving the same bytes) and, with LSP4J present,
`LspCollectorContractTestMain` (a fake language server: staged publishes, progress, silent, crashing, unreadable and
missing files, refused launchers). With a scratch root argument and a java `@argfile` in `M3_REAL_RUN_ARGFILE` it also
runs `ToolAdaptersRealRunTestMain`: every provider through `PolyglotCli run` on scratch projects, and every SARIF
report read back by m3-analyzer-report-adapter's `NormalizeCli`. A case whose tool is not configured prints `NOT RUN`
with the reason.

Gaps owned elsewhere: `LspDiagnosticsMain` moves into code-clustering-lsp once its session keeps diagnostics; a
`CodePatchCli` main in code-patch would remove the nested Maven hop; the SARIF writer moves into
m3-analyzer-report-adapter once it has one; `EventVocabulary` gives way to the build-events owner once it lands, and
that owner's source list needs `linter` for the language-server lines.
