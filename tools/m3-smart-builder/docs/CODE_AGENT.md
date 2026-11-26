<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Machine-Oriented Coding Agent (experimental)

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

CI can make these fingerprints a mandatory admission prerequisite for
`m3:code-agent` by setting `-Dm3.smart.requireMachineEvidence=true` together
with `-Dm3.smart.machineEvidence=<path-to-generated-ledger>`. With this
explicit opt-in, a missing ledger, an invalid boolean setting, or a stale,
unsorted or incomplete ledger refuses nomination **before model inference**.
The default remains `false` for existing consumers. This check establishes
current-source binding and syntactic coverage, not behavioral equivalence;
the producer's AST/logic/contract cues remain untrusted candidate evidence.

### Machine-Oriented Programming fingerprints (optional)

For an existing AST/atom producer that also emits structural, normalized logic
and SimHash fingerprints, pass `-Dm3.smart.machineEvidence=.reports/machine.tsv`.
The existing seven-column sorted UTF-8 TSV schema is:

`source_path<TAB>source_sha256<TAB>atom_id<TAB>structural_sha256<TAB>logic_sha256<TAB>simhash64<TAB>contract_sha256`

For additive source-level static-analysis cues, a producer may append exactly
two columns: `control_flow_mask<TAB>effect_cue_mask`. Both are four-digit
lowercase hex masks with strictly checked, versioned bit assignments. The
seven-column form remains accepted without inventing missing analysis.
For explicitly compiler-attributed work, four further columns are appended:
`resolved_call_sites<TAB>unresolved_call_sites<TAB>attributed_type_sha256<TAB>call_targets_sha256`.
With `-Dm3.smart.analyzeFlow=true`, eight more columns are appended:
`cfg_nodes<TAB>cfg_edges<TAB>cfg_sha256<TAB>local_read_sites<TAB>local_write_sites<TAB>reaching_def_links<TAB>def_use_sha256<TAB>flow_incomplete_mask`.
The seven-, nine-, thirteen- and twenty-one-column ledgers are independently validated;
no consumer invents absent compiler facts.

Paths are relative to `sourceRoot`, `source_sha256` binds each row to the current
whole Java file, and every selected source file must have at least one atom
row. `simhash64` is exactly 16 lowercase hexadecimal characters. This plugin
checks coverage, order and current file SHA-256, and computes bounded exact-hash
duplicate-group counts and SimHash Hamming-distance-near-pair counts (distance
at most 3). It **does not recompute producer AST or logic hashes**. The resulting
counts, receipt SHA-256, and explicitly unverified authority flags are bound
into the model request, nomination and verification replay. Patternization
adds **at most 24 deterministic atom pairs**, ranked by exact normalized logic,
then exact structure, then SimHash distance ≤3. Each pair carries source paths,
atom IDs, distance, syntactic-contract-match status and `REVIEW_ONLY` authority.
When both atoms have the opt-in 21-column ledger, it also carries
`mayFlowCompared`, `mayFlowFingerprintMatches`,
`localDefUseFingerprintMatches`, and a union of incomplete masks. Flow matches
sort ahead of flow mismatches within an equal similarity/contract group; no
flow is explicitly marked as unavailable, never silently as a match.
The total candidate-pair count and truncation flag remain visible, so a prompt
cannot misrepresent the bounded sample as a complete search result. These are
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
  -Dm3.smart.machineEvidence=target/m3-smart/machine-evidence.tsv \
  -Dm3.smart.requireMachineEvidence=true
