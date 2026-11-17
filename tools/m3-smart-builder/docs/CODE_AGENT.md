<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Chrome AI coding-agent recipe nomination (experimental)

The `m3:code-agent` goal is an **additive, opt-in** continuation of the existing
ChromeLLM `m3:build-hook`. It turns bounded, source-bound Java atom/pattern
facts into a proposed, already-catalogued OpenRewrite recipe. It neither
applies a recipe nor runs Maven, a compiler, tests, scripts or model-supplied
commands. No regular Maven lifecycle invokes the goal automatically.

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

## Output and promotion policy

`target/m3-smart/code-agent.json` records a hash-bound `proposed` nomination,
`no-match`, `no-applicable-recipe`, or `awaiting-review` outcome. Refused inputs
replace any earlier successful output with `status: refused` and a nonzero
exit whenever the guarded output directory can be safely opened. Output flags remain `modelOutputTrusted: false`, `recipeApproved: false`,
`recipeExecuted: false`, `semanticProofAuthenticated: false` and
`verificationAuthenticated: false`.

After independently reviewing the selected, pinned recipe, the **separate**
approved OpenRewrite runner (or a deliberately configured, pinned
`rewrite-maven-plugin`) can run a dry run and then apply the recipe on a
review branch. Compile, unit tests, API/ABI/JNI compatibility, behavior,
runtime and convergence checks remain independent gates. Only their actual
successful results should be promoted into the upstream verified recipe
catalogue; model output alone must never populate it.

This is a working recipe-nomination slice of the larger distillation workflow,
not a model-weight exporter, autonomous code-writing agent or claim that Chrome
inference/real Maven descriptor verification was run by offline tests.
