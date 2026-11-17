<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Chrome AI Machine-Oriented Coding Agent (experimental)

The `m3:code-agent` goal is an **additive, opt-in** continuation of the existing
ChromeLLM `m3:build-hook`. It turns bounded, source-bound Java atom/pattern
facts into a proposed, already-catalogued OpenRewrite recipe. This nomination
goal does not run a compiler or execute model-supplied commands. The **separate**
`m3:code-agent-verify` goal can explicitly stage and execute an approved recipe,
run Maven `verify`, and check idempotence without changing the original checkout's
source. Neither goal is bound to the default Maven lifecycle.

## Source-bound inputs

The goal requires all three independent, project-local inputs:

1. An `m3.atom-pattern.manifest.v1` manifest and its two-round checkpoints,
   covering **every** current `.java` file under `sourceRoot`.
2. A `synexia.smart-build.preparation.v1` report from the approved canonical
   OpenRewrite preparation producer. It must match the current source bytes.
3. A strictly sorted, operator-curated `approved-recipes.tsv` with this exact
   tab-separated header (a trailing LF is required):

   `recipe_id<TAB>pattern<TAB>scope<TAB>openrewrite_recipe<TAB>implementation_sha256`

The `pattern` must exist with a nonzero count in the validated preparation
report. Scopes are `FILE`, `PACKAGE`, `MODULE`, `REACTOR`, `API`; entries are
sorted by `recipe_id`. The final field is the independently reviewed SHA-256 of
the recipe implementation supplied by its owner. **The goal records that
digest, but cannot itself authenticate external source ownership, licensing,
recipe execution or semantic equivalence.** Do not place placeholder digests in
a production catalogue. All catalogue files and candidate files must be
ordinary, non-symlinked files inside the project checkout.

The canonical producer and the operator must provide these receipts. The Maven
consumer does not invent source preparation, file atomization or proof roots.

### Machine-Oriented Programming fingerprints (optional)

For an existing AST/atom producer that also emits structural, normalized logic
and SimHash fingerprints, pass `-Dm3.smart.machineEvidence=.reports/machine.tsv`.
Its exact, sorted UTF-8 TSV schema is:

`source_path<TAB>source_sha256<TAB>atom_id<TAB>structural_sha256<TAB>logic_sha256<TAB>simhash64<TAB>contract_sha256`

Paths are relative to `sourceRoot`, `source_sha256` binds each row to the current
whole Java file, and every selected source file must have at least one atom
row. `simhash64` is exactly 16 lowercase hexadecimal characters. This plugin
checks coverage, order and current file SHA-256, and computes bounded exact-hash
duplicate-group counts and SimHash Hamming-distance-near-pair counts (distance
at most 3). It **does not recompute producer AST or logic hashes**. The resulting
counts, receipt SHA-256, and explicitly unverified authority flags are bound
into the model request, nomination and verification replay. They identify
similarity candidates, never equivalence or permission to refactor.

The model receives a bounded rule set derived from M3Scale: inventory and
contracts first; string/comment masking before regex; atomization before
patternization; private-leaf transformation; FILE→PACKAGE→MODULE→REACTOR→API
promotion only with real proof; deterministic recipes before probabilistic
inference; and structural/logic/SimHash similarity strictly below compiler,
tests and behavior proof in authority. No entire source text is sent to Chrome.

## Use without a model

```sh
mvn io.synexia:m3-smart-maven-plugin:0.1.0-SNAPSHOT:code-agent \
  -Dm3.smart.sourceRoot=src/main/java \
  -Dm3.smart.preparationReport=.reports/preparation.json \
  -Dm3.smart.atomPatternManifest=.reports/atom-pattern.tsv \
  -Dm3.smart.recipeCatalog=.reports/approved-recipes.tsv
```

The deterministic default lists recipes matching verified **structural
admission** and leaves selection as `awaiting-review`. `code-agent.json` is
written through the existing fixed-name, project-local `SafeBuildOutput` guard
under `target/m3-smart/`. This is the mechanical-first path.

## Use the existing ChromeLLM provider

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

This reuses the existing ChromeLLM MCP stdio implementation. The default
`m3.smart.chromeDownload=false` refuses lazy model acquisition; set it to
`true` only when the operator approves the initial browser-managed download.
CLI/JNI/bridge provider options are the same as existing build hooks. No cloud
fallback is chosen. A model-backed invocation may fail when Chrome is not
available, the model is not ready or the response is not valid JSON.

The model sees only bounded counts, hashes and approved recipe metadata, not
raw source text or log lines. The request demands one exact object:

```json
{
  "schema": "m3.smart.code-agent.candidate.v1",
  "evidenceSha256": "<exact evidenceBindingSha256 from the input>",
  "recipeId": "<one applicable recipe_id>",
  "confidence": "MEDIUM"
}
```

`confidence` is a claim from the model, **never** a verification score. To
decline, the model can use an empty `recipeId` and `confidence: "NONE"`.
Additional fields, arbitrary recipe names, stale SHA-256 bindings, free text,
patches and commands are rejected. Existing ChromeLLM MCP does not promise
JSON Schema-constrained inference; strict validation on the Maven side is the
fail-closed boundary.