```

For a Java-21-only source root that resolves against **the platform modules
and its own selected Java files**, an opt-in compiler pass can be requested by
adding `-Dm3.smart.attributeTypes=true` to `m3:machine-fingerprints`. The
default remains `false`. The pass uses the JDK compiler `parse()` and
`analyze()` APIs with annotation processing disabled, a deliberately empty
external classpath, and a 256-file/8-MiB attribution budget. It freezes atom
identities **before** analysis, because javac may inject synthetic constructors.
Missing dependencies, compiler errors and divergent syntax/attributed atoms
refuse the run and invalidate any previously successful generated ledger.
There is no automated model download or Java source edit.

The opt-in thirteen-column ledger binds each atom to a SHA-256 of its
compiler-reported type and of its statically resolved method-call targets,
plus resolved/unresolved invocation counts. These are *static compiler
symbols*, not a complete call graph: virtual dispatch, reflection, class
loading, JNI effects, aliasing and runtime behavior remain unproven. On JDK 21
the pass selects source level 21; on newer JDKs it uses `--release 21` to
avoid targeting newer platform APIs.

For a more precise *advisory* pass, set both `-Dm3.smart.attributeTypes=true`
and `-Dm3.smart.analyzeFlow=true`. The latter is rejected without compiler
attribution. This constructs a bounded, deterministic statement-level
**may-flow graph** for sequential blocks, if/else, for/while/do-while,
enhanced for, return/throw, break/continue, and synchronized entry. It uses
compiler-resolved local/parameter symbols to iterate a may-reaching-definition
fixed point, recording only counts and digests. A per-atom four-digit
`flow_incomplete_mask` explicitly flags unmodelled exceptional, switch/try,
nested, expression-short-circuit, monitor, heap, unbound and control paths.
The limits are 256 nodes, 1024 edges and 8192 links per atom, and 16384 nodes,
65536 edges and 131072 links across the selected source root; over-budget
work refuses and invalidates its output. The result is **not** javac's full
flow graph, not SSA, not a verified alias/effect model and not proof of
semantic equivalence. `flowFacts.cfgIsComplete` and
`flowFacts.reachingDefinitionsAreProof` are always false. The default goal
and legacy evidence schemas remain unchanged. A reviewed OpenRewrite property
recipe is available in `recipes/enable-machine-flow.yml` for opting in.

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

The extended ledger additionally records **syntactic static-analysis cues**
for each candidate atom: branch/loop/switch/try/throw/return/jump/yield bits,
and call/potential-write/allocation/throw/synchronization bits. The consumer
exposes aggregate atom counts for these categories in the bounded ChromeLLM
prompt. Every call target is **unresolved**; an assignment is only a potential
write. These masks neither construct a compiler CFG nor establish purity,
aliasing, external effects, or resolved type/call graph semantics. Those remain
explicit typed residue for future JDT/CodeQL/host compiler proof gates.

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
tests and behavior proof in authority. No entire source text is sent to the model.

### Versioned machine law packet (M3 policy)

This is the `AtomizerPattenrizerM3` Machine-Oriented Programming rule substrate:
the LLM is a constrained speculative operator, while the machine owns evidence,
recipes, verification, and canonical promotion.

The 18 required M3 laws and eleven additional reviewed analysis, operator, and proof-provenance rules
(29 in the current packet) are materialized as the packaged resource
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

The model receives the complete bounded policy packet and its authority labels,
not a request to invent arbitrary fixes. The policy is packaged with the Maven
plugin so it is independently reviewable in the pull request and does not
depend on hidden browser prompts. This is **operational distillation of coding
discipline into rules and recipes**, not access to model weights.

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
  -Dm3.smart.llmBackend=cli \
  -Dm3.smart.chromeLlmJar=/approved/chromellm-standalone.jar \
  -Dm3.smart.llamaBin=/approved/llama-cli \
  -Dm3.smart.modelPath=/approved/model.gguf
```

This reuses the existing ChromeLLM MCP stdio implementation with local llama.cpp
(`cli`, or `jni` with `m3.smart.jniLib`); there is no browser provider. The
provider options are the same as the build hooks'. No cloud fallback is chosen.
A model-backed invocation may fail when llama.cpp or the model is unavailable,
or when the response is not valid JSON.

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

To replay a previously produced JSON candidate without starting a model, omit
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
source-writing agent, or claim that model inference/real OpenRewrite/JUnit
project verification was run by the synthetic offline tests.


## Source-bound semantic operator catalogue (opt-in)

The operator catalogue is a persistent, operator-maintained input used to **guide** the
coding agent; it is not an executable recipe list, a semantic-equivalence database,
or a replacement for the sealed M3 canonical specification. In the active project
checkout, create a sorted UTF-8 TSV with this exact header (trailing LF required):

`operator_id<TAB>family<TAB>match_kind<TAB>fingerprint_sha256`

