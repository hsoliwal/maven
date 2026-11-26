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
- Optional `-Dm3.smart.llmBackend=cli|jni` enables **ChromeLLM's existing** MCP stdio server, selected using an explicit module JAR and provider settings. The model returns *candidate commentary only*; it cannot set executable commands, artifact URLs, profiles or success statuses. No fallback to another model. An explicitly approved candidate file can instead supply commentary via `-Dm3.smart.candidateFile=/approved/candidate.txt`. The two inputs are exclusive.
- `m3:provision` requires `-Dm3.smart.approveProvision=true`, an existing, explicit absolute cache directory, a manifest, a nonempty named profile and online permission. It processes only manifest rows selected by that profile. HTTPS transfers follow no redirects, are bounded, are SHA-256 verified, and are exclusively published into the approved cache. Nothing is executed or unpacked. A deterministic `target/m3-smart/provision.json` receipt is written only after all selected artifacts are verified. Normal Maven dependencies remain Maven Resolver's responsibility.
- `m3:diagnose` requires an explicit build log and an existing project-local source directory. It produces a deterministic `target/m3-smart/diagnose.json` containing bounded Maven failure-category counts, log/content hashes and Java/JNI source-inventory aggregates. No raw log lines, source text, credentials or log-provided paths enter the report or the optional ChromeLLM advisory prompt. `-Dm3.smart.candidateFile` and `-Dm3.smart.llmBackend=cli|jni` remain exclusive. See [diagnostics contract](docs/DIAGNOSE_CONTRACT.md).
- All JSON reports use the shared `SafeBuildOutput` guard, rejecting symlinks along the build-output path and at report destinations. The checkout/output directory must be exclusively owned; concurrent malicious path replacement is not a supported security boundary.
- `m3:provision` with Maven's `-o` checks and rehashes existing entries only. A corrupt entry fails rather than being overwritten; an absent entry fails rather than initiating networking. Cache entries are content-addressed: SHA-256 plus a fixed suffix, not user-provided filenames.
- No root or home writes are performed implicitly. The output/build directory must resolve within the explicitly invoked project checkout, under Maven's normal build directory. A cache directory must already exist and be operator approved. Plan/receipt filenames are fixed; no AI-controlled path, command or URL is accepted.
- GitHub native assets or model weights must be independently licensed and immutable SHA-256 pinned. A repository tag alone is not a digest. The manifest provisioner does not extract archives or execute downloads.
- LLM advisory text is untrusted. No automated patch, Maven module selection, build invocation or API change is derived from it.
- `m3:code-agent` extends the advisory pipeline with **source-bound recipe nomination**: it requires both canonical Java atom/pattern receipts and the OpenRewrite preparation report, a strictly sorted operator-approved SHA-pinned recipe catalogue, and optionally a ChromeLLM candidate. It only proposes an applicable, catalogued recipe; source mutation, recipe approval and verification remain separate. See [code-agent contract](docs/CODE_AGENT.md).
- The semantic operator catalogue now also accepts an optional `contract_sha256` pin to reject conflicting syntactic signatures. Per-atom `MachineContractEnvelope` reports source-bound signature/type/call/effect/may-flow cues, explicit typed mismatch residue and outstanding proof obligations. A match never authenticates API/ABI/behavior; the locked M3 canon is unchanged.
- `m3:code-agent` can also consume a strictly sorted, operator-curated **semantic operator catalogue** (`m3.smart.operatorCatalog`) alongside `m3.smart.machineEvidence`. Exact logic/structural hash hits become only REVIEW_ONLY operator-family hints; unmatched and ambiguous leaves remain typed residue. The catalogue SHA-256 and source-bound projection participate in both nomination and staged-verifier bindings. It never claims effects, contract equivalence, or automatic source mutation. See [code-agent contract](docs/CODE_AGENT.md).
- `m3:code-agent` optionally consumes a current-file-bound **structural/logic/SimHash atom ledger** and guides ChromeLLM with a versioned, digest-bound **M3 Machine-Oriented Programming law packet** (18 mandatory plus eleven additional reviewed analysis and proof-provenance rules) packaged in the plugin. Deterministic patternization exposes at most 24 ranked atom-pair review candidates with contract-signature risk, plus AST control-flow/effect-cue counts and an opt-in JDK 21 compiler-attribution pass (`m3.smart.attributeTypes=true`) that binds static call targets and type digests when all selected sources resolve. The hashes and bindings provide signals, not proof; authority labels distinguish local gates, producer statements, advisory evidence, and proof still outstanding. `m3:code-agent-verify` is a **separate**, explicitly approved, offline/pinned OpenRewrite `dryRun` → staged `run` → Maven `verify` → fixed-point replay workflow, restricted to FILE scope. It keeps original sources unchanged and does not authenticate semantic equivalence or promote recipes. See [code-agent contract](docs/CODE_AGENT.md).
- New `m3:machine-fingerprints` produces this ledger locally from the JDK 21 javac syntax AST, after validating canonical preparation and atom/pattern checkpoints. It emits bounded per-atom structural, normalized-logic-cue, SimHash and syntactic contract hashes, with deterministic parallel per-file fan-in. The default is syntax-only; explicit `m3.smart.attributeTypes=true` adds source-bound compiler type/call symbols under a dependency-free admission gate, still without physical atomization or equivalence proof. See [code-agent contract](docs/CODE_AGENT.md).

