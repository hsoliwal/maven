<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Chrome AI before and after Maven

The existing Smart Builder can invoke ChromeLLM's real `chrome` provider through
its MCP stdio client. The Java controller remains independent of the application
being built. Existing CLI/JNI providers, Maven arguments and build exit statuses
retain their contracts. AI is opt-in and its output is advisory.

## Reusable GitHub Action

The product action is `.github/actions/chrome-ai`. It requires Linux, Bash and a
complete JDK 21 or later. It compiles its dependency-free Java controller into a
temporary directory, calls the existing ChromeLLM module, and removes only its own
temporary classes afterward. It never invokes Maven itself.

Prepare an independently built `chromellm-standalone` JAR, a Chrome executable,
and a dedicated profile. The existing `m3-smart-builder.yml` live lane demonstrates
source-pinned provider bootstrap and downloading an exact Chrome binary using a
commit-pinned setup action. That lane explicitly permits Chrome's own lazy model
download. It is manually dispatched and disabled for ordinary pull-request checks.

For a checkout containing this action:

```yaml
- name: AI before Maven
  continue-on-error: true
  uses: ./.github/actions/chrome-ai
  with:
    phase: pre-build
    build-directory: .github-build/${{ github.run_id }}-${{ github.run_attempt }}
    chrome-llm-jar: ${{ env.CHROME_LLM_JAR }}
    chrome-path: ${{ env.CHROME_BIN }}
    chrome-profile: ${{ runner.temp }}/chrome-ai-profile
    allow-download: 'true'
    timeout-seconds: '600'

- name: Maven
  id: build
  shell: bash
  run: mvn -B -ntp --log-file build.log clean verify

- name: AI after Maven
  if: ${{ !cancelled() && (steps.build.outcome == 'success' || steps.build.outcome == 'failure') }}
  continue-on-error: true
  uses: ./.github/actions/chrome-ai
  with:
    phase: post-build
    build-outcome: ${{ steps.build.outcome }}
    log-file: build.log
    build-directory: .github-build/${{ github.run_id }}-${{ github.run_attempt }}
    chrome-llm-jar: ${{ env.CHROME_LLM_JAR }}
    chrome-path: ${{ env.CHROME_BIN }}
    chrome-profile: ${{ runner.temp }}/chrome-ai-profile
    allow-download: 'true'
    timeout-seconds: '600'
```

Other repositories can use `hsoliwal/maven/.github/actions/chrome-ai@<reviewed-commit>`.
Set `project-directory` to the Maven project, and optionally `source-root` to a
bounded Java/native source subtree. Use the same dedicated Chrome profile for
both calls. Avoid parallel jobs sharing a profile. `allow-download` defaults to
`false`; without explicit permission the Chrome provider refuses unprepared models.
The action does not select a cloud provider or bypass Chrome eligibility checks.

The two reports are `pre-build.json` and `post-build.json` under
`<project>/<build-directory>/m3-smart/`. Use a directory outside `target` when the
build runs `clean`, and a distinct build directory per CI run. The action outputs
the report path and the hook exit code. Keep the Maven step's normal failure
behavior; `continue-on-error` belongs only on advisory AI steps. The live acceptance
fixture deliberately uses it on its expected-failing Maven step, then explicitly
asserts that the observed outcome was `failure`.

## Java command and Maven goal

The installed plugin JAR also contains a dependency-free executable class:

```sh
java -cp m3-smart-maven-plugin-0.1.0-SNAPSHOT.jar \
  io.synexia.maven.smart.BuildHookMain \
  --phase post-build --project-dir /work/project --build-dir .github-build/run-1 \
  --build-outcome failure --log-file build.log --source-root src \
  --backend chrome --chrome-llm-jar /tools/chromellm-standalone.jar \
  --chrome-binary /tools/chrome --chrome-profile /cache/chrome-ci \
  --headless true --allow-download true --timeout-seconds 600
```

