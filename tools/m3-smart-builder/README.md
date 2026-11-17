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
# M3 Smart Maven Builder — opt-in Java 21 Maven plugin

Status: experimental. This project lives **outside the upstream Maven reactor** and does not change Maven's default lifecycle, POM APIs, dependency resolution or repository credentials.

## Contract

- `m3:plan` reads the project's `pom.xml` and an optional immutable TSV artifact manifest. It extracts bounded, direct-only POM facts (coordinates, modules, declared dependency identifiers) with external XML entities and DTDs disabled. It does not resolve parent POMs, profiles, transitive dependencies or dependency graphs. It writes one review-only JSON plan under the project's existing Maven build directory (`target/m3-smart/plan.json` unless overridden). Its default mode performs no inference or runtime acquisition.
- Optional `-Dm3.smart.llmBackend=cli|jni|chrome` enables **ChromeLLM's existing** MCP stdio server, selected using an explicit module JAR and provider settings. The Chrome provider owns a dedicated browser/profile and permits model downloads only with `m3.smart.chromeDownload=true`. The model returns *candidate commentary only*; it cannot set executable commands, artifact URLs, profiles or success statuses. No fallback to another model. A file from the existing Chrome browser agent can instead supply a candidate via `-Dm3.smart.candidateFile=/approved/candidate.txt`. The two inputs are exclusive.
- `m3:provision` requires `-Dm3.smart.approveProvision=true`, an existing, explicit absolute cache directory, a manifest, a nonempty named profile and online permission. It processes only manifest rows selected by that profile. HTTPS transfers follow no redirects, are bounded, are SHA-256 verified, and are exclusively published into the approved cache. Nothing is executed or unpacked. A deterministic `target/m3-smart/provision.json` receipt is written only after all selected artifacts are verified. Normal Maven dependencies remain Maven Resolver's responsibility.
- `m3:diagnose` requires an explicit build log and an existing project-local source directory. It produces a deterministic `target/m3-smart/diagnose.json` containing bounded Maven failure-category counts, log/content hashes and Java/JNI source-inventory aggregates. No raw log lines, source text, credentials or log-provided paths enter the report or the optional ChromeLLM advisory prompt. `-Dm3.smart.candidateFile` and `-Dm3.smart.llmBackend=cli|jni|chrome` remain exclusive. See [diagnostics contract](docs/DIAGNOSE_CONTRACT.md).
- All JSON reports use the shared `SafeBuildOutput` guard, rejecting symlinks along the build-output path and at report destinations. The checkout/output directory must be exclusively owned; concurrent malicious path replacement is not a supported security boundary.
- `m3:provision` with Maven's `-o` checks and rehashes existing entries only. A corrupt entry fails rather than being overwritten; an absent entry fails rather than initiating networking. Cache entries are content-addressed: SHA-256 plus a fixed suffix, not user-provided filenames.
- No root or home writes are performed implicitly. The output/build directory must resolve within the explicitly invoked project checkout, under Maven's normal build directory. A cache directory must already exist and be operator approved. Plan/receipt filenames are fixed; no AI-controlled path, command or URL is accepted.
- GitHub native assets or model weights must be independently licensed and immutable SHA-256 pinned. A repository tag alone is not a digest. The manifest provisioner does not extract archives or execute downloads. The explicit Chrome provider delegates its own model provisioning to Chrome; it never extracts browser-managed model bytes.
- LLM advisory text is untrusted. No automated patch, Maven module selection, build invocation or API change is derived from it.
- `m3:code-agent` extends the advisory pipeline with **source-bound recipe nomination**: it requires both canonical Java atom/pattern receipts and the OpenRewrite preparation report, a strictly sorted operator-approved SHA-pinned recipe catalogue, and optionally a ChromeLLM candidate. It only proposes an applicable, catalogued recipe; source mutation, recipe approval and verification remain separate. See [code-agent contract](docs/CODE_AGENT.md).
- `m3:code-agent` optionally consumes a current-file-bound **structural/logic/SimHash atom ledger** and guides ChromeLLM with a versioned, digest-bound, 18-rule **M3 Machine-Oriented Programming law packet** packaged in the plugin. The hashes provide similarity signals, not proof; authority labels distinguish local gates, external producer statements, advisory evidence, and proof still outstanding. `m3:code-agent-verify` is a **separate**, explicitly approved, offline/pinned OpenRewrite `dryRun` → staged `run` → Maven `verify` → fixed-point replay workflow, restricted to FILE scope. It keeps original sources unchanged and does not authenticate semantic equivalence or promote recipes. See [code-agent contract](docs/CODE_AGENT.md).
- The canon gate (`sh m3-build.sh canon <packet.tsv> <run-dir>` or `mvn m3:canon`) is the hard M3-SCALE run contract: task packet, fixed stage order diff, atom_pattern, lint, compile, test, runtime, scope locks, mandatory atomization and patternization of every changed file, idempotence and the output contract; any violation fails. See [the canon gate contract](docs/CANON_GATE.md).
- Build hooks can require canonical Java atomization/patternization checkpoint manifests before inference using `m3.smart.requireAtomPattern=true` and `m3.smart.atomPatternManifest`. The gate validates current Java scope coverage and ordered digest binding; non-Java files remain assigned to their existing lanes. It does not authenticate host semantic/JIT proof execution. See [the receipt contract](docs/CHROME_BUILD_HOOKS.md#require-canonical-java-atomization-and-patternization-receipts).

## Manifest v1

The strict UTF-8, tab-separated `m3-artifacts.tsv` schema is:

`id<TAB>profile<TAB>kind<TAB>url<TAB>sha256<TAB>maxBytes<TAB>licenseApproved`

Header required, no duplicate keys, no extra columns. `kind` is one of `model-gguf`, `native-bin`, `test-data`; `url` is a directly operator-approved HTTPS URL (no credentials, fragment, literal IP or redirect); `sha256` is lowercase 64-hex digest; `maxBytes` is 1..68719476736; `licenseApproved` must be `true`. No artifact URL is inferred from a GitHub repository name. All rows are validated even if not selected.

Example (replace the placeholders with independently verified values; **not runnable as-is**):

```text
id	profile	kind	url	sha256	maxBytes	licenseApproved
webgpu-runtime	webgpu-smoke	native-bin	https://example.org/approved-webgpu-package	REPLACE_WITH_64_HEX	10485760	true
```

## Commands (after building and installing this plugin)

```sh
mvn -f tools/m3-smart-builder/pom.xml clean verify
mvn -f tools/m3-smart-builder/pom.xml install
mvn io.synexia:m3-smart-maven-plugin:0.1.0-SNAPSHOT:plan \
    -Dm3.smart.profile=webgpu-smoke -Dm3.smart.manifest=/approved/m3-artifacts.tsv
mvn -o io.synexia:m3-smart-maven-plugin:0.1.0-SNAPSHOT:provision \
    -Dm3.smart.profile=webgpu-smoke -Dm3.smart.manifest=/approved/m3-artifacts.tsv \
    -Dm3.smart.cacheDir=/approved/existing-cache -Dm3.smart.approveProvision=true
```

For log and Java/JNI source diagnostics (read-only):

```sh
mvn io.synexia:m3-smart-maven-plugin:0.1.0-SNAPSHOT:diagnose \
    -Dm3.smart.logFile=/approved/build.log \
    -Dm3.smart.sourceRoot=/approved/project/src
```

For ChromeLLM planning (local, browser-independent):

```sh
mvn io.synexia:m3-smart-maven-plugin:0.1.0-SNAPSHOT:plan \
    -Dm3.smart.llmBackend=cli \
    -Dm3.smart.chromeLlmJar=/approved/chromellm-standalone.jar \
    -Dm3.smart.llamaBin=/approved/llama-cli -Dm3.smart.modelPath=/approved/model.gguf
```

For the browser-managed Chrome AI model, select the explicit `chrome` provider as
described below. The existing candidate-file route remains available unchanged.

## Verification

`scripts/verify.sh` compiles the dependency-free Java core and runs its offline tests with JDK 21. It also executes the Mojo adapter bodies against minimal Maven API stubs. These adapter tests do not qualify Maven plugin descriptor generation or real Maven API injection. The Maven verification goal explicitly binds its test fixture root to `${project.build.directory}`, independent of the caller's working directory. The code-agent verifier uses a synthetic Maven executable in offline tests; a genuine OpenRewrite run is a distinct qualification. A real Maven plugin build, a real ChromeLLM MCP session, a browser extension test, network artifact fetching and native GPU execution require separate evidence. Never treat the offline fixtures as those qualifications.

## Scope boundaries and repository ownership

`hsoliwal/maven` owns this opt-in plugin and plugin goal semantics. `hsoliwal/ChromeLLM` owns MCP stdio, lazy GGUF and JNI providers. `hsoliwal/dawn` and `hsoliwal/synexia-llmcpp` own native WebGPU components. `hsoliwal/com.synexia` owns reusable recipes, pattern evaluation and proof policy. Do not merge these contracts into a single mutable Maven Core file.

## Chrome AI build hooks

The existing provider now supports explicit Chrome built-in AI for `m3:plan`,
`m3:diagnose` and the additive `m3:build-hook` goal. The reusable Action and Java
command invoke it before and after a build, including after failed compilation.
See [Chrome build hooks](docs/CHROME_BUILD_HOOKS.md) for setup, lazy download,
failure preservation and the distinct synthetic/live qualification lanes.
`m3:build-hook` also takes the `bridge` provider (a loopback chat-completions
endpoint in front of an already provisioned Chrome model), an operator-chosen CPU
summarizer whose lines join the facts, and `failOnError=false` for binding the hook
into a POM's lifecycle without letting advice change the build result.

## Chrome AI coding agent — pinned OpenRewrite recipes

`m3:code-agent` reuses the already installed ChromeLLM provider and existing
Java preparation/atom checkpoint verifiers. Without a provider it emits a
deterministic list of applicable catalogued recipes. With a provider it accepts
only an exact JSON nomination bound to the current evidence and recipe digest,
never model-generated code or executable commands. The output is review-only.

```sh
mvn io.synexia:m3-smart-maven-plugin:0.1.0-SNAPSHOT:code-agent \
  -Dm3.smart.sourceRoot=src/main/java \
  -Dm3.smart.preparationReport=.reports/preparation.json \
  -Dm3.smart.atomPatternManifest=.reports/atom-pattern.tsv \
  -Dm3.smart.recipeCatalog=.reports/approved-recipes.tsv \
  -Dm3.smart.llmBackend=chrome \
  -Dm3.smart.chromeLlmJar=/approved/chromellm-standalone.jar \
  -Dm3.smart.chromeBin=/approved/chrome \
  -Dm3.smart.chromeProfile=/approved/dedicated-profile
```

See [the complete input, replay and promotion contract](docs/CODE_AGENT.md).