### Persisted module computations in M3DB

`m3:machine-fingerprints` can persist completed Java syntax/atom fingerprints in
Synexia's canonical native **MIndexDB** engine. Each Maven module owns a store at
`${project.basedir}/.m3/maven/mindexdb`. MIndexDB writes one sealed `.miart` artifact
for each selected source root; ordinary single-root use therefore has one data
file per module, plus Maven's small sibling `mindexdb.lock` coordination file.
An unchanged source file reuses its prior result across Maven invocations without
running javac parsing and fingerprint computation again. The store remains outside
`target`, so the next fingerprint run can recreate cleaned reports from valid
cached computations. Separate modules and source roots do not share entries.

The installed `com.synexia.mindex.db.MIndexDB` provider owns its native artifact
format and replacement through `open`, `findArtifact` and `putArtifact`. This is
Synexia's Java/NIO engine; it needs no SQLite, H2, Derby or JNI library. Maven adds
a bounded application record and coordinates access to this module-owned store.
The adapter does not bundle, download or implement a database engine.

| Property / CLI option | Value | Behavior |
| --- | --- | --- |
| `m3.smart.moduleDb` / `--module-db` | `auto` (default) | Use an installed MIndexDB provider; compute normally when it is unavailable or cache I/O is refused. |
| Same | `off` | Run the original computation without opening or writing MIndexDB. |
| Same | `required` | Require the native provider and report storage failures as goal failures. |

Add a qualified MIndexDB build and its transitive dependencies to the Maven plugin
realm. For the provider's current Maven coordinates, a consuming project can use:

```xml
<plugin>
  <groupId>io.synexia</groupId>
  <artifactId>m3-smart-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <dependencies>
    <dependency>
      <groupId>com.synexia</groupId>
      <artifactId>synexia-mindex-db</artifactId>
      <version>1.0.0-SNAPSHOT</version>
    </dependency>
  </dependencies>
</plugin>
```

These artifacts must already be installed or available from your configured
Synexia repository. The public Maven fork does not publish the provider. Then run:

```sh
mvn io.synexia:m3-smart-maven-plugin:0.1.0-SNAPSHOT:machine-fingerprints \
  -Dm3.smart.sourceRoot=src/main/java \
  -Dm3.smart.preparationReport=receipts/preparation.json \
  -Dm3.smart.atomPatternManifest=receipts/atom-pattern.tsv \
  -Dm3.smart.moduleDb=required
```

The preparation and atom-pattern paths must identify current receipts from the
existing canonical producers. Existing evidence requirements still apply. The
standalone Java CLI resolves the same provider from its application classpath.

