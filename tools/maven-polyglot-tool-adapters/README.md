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
| `@m3:git:differ:text` | `trees <git> <before> <after> <out.patch>` or `repo <git> <repository> <revision> <out.patch> [<pathspec>...]` | `git diff` with pinned configuration (`core.autocrlf=false`, prefixes, algorithm, no renames, no external diff); trees mode exits 1 on differences, which is a declared success | unified diff | `NO_AST` |
| `@m3:clustering:atomizer:<lang>` | `<clustering.jar> <out-dir> <source-root>...` | `CodeClustering cluster` | `<out-dir>/ATOM_HASHES.tsv` | Java `COMPILER_FRONT_END`, others `SEPARATE_PARSE` |
| `@m3:clustering:differ:<lang>` | `<clustering.jar> <baseline.tsv> <out-dir> <source-root>... [failOn=STATE,...]` | `CodeClustering compare` (structural differ); exit 3 = a failOn state was found, the task fails | `<out-dir>/COMPARE.tsv` | as above |
| `@m3:code-patch:patcher:text` | `plan\|preview\|verify\|apply <mvn> <version> <base-dir> <out-dir> <patch>... [failOn=..] [expectPlan=<id>] [approvedPlan=<id>]`, `rollback <mvn> <version> <base-dir> <out-dir> <id>` | the code-patch goals through Maven without a POM, `--offline --batch-mode` | `PLAN.tsv`, verify `COMPARE.tsv` (with `expectPlan`), apply/rollback `RECEIPT.tsv` | `NO_AST` |
| `@m3:javac-plugins:static-analyzer:java` | `<out.jsonl> <out.sarif> [pp=<jar>]... <javac-arg>...` | `JavacPluginHost`: one `javax.tools` compilation; `-Xplugin:` analyzers (Error Prone, NullAway through Error Prone) and javac lint on the compiler's AST; the same compile writes the classes | build-events lines, SARIF 2.1.0 | `COMPILER_PLUGIN` |
| `@m3:lsp:linter:c\|cpp\|objc` | `<out.jsonl> <out.sarif> <deadline-ms> jar=<lsp4j jar>... server=<exe> [server-arg=<arg>]... <file>...` | `LspDiagnosticsMain`: opens each file once at version 1 and keeps the server's `publishDiagnostics` for it (clangd) | build-events lines, SARIF 2.1.0 | `LANGUAGE_SERVER` |

## Differs and patchers as one chain

code-patch is the only owner of source changes. The refused-by-default effect makes the chain explicit:

```
DIFFER git repo mode (repository-relative patch) --> PATCHER plan (PLAN.tsv, PLAN_ID)
  --> PATCHER verify expectPlan=<id> (COMPARE.tsv, failOn gate, sources untouched)
  --> review PLAN_ID --> PATCHER apply approvedPlan=<id> (WRITES_SOURCES: needs approval) --> RECEIPT.tsv
  --> PATCHER rollback <id> (WRITES_SOURCES) when needed
DIFFER clustering compare (COMPARE.tsv against an ATOMIZER snapshot) gates the result structurally.
```

`apply` and `rollback` declare `WRITES_SOURCES`; the engine refuses them in `run` and plans them in `plan` until it
has a source-mutation approval flag. `git diff --no-index` (trees mode) is report grade: its paths keep both folder
names, which code-patch refuses; use repository mode for patches that are applied.

## Install

Put this jar next to the engine jar: a plugin dependency of `m3-polyglot-maven-plugin` for Maven, or one more `-cp`
entry for the portable CLI and Gradle's JavaExec. Tool jars and executables are named in the task's `arg` rows;
nothing is downloaded. On Windows a `.cmd`/`.bat` launcher (for example `mvn.cmd`) is refused when an argument holds a
cmd.exe metacharacter (`% ! ^ & | < > "`).

```sh
java -cp m3-polyglot-maven-plugin.jar:maven-polyglot-tool-adapters.jar io.synexia.maven.polyglot.PolyglotCli providers
```

## Reports

`FindingReports` writes SARIF 2.1.0 (results relative to the `SRCROOT` base, rules, invocation success) and
build-events v1 `diagnostic` lines with the field set of the build-events extension. Findings are de-duplicated and
sorted, so the same findings give the same bytes. A javac compilation that fails without an ERROR diagnostic (a
crashing plug-in) and a language server that does not answer every file before the deadline are reported as
unsuccessful invocations (exit 2 and 4).

## Qualification

`scripts/verify.sh` compiles the engine and the adapters with `--release 17 -Werror` and runs
`ToolAdaptersContractTestMain` (argv and run contracts of every provider through the engine's registry, the report
writer, one in-process javac lint compilation, a crashing javac plug-in). With a scratch root argument and a java
`@argfile` in `M3_REAL_RUN_ARGFILE` it also runs `ToolAdaptersRealRunTestMain`: every provider through
`PolyglotCli run` on scratch projects, and every SARIF report read back by m3-analyzer-report-adapter's
`NormalizeCli`. A case whose tool is not configured prints `NOT RUN` with the reason.

Gaps owned elsewhere: `LspDiagnosticsMain` moves into code-clustering-lsp once its session keeps diagnostics; a
`CodePatchCli` main in code-patch would remove the nested Maven hop; the SARIF writer moves into
m3-analyzer-report-adapter once it has one.