Native providers use `--backend cli --llama-bin ... --model ...` or
`--backend jni --jni-lib ... --model ...`. Do not combine Chrome and native
settings. Omit the backend, or select `none`, to collect facts without inference.

The Maven goal uses the same implementation:

```sh
mvn io.synexia:m3-smart-maven-plugin:0.1.0-SNAPSHOT:build-hook \
  -Dm3.smart.phase=pre-build -Dm3.smart.llmBackend=chrome \
  -Dm3.smart.chromeLlmJar=/tools/chromellm-standalone.jar \
  -Dm3.smart.chromeBin=/tools/chrome -Dm3.smart.chromeProfile=/cache/chrome-ci \
  -Dm3.smart.chromeDownload=true -Dm3.smart.llmTimeoutSeconds=600
```

`m3:plan` and `m3:diagnose` accept the same Chrome provider properties.
`m3.smart.chromeHeadless` defaults to `true`; download defaults to `false`.
Bind `build-hook` to `validate` for preparation and `verify` for successful-build
analysis if desired. The Maven lifecycle can stop before `verify`, so GitHub's
explicit post step remains responsible for failed-build diagnosis.

## Require canonical Java atomization and patternization receipts

The optional receipt gate consumes the existing canonical Java convergence DAG's
checkpoint output. It does not implement another atomizer or execute source changes.
Select `require-atom-pattern: 'true'` and an `atom-pattern-manifest` on both Action
calls, together with an explicit `source-root`. The equivalent Java options are
`--require-atom-pattern true --atom-pattern-manifest receipts/manifest.tsv`;
Maven properties are `m3.smart.requireAtomPattern` and `m3.smart.atomPatternManifest`.
The requirement defaults to false. With no manifest supplied, existing advisory
behavior remains available. A supplied manifest is always validated, even when
the requirement is false.

To make receipt admission a prerequisite for Maven, first run the canonical
producer with its real host transforms and compiler/contract/behavior/JIT
qualification. It must create the manifest and checkpoints for the resulting
source tree; this Action does not produce them. Then insert this mandatory step
before the advisory **AI before Maven**, **Maven**, and **AI after Maven** steps
shown above:

```yaml
- name: Require Java atomization and patternization receipts
  uses: ./.github/actions/chrome-ai
  with:
    phase: pre-build
    backend: none
    source-root: src/main/java
    atom-pattern-manifest: qualified/manifest.tsv
    require-atom-pattern: 'true'
    build-directory: .github-build/${{ github.run_id }}-${{ github.run_attempt }}/preflight
```

This step deliberately has no `continue-on-error`: rejected evidence prevents
Maven from starting under normal GitHub success conditions. Add these same three
inputs to **both** advisory Chrome steps: `source-root: src/main/java`,
`atom-pattern-manifest: qualified/manifest.tsv`, and `require-atom-pattern: 'true'`.
Those advisory steps retain their existing `continue-on-error: true`, provider
configuration, pre/build/post ordering and post-failure condition. Their reports
use the original run directory, preserving the mandatory preflight report in its
own subdirectory. Merely adding the requirement to an advisory step would block
its inference but would not gate Maven. This example assumes the producer output
exists at the stated path; it neither invents a producer goal nor establishes a
repository-wide rollout.

The UTF-8 manifest has these first two lines, with literal tabs in the header:

```text
# m3.atom-pattern.manifest.v1
source_path	input_sha256	output_sha256	checkpoint_path	checkpoint_sha256
```

Every following row names one current `.java` file recursively below `source-root`,
including unchanged files. Source and checkpoint paths are project-relative POSIX
paths without `.` or `..` segments, repeated separators, backslashes or control
characters. Rows are strictly sorted by source path. Each row binds the original
source digest, final source digest, checkpoint file path and exact checkpoint byte
digest. All hashes are lowercase SHA-256. Every current Java file must occur once;
missing, extra, duplicate or reordered rows are refused. The final source digest
must match the current on-disk bytes on every invocation, including post-build.