To replay a previously produced JSON candidate without starting Chrome, omit
`m3.smart.llmBackend` and specify
`-Dm3.smart.codeAgentCandidate=.reports/candidate.json`. Provider and file
inputs are mutually exclusive. The goal rechecks source, preparation, atom
manifest and catalogue **after** the model call, preventing stale suggestions
from being published following a source edit.

## Explicit offline OpenRewrite execution and verification

After reviewing the nominated recipe, provisioning both the recipe JAR and the
OpenRewrite Maven plugin **in an existing offline Maven repository**, and
independently checking their SHA-256 digests, run the opt-in goal:

```sh
mvn io.synexia:m3-smart-maven-plugin:0.1.0-SNAPSHOT:code-agent-verify \
  -Dm3.smart.sourceRoot=src/main/java \
  -Dm3.smart.preparationReport=.reports/preparation.json \
  -Dm3.smart.atomPatternManifest=.reports/atom-pattern.tsv \
  -Dm3.smart.recipeCatalog=.reports/approved-recipes.tsv \
  -Dm3.smart.machineEvidence=.reports/machine.tsv \
  -Dm3.smart.approvedRecipeId=my-reviewed-recipe \
  -Dm3.smart.approveRecipeExecution=true \
  -Dm3.smart.approvedMavenBin=/approved/apache-maven/bin/mvn \
  -Dm3.smart.approvedMavenRepository=/approved/offline-m2 \
  -Dm3.smart.recipeCoordinates=com.synexia:rewrite-recipes:1.0.0 \
  -Dm3.smart.rewritePluginSha256=REPLACE_WITH_A_VERIFIED_64_HEX_DIGEST
```

The producer receipt and catalogue must already have passed `m3:code-agent`
with an exact `proposed` nomination. The selected `implementation_sha256` is
now **checked against the actual cached recipe JAR**, not merely copied to a
report. The plugin JAR (`org.openrewrite.maven:rewrite-maven-plugin:6.66.0`) is
also checked against the operator-supplied SHA-256. Both artifact coordinates
and version are fixed before executing child Maven; the runner uses `-o`, `-B`,
`-ntp` and an explicit local repository. Maven is passed fixed arguments, not
LLM text or shell fragments. Only `FILE`-scoped recipes are executable in this
slice. Other scopes require their independent contract gates first.

The goal makes a bounded, symlink-free project copy inside
`target/m3-smart/code-agent-stage-*`, omitting `.git` and previous `target`
trees. It then executes, in order:

1. `rewrite:dryRun`; reject any project input changes during dry run, and hash
   the OpenRewrite preview at `target/rewrite/rewrite.patch` when present.
2. `rewrite:run`; require Java-only in-scope edits, with no file additions or removals.
3. `mvn -o verify`; require successful Maven verification and unchanged staged inputs.
4. `rewrite:run` again; require a byte-identical fixed point.
5. Revalidate original source/preparation/atom/optional machine receipts and the catalogue.

If Java sources changed but the expected nonempty OpenRewrite dry-run patch was
not produced, verification fails. Its SHA-256 is recorded for independent
diff review before any canon promotion.

It writes a fixed `target/m3-smart/code-agent-verify.json` receipt with stage
path, input/output source digests, changed Java file count, run status and
truthful non-promotion flags. Full logs remain inside the stage at
`target/m3-code-agent/`. The original working sources are never rewritten by
this goal. The stage size is limited to 256 MiB / 20,000 input files, and child
Maven processes have bounded timeouts. **Offline Maven resolver mode does not
sandbox arbitrary code in existing Maven plugins or tests**: execute only
trusted project builds in a controlled machine. A staged `verify` success does
not establish API/ABI, JNI, semantic or runtime equivalence by itself; those
must be checked under the separately configured M3 canon before promotion.

## Output and promotion policy

`target/m3-smart/code-agent.json` records a hash-bound `proposed` nomination,
`no-match`, `no-applicable-recipe`, or `awaiting-review` outcome. Refused inputs
replace any earlier successful output with `status: refused` and a nonzero
exit whenever the guarded output directory can be safely opened. Output flags remain `modelOutputTrusted: false`, `recipeApproved: false`,
`recipeExecuted: false`, `semanticProofAuthenticated: false` and
`verificationAuthenticated: false`.

The staged verifier is now the **separate**, digest-pinned OpenRewrite runner
for the initial FILE scope. Compile, unit tests, API/ABI/JNI compatibility,
behavior, runtime and convergence checks remain independent gates. Only their
actual successful results should be promoted into the upstream verified recipe
catalogue; model output alone must never populate it.

This is an executable **recipe-nomination plus isolated replay** slice of the
larger distillation workflow, not a model-weight exporter, autonomous
source-writing agent, or claim that Chrome inference/real OpenRewrite/JUnit
project verification was run by the synthetic offline tests.
