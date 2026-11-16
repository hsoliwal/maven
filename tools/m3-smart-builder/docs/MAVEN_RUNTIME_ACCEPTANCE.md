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

# M3 Smart Maven — real Maven runtime acceptance (v1)

This document specifies an **additional** qualification gate for the already opt-in
`io.synexia:m3-smart-maven-plugin:0.1.0-SNAPSHOT`. It does not modify existing
Mojo parameters, JSON schemas, Maven Core, Maven Resolver, or the normal Maven
lifecycle. All effects remain bounded to an operator-approved Maven local
repository and the plugin project's normal `target/` output directory.

## Entrypoints and gate order

1. Inventory an existing Maven executable, JDK 21, the plugin POM and approved
   temporary local repository. An absent Maven executable is **BLOCKED**, not PASS.
2. Run the existing `sh scripts/verify.sh` gate (JDK lint, compile, unit and
   synthetic MCP runtime tests). Its fixtures are not actual Maven integration.
3. Run `mvn -B -ntp -Dmaven.repo.local=<approved-repository> clean install`
   from `tools/m3-smart-builder/`. The local repository must be a pre-existing,
   operator-approved writable directory outside the plugin's `target/` tree so
   `clean` cannot destroy Maven's own dependencies. No deploy/publish goal.
4. Run `python3 scripts/verify_maven_runtime.py --maven mvn
   --local-repo <approved-repository>` from the plugin directory. The script
   refuses to run if the deterministic fixture directory already exists, if
   the Maven executable is missing, or if a generated Maven plugin descriptor
   declaring all three goals cannot be found in the installed local plugin JAR.
5. The script invokes Maven **with `-o`** for each synthetic project goal;
   it never asks Maven to download a fixture or a model. First run and replay
   of `plan` must produce identical byte content. Offline `provision` must
   reuse a pre-seeded SHA-256-verified, license-approved cache. `diagnose` must
   classify a compiler error without emitting the synthetic log secret.
6. The script must observe failure of unapproved provisioning, corrupted
   cached content and missing offline content without altering earlier
   validated receipts or cached bytes. Any unexpected success is a hard fail.
7. Persist the actual Maven command receipts, exit codes and sanitized
   deterministic summary under `target/m3-runtime-acceptance/`. These are CI
   artifacts only when the job actually runs; a queued or absent GitHub run
   is **PENDING**, never PASS.

## Controlled fixture

The fixture POM declares a single normal Maven `pom` project. A fixed UTF-8
candidate string represents an untrusted LLM recommendation. The artifact
manifest has a fixed HTTPS example-domain URL, license approval and SHA-256,
while the selected artifact itself is pre-seeded locally. No network request
to the placeholder URL is permitted. The source directory contains one Java
file; the Maven log is a synthetic compiler failure plus a synthetic secret.
No Chrome-managed weights, Dawn library, GPU driver, Maven Core implementation
or public API is changed or exercised by this gate.

All subprocess arguments are fixed argv lists; no shell interpretation, script
execution from LLM text, repository credentials or implicit user-home cache.
The acceptance script only writes into the requested checkout's existing Maven
`target` directory; it cannot clean, remove or overwrite existing fixture
paths. A previous fixture run requires a fresh explicitly provisioned workspace.

## Platform and release claims

This version is scoped to a Linux GitHub Actions runner with Maven 3.9.x and
JDK 21. A passing integration run proves that the three plugin goals package,
load, execute and produce the expected on-disk Maven receipts on that runner.
It **does not** prove Maven 4 API compatibility, live Chrome Prompt API / native
messaging, real ChromeLLM inference, GitHub binary network retrieval, native
Dawn/JNI linkage, GPU inference or Windows/macOS registration. Those remain
separate acceptance gates with their own receipts.

A local `--prepare-only` check validates deterministic fixtures without Maven,
but **never qualifies** the real Maven runtime; the CI job must execute the
complete plugin build and subsequent goal invocations.