Each checkpoint TSV is the exact `M3Java21ConvergenceDag` checkpoint format:

```text
round	phase	source_path	before_sha256	after_sha256	compiler_root	contract_root	behavior_root	jit_root
```

There are exactly eight data rows: rounds 1 and 2 each contain `INVENTORY`,
`FILE_ATOMIZATION`, `FILE_PATTERNIZATION`, `FILE_DOCUMENTATION`, in that order.
Each row names the manifest's source path, and the source hashes form one continuous
chain from manifest input to manifest output. Every round-2 row must be unchanged.
Inventory rows must also remain unchanged. A final rescan rejects Java file-set or
observed size/time/file-identity changes during checking.
The four proof roots must be present as SHA-256 identities. Hashes bind the supplied
attestations to this manifest; this consumer does **not authenticate that the host
actually ran compiler, contract, behavior or JIT verification**. The canonical
producer and its executed qualification remain responsible for those claims.

The report and model prompt include manifest, Java source-set and checkpoint-set
identities, Java file/byte counts, checkpoint counts, and the explicit status
`structurally-validated`. They set `semanticProofAuthenticated: false`. Non-Java
ordinary files are counted with disposition `outside-java-dag`; they require their
existing type-specific lanes. This gate does not claim every repository or native,
text or binary file has undergone Java transforms. Its coverage is exactly the
Java files in the selected subtree, independent of the older diagnostic inventory's
2,000-file limit.

Missing, stale, malformed, incomplete or misordered evidence writes a report with
`atomPatternStatus: rejected`, a bounded reason code and `adviceStatus: blocked-evidence`.
The hook exits nonzero **before launching the provider** and retains the observed
Maven outcome. No failed evidence is treated as a successful model or build result.
Keep evidence outside `target` when Maven runs `clean`.

The scanner admits up to 262,144 ordinary files and 262,144 directories; Java files
are limited to 16 MiB each, the manifest to 128 MiB, each checkpoint to 64 KiB, and
paths to 4,096 characters. Exceeding a bound refuses the gate explicitly. Symlinks
and non-ordinary source entries are refused; source, manifest and checkpoint paths
remain within the checkout. As with the existing output contract, the workspace
must be exclusively owned during checking; concurrent adversarial path replacement
is outside this boundary.

## Report contract and qualification

Schema `m3.smart.build-hook.v1` records the phase, supplied build outcome, selected
provider, advice status, and bounded POM/log/source facts. Raw log lines and source
contents are not sent to the model. `unavailable` facts are unknown, not a clean
bill of health. A malformed POM or missing log still produces a post-build report.
Provider failure writes a report with `adviceStatus: failed` and a nonzero hook
status. Model prose cannot change the observed `buildOutcome` or execute code.

`scripts/verify.sh` runs core, adapter and synthetic subprocess tests.
The receipt suite observes a synthetic provider launch marker, checks successful
pre/post admission, and asserts malformed/stale/incomplete receipts are rejected
before any provider invocation. Its 2,001-file fixture verifies that coverage does
not reuse the legacy diagnostic size bound. Synthetic proof roots test parsing and
binding only and are never presented as executed semantic verification.
`scripts/verify-build-hook-workflow.sh` runs an actual failing Maven compilation
between the real action entry points using a clearly labeled synthetic MCP
provider. Neither test claims trained-model inference. The manually dispatched
live lane separately requires a successful real Chrome prompt in both hooks.

Chrome's documented CPU path requires at least four cores and 16 GB RAM, with
22 GB free on the profile volume before initial model download. Runner capacity
alone is insufficient evidence: the actual Prompt API invocation must succeed.
References: https://developer.chrome.com/docs/ai/get-started and
https://developer.chrome.com/docs/ai/prompt-api.