Reuse is bound to the canonical module/source-root identity, relative filename,
SHA-256 of source bytes, source size, producer bytecode, parser options, exact
`jdk.compiler` resource contents, and runtime identity. Same-size edits with
restored timestamps still miss. An added file is computed, a removed file is
omitted from the replacement image, and compiler/producer changes invalidate
prior syntax rows. Worker count does not change the result. Compiler and producer
identities are calculated once per loaded plugin/JVM.

Every run still validates canonical evidence, enumerates and hashes current
inputs, and rechecks them after computation. Attribution and may-flow analysis
run when requested because their dependency scope is wider than a single source
file. This cache does not skip compilation, tests, resource processing, install,
deploy, watch-mode clean builds, or any other lifecycle goal. The merged
[polyglot task cache](../m3-polyglot-maven-plugin/README.md#persistent-module-database)
and opt-in [ordinary Maven lifecycle cache](extension/README.md) provide separate
build-reuse capabilities with their own admission rules and storage paths.

The `moduleDatabase` object in `target/m3-smart/machine-fingerprints.json` reports
`hits`, `misses`, `filesComputed`, `mode`, `provider`, `status`, and `reason`. A warm
run reports zero files computed and `unchanged`, without rewriting its artifact.
`stored` records a successful replacement. `superseded` means a concurrent writer
already committed a different image; the current computation succeeds without
replacing it. `bypassed` identifies refused cache storage in automatic mode.
Source hashes and cache checksums establish identity and detect accidental
corruption; local cache files are not independent semantic proofs.

Publication happens only after the current computation and its output validation
succeed. Short OS locks protect both reads and writes; an expected-image comparison
prevents stale writers from replacing newer state. A loaded cache keeps an
independent immutable payload while subsequent invocations publish new artifacts.
Failed computations and cancellation before publication retain the prior accepted
image. Once `putArtifact` starts, replacement and temporary-file cleanup belong to
MIndexDB; the adapter does not promise rollback on cancellation inside that call.
The provider currently falls back to ordinary replacement where atomic moves are
unsupported and does not promise an fsync-backed power-loss commit. A lost or
incomplete local cache is recomputed in automatic mode. This is reusable analysis
state, not a successful-build or lifecycle completion receipt.

Linked parents, artifacts and lock files, excessive artifact sizes, foreign
artifact metadata, and malformed application records are refused. Corrupt files
are preserved for diagnosis. To reset the cache, remove `.m3/maven/mindexdb` and
its sibling `mindexdb.lock` after the module's Maven processes have stopped.
Projects can add `**/.m3/maven/` to `.gitignore` for this local state.
The store and its lock are reserved M3 state (`ModuleStateDb.reservedStatePath`):
the guarded lifecycle fingerprint excludes them, so a cache write never
invalidates the module's lifecycle receipts, and polyglot tasks can neither
cache inputs from nor write outputs into them.

The real-provider regression lane runs through the standalone Maven build:

```sh
mvn -f tools/m3-smart-builder/pom.xml -Pm3-module-db \
  -Dm3.smart.mindexdbClasspath=/absolute/path/to/qualified/provider-and-dependencies.jar verify
```

`m3.smart.mindexdbClasspath` can also be a platform-separated classpath containing
the provider and every required dependency. This lane requires the actual native
MIndexDB implementation. It tests cold/warm runs, fresh JVMs, same-time content
changes, added/deleted files, compiler identity, module isolation, clean output
recreation, corruption, writer races, interruption and retained attribution.
Ordinary `verify` exercises automatic fallback, off/invalid modes and Mojo forwarding.

- `-Dm3.smart.requireMachineEvidence=true` optionally makes that source-bound ledger mandatory before `m3:code-agent` will nominate a recipe. A missing or malformed `-Dm3.smart.machineEvidence` is refused before model inference; existing builds continue with the default `false`. The reusable [OpenRewrite property recipe](recipes/enable-strict-machine-evidence.yml) makes this gate declarative without attaching any goal to Maven's default lifecycle. No fingerprint counts as contract or behavior proof.
- The canon gate (`sh m3-build.sh canon <packet.tsv> <run-dir>` or `mvn m3:canon`) is the hard M3-SCALE run contract: task packet, fixed stage order diff, atom_pattern, lint, compile, test, runtime, scope locks, mandatory atomization and patternization of every changed file, idempotence and the output contract; any violation fails. See [the canon gate contract](docs/CANON_GATE.md).
- Build hooks can require canonical Java atomization/patternization checkpoint manifests before inference using `m3.smart.requireAtomPattern=true` and `m3.smart.atomPatternManifest`. The gate validates current Java scope coverage and ordered digest binding; non-Java files remain assigned to their existing lanes. It does not authenticate host semantic/JIT proof execution. See [the receipt contract](docs/BUILD_ADVICE_HOOKS.md#require-canonical-java-atomization-and-patternization-receipts).

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

There is no browser provider: the `chrome` and `bridge` providers were removed on 2026-11-17. The existing
candidate-file route remains available unchanged.

## Verification

`scripts/verify.sh` compiles the dependency-free Java core and runs its offline tests with JDK 21. It also executes the Mojo adapter bodies against minimal Maven API stubs. These adapter tests do not qualify Maven plugin descriptor generation or real Maven API injection. The Maven verification goal explicitly binds its test fixture root to `${project.build.directory}`, independent of the caller's working directory. The code-agent verifier uses a synthetic Maven executable in offline tests; a genuine OpenRewrite run is a distinct qualification. A real Maven plugin build, a real ChromeLLM MCP session, network artifact fetching and native GPU execution require separate evidence. Never treat the offline fixtures as those qualifications.

## Scope boundaries and repository ownership

`hsoliwal/maven` owns this opt-in plugin and plugin goal semantics. `hsoliwal/ChromeLLM` owns MCP stdio, lazy GGUF and JNI providers. `hsoliwal/dawn` and `hsoliwal/synexia-llmcpp` own native WebGPU components. `hsoliwal/com.synexia` owns reusable recipes, pattern evaluation and proof policy. Do not merge these contracts into a single mutable Maven Core file.

## Build advice hooks

`m3:plan`, `m3:diagnose` and the additive `m3:build-hook` goal ask the local llama.cpp provider (`cli` or `jni`)
before and after a build, including after failed compilation. See [Build advice hooks](docs/BUILD_ADVICE_HOOKS.md)
for setup, failure preservation, the operator-chosen CPU summarizer whose lines join the facts, and
`failOnError=false` for binding the hook into a POM's lifecycle without letting advice change the build result.

### Source-specific signed provenance (V2)

The newer nine-column signed operator registry pins the **current machine
evidence SHA-256, exact semantic leaf SHA-256 and archived staged source
digest** in addition to the reviewed recipe and operator catalogue. The
original seven-column registry remains supported, but its attestation is
CATALOGUE_ONLY and does not identify a particular source atom. Source-bound
signing is recommended for any leaf-level proof review, and neither format
grants source-mutation or promotion authority.

### Signed operator-proof provenance (opt-in)

The existing m3:code-agent and m3:code-agent-verify goals can read externally
signed operator-proof receipts via m3.smart.operatorProofRegistry,
m3.smart.operatorProofDir, m3.smart.operatorProofPublicKey, and an independently
verified m3.smart.operatorProofPublicKeySha256. This additionally requires
source-bound machineEvidence and operatorCatalog inputs. The signed data pins
an approved FILE-scoped recipe implementation, the **exact operator catalogue**,
the syntactic contract, a successful staged verifier receipt, an external canon
declaration and the six mandatory M3 output artifacts. Maven authenticates
their Ed25519 signatures and SHA-256 digests, **not** their semantic truth.
All evidence is rechecked by the separate staged verifier; no direct source
mutation, automatic promotion, model-supplied commands or changes to default
Maven behavior are permitted. See
[OPERATOR_PROOF_REGISTRY.md](docs/OPERATOR_PROOF_REGISTRY.md).

## Coding agent — pinned OpenRewrite recipes

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
  -Dm3.smart.llmBackend=cli \
  -Dm3.smart.chromeLlmJar=/approved/chromellm-standalone.jar \
  -Dm3.smart.llamaBin=/approved/llama-cli \
  -Dm3.smart.modelPath=/approved/model.gguf
```

See [the complete input, replay and promotion contract](docs/CODE_AGENT.md).