`operator_id` is a unique lowercase identifier; `family` must be one of
`MAP FILTER FLATMAP FOLD REDUCE ZIP JOIN GROUP PREDICATE ADAPTER STRATEGY STATE PIPELINE CONTEXT OTHER`.
`match_kind` is `LOGIC` or `STRUCTURAL`, each matching exactly the corresponding
producer-reported 64-hex SHA-256 of an atom. Entries are sorted by operator ID,
strictly parsed, and limited to 128. **No semantic identity or proof is inferred
from an equal hash.** Colliding entries remain ambiguous; no candidate is
automatically accepted.

Use both the existing, freshly validated machine evidence and the new optional
catalogue when nominating and verifying a recipe:

```sh
mvn io.synexia:m3-smart-maven-plugin:0.1.0-SNAPSHOT:code-agent \
  -Dm3.smart.sourceRoot=src/main/java \
  -Dm3.smart.preparationReport=.reports/preparation.json \
  -Dm3.smart.atomPatternManifest=.reports/atom-pattern.tsv \
  -Dm3.smart.recipeCatalog=.reports/approved-recipes.tsv \
  -Dm3.smart.machineEvidence=target/m3-smart/machine-evidence.tsv \
  -Dm3.smart.operatorCatalog=.reports/approved-operators.tsv \
  -Dm3.smart.requireMachineEvidence=true
```

Supply those same `machineEvidence` and `operatorCatalog` properties to the
separate `m3:code-agent-verify` invocation. Omitting the catalogue leaves the old
nomination and verifier schema/digest unchanged. The catalogue cannot be used
without the machine ledger. Any change to the catalogue or ledger invalidates
the evidence-bound candidate and must be re-nominated before replay.

The bounded `semanticOperatorFacts` projection provides at most **24** semantic
leaves, prioritizing unknown and ambiguous labels, with a stable SHA-256 leaf
identity, optional family/label and a typed residue. Bitmask proof obligations
remain set for observable contracts (bit 0), effects (bit 1), call dispatch
(bit 2), unavailable flow (bit 3), and incomplete flow (bit 4).
`semanticProofAuthenticated` and `promotionAuthority` are always false.
Labels do not become verified operators until their separate owner supplies
compiler, contract, effect, behavioral and M3 canon proof.

The packaged rulebook adds two REVIEW_ONLY laws for operator recognition and
typed residue. It does **not** replace or edit the locked M3 canon. The outcome
is an explicit, replayable context projection for LLM recipe nomination, not
training or extraction of a browser model.


### Contract-aware semantic leaves (advisory)

The operator catalogue also accepts a second, strictly validated schema that
appends `contract_sha256` to every operator row. If the digest of the atom's
**syntactic** signature does not match an operator's optional pin, a matching
logic/structural hash is classified as `CONTRACT_CUE_MISMATCH`, not as a matched
operator. The original four-column catalogue remains valid. Entries cannot
mix the two schema widths.

Each bounded semantic leaf contains a `contractEnvelope` derived from the
independently source-validated machine ledger. It may contain the syntactic
contract SHA-256, control/effect syntax masks, javac-attributed type/call-target
SHA-256, unresolved-call count, and may-flow/def-use hashes. Missing facts are
explicitly null or -1, rather than silently interpreted as safe.

The proof-obligation mask is conservative even when a 21-column may-flow
receipt reports no incomplete paths: observable contracts, effects,
dynamic/interprocedural dispatch, full flow, and API/ABI compatibility remain
external proof obligations. An incomplete may-flow mask adds another explicit
obligation. The envelope is `PRODUCER_REPORTED`; hash matches remain `REVIEW_ONLY`.
No Java source modification, verified operator promotion or semantic equivalence
is implied. These additional fields and the new \`contract-envelope\` rule are
included in the versioned, content-bound model evidence packet and are
revalidated before staged recipe execution.

## Signed evidence from an external M3 proof owner

The optional, Ed25519-signed and independently pinned operator-proof registry
is specified in [OPERATOR_PROOF_REGISTRY.md](OPERATOR_PROOF_REGISTRY.md).
It augments the source-bound operator catalogue with authenticated provenance
and all six mandatory M3 output-artifact digests. The model receives bounded
review-only attestation facts; staged verification rechecks them. An external
signature **never** authenticates semantic equivalence or authorizes a
FILE-to-PACKAGE/API promotion. Omitting the registry retains the legacy
nomination and verification contract.
