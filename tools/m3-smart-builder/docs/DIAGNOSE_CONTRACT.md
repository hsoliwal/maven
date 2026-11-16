<!--
SPDX-License-Identifier: Apache-2.0

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

<!-- SPDX-License-Identifier: Apache-2.0 -->
# M3 Smart Builder diagnostics and safe output contract (v1)

Status: experimental, opt-in. This specification precedes its implementation.
Existing `m3:plan` and `m3:provision` outputs, parameters, default Maven lifecycle,
public Maven APIs, dependency resolver and selected providers remain unchanged.

## New goal: `m3:diagnose`

`m3:diagnose` is read-only with respect to the target project, except for
`target/m3-smart/diagnose.json`. It requires an operator-specified existing
Maven build log and an existing source directory beneath the project base.
It never runs a Maven build, invokes the compiler, patches source, downloads
artifacts or invokes a command contained in a log or LLM response.

The deterministic facts engine reads at most 32 MiB of the log and at most
2,000 source files across at most 8,192 directories (with at most 1 MiB inspected per file). Source extensions:
`.java`, `.c`, `.cc`, `.cpp`, `.h`, `.hpp`. A source tree with symbolic links
is rejected instead of followed. Every qualifying file contributes its
repository-relative path, file-content SHA-256, and counts of a small fixed
set of source/API/JNI markers to an ordered source digest. Raw source contents
and build-log lines are never serialized into the diagnosis JSON or sent to an
LLM. Source scans and log classification are deterministic, not AST parsing or
compilation proofs. Source counts are not a substitute for semantic analysis.

The log digest covers raw bytes; source digest covers ordered manifest records.
The facts identify stable error categories (compiler, dependency-resolution,
plugin, test, native, other) and count matched lines. A line belongs to exactly
one category, using a documented priority. The report includes only fixed
category labels and pattern names, not excerpts, paths from log output, tokens,
credentials, command lines, or environment values.

Optional LLM candidate input (`m3.smart.candidateFile`) is review-only.
Optional ChromeLLM MCP stdio inference (`m3.smart.llmBackend=cli|jni`) receives
only structured category/count/source totals. These modes are exclusive. The
result is recorded as untrusted candidate text, never parsed as a command or
used to choose executable build steps, binary URLs, source patches or tests.

## Shared safe output writer

All three M3 goals use a single writer for their fixed JSON receipt filenames.
The output directory must be lexically below the actual project base and all
existing path components from the project root through the output directory
must be ordinary directories, never symbolic links. The destination file must
not itself be a link. Receipt writes use a sibling temporary file and atomic
replace, preserving current deterministic fixed paths and bytes. No arbitrary
output paths supplied by model text are accepted. The guard does not claim to
contain a malicious concurrent local actor that can swap directories after
validation; an exclusively owned checkout/output directory is required.

## Acceptance evidence

Before PR review: document input/failure invariants; patch sources; run script
syntax, Java lint, compilation, deterministic fixture tests (including symlink
rejection), runtime test; record pre/post file hashes and the exact commands.
Full Maven plugin packaging and actual Chrome/GPU execution require a separate
real integration host and must not be marked as passed without logs.
