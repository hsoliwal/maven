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

### Generate a local Java 21 AST fingerprint ledger

The additive, opt-in `m3:machine-fingerprints` goal now materializes these
candidate hashes using the **JDK 21 javac AST parser**. It requires the same
fresh canonical preparation receipt and Java atom/pattern checkpoint manifest
as `m3:code-agent`. No LLM or model download is involved:

```sh
mvn io.synexia:m3-smart-maven-plugin:0.1.0-SNAPSHOT:machine-fingerprints \
  -Dm3.smart.sourceRoot=src/main/java \
  -Dm3.smart.preparationReport=.reports/preparation.json \
  -Dm3.smart.atomPatternManifest=.reports/atom-pattern.tsv \
  -Dm3.smart.machineWorkers=4

mvn io.synexia:m3-smart-maven-plugin:0.1.0-SNAPSHOT:code-agent \
  -Dm3.smart.sourceRoot=src/main/java \
  -Dm3.smart.preparationReport=.reports/preparation.json \
  -Dm3.smart.atomPatternManifest=.reports/atom-pattern.tsv \
  -Dm3.smart.recipeCatalog=.reports/approved-recipes.tsv \
  -Dm3.smart.machineEvidence=target/m3-smart/machine-evidence.tsv
```

The producer inventories every selected Java file, uses the compiler AST
instead of text regex to isolate method, lambda and initializer candidates,
then computes per-atom SHA-256 of tree kinds (structural), SHA-256 of tree
kinds plus literal/member cues (normalized logic candidate), a 64-bit
kind-frequency SimHash, and a separately hashed syntactic signature/contract
cue. It deliberately omits local variable names from the logic cue and
preserves operator kinds and literal values. These algorithms are **syntax
fingerprints, not type-attributed semantic hashes**. They can collide for
semantically different code. API, ABI, JNI effects, behavior and contract
equivalence must still be proven externally.

Files are parsed independently with 1–8 bounded workers; results fan in in
source-path and atom-id order. Output is the fixed
`target/m3-smart/machine-evidence.tsv` plus
`target/m3-smart/machine-fingerprints.json`. The generated ledger is passed
through the same independent `MachineEvidence.inspect` consumer check as
external ledgers. A refused regeneration invalidates any previous successful
generated ledger. Normal Maven builds are unaffected, and no Java sources
are modified by this goal.

The model receives a bounded rule set derived from M3Scale: inventory and
contracts first; string/comment masking before regex; atomization before
patternization; private-leaf transformation; FILE→PACKAGE→MODULE→REACTOR→API
promotion only with real proof; deterministic recipes before probabilistic
inference; and structural/logic/SimHash similarity strictly below compiler,
tests and behavior proof in authority. No entire source text is sent to Chrome.

### Versioned machine law packet (M3 policy)

This is the `AtomizerPattenrizerM3` Machine-Oriented Programming rule substrate:
the LLM is a constrained speculative operator, while the machine owns evidence,
recipes, verification, and canonical promotion.

The 18 required M3 laws are materialized as the packaged resource
`src/main/resources/io/synexia/maven/smart/mop-rules.tsv`. Each sorted row is
`rule_id<TAB>stage<TAB>authority<TAB>directive`; the Java rulebook checks the
complete required set, bounded syntax, and an exact schema before any model
invocation. Its content SHA-256 appears as `machineRulebookSha256` in the
nomination and verification receipts and participates in the model candidate's
`evidenceBindingSha256`. Changing even the wording of a directive therefore
requires a fresh nomination rather than silently replaying an obsolete one.
New reviewed rules may be appended additively in sorted order (up to 64 rows),
but cannot claim `MECHANICAL` authority without a corresponding implemented
gate. The four actually enforced admission rules retain their required labels.

The four authority classes are deliberately distinct:

- `MECHANICAL`: a rule has an actual local admission check (current source
  receipt, allowlisted recipe, or exact response schema).
- `PRODUCER_REPORTED`: AST, logic, structural, contract and pattern facts are
  external owner statements; Maven can bind their bytes but cannot prove them.
- `REVIEW_ONLY`: heuristics, SimHash near matches, ranking and typed residue
  remain proposals.
- `EXTERNAL_GATE`: independent masking, API/ABI/JNI behavior, canon and
  hierarchical promotion proofs are required before claiming completion.

Chrome receives the complete bounded policy packet and its authority labels,
not a request to invent arbitrary fixes. The policy is packaged with the Maven
plugin so it is independently reviewable in the pull request and does not
depend on hidden browser prompts. This is **operational distillation of coding
discipline into rules and recipes**, not access to Chrome's model weights.

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
LLM text or shell fragments. The rulebook digest from nomination must match
the verifier's packaged rulebook. Only `FILE`-scoped recipes are executable in this
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
