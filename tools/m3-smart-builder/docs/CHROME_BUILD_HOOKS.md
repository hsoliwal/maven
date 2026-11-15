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

## Report contract and qualification

Schema `m3.smart.build-hook.v1` records the phase, supplied build outcome, selected
provider, advice status, and bounded POM/log/source facts. Raw log lines and source
contents are not sent to the model. `unavailable` facts are unknown, not a clean
bill of health. A malformed POM or missing log still produces a post-build report.
Provider failure writes a report with `adviceStatus: failed` and a nonzero hook
status. Model prose cannot change the observed `buildOutcome` or execute code.

`scripts/verify.sh` runs core, adapter and synthetic subprocess tests.
`scripts/verify-build-hook-workflow.sh` runs an actual failing Maven compilation
between the real action entry points using a clearly labeled synthetic MCP
provider. Neither test claims trained-model inference. The manually dispatched
live lane separately requires a successful real Chrome prompt in both hooks.

Chrome's documented CPU path requires at least four cores and 16 GB RAM, with
22 GB free on the profile volume before initial model download. Runner capacity
alone is insufficient evidence: the actual Prompt API invocation must succeed.
References: https://developer.chrome.com/docs/ai/get-started and
https://developer.chrome.com/docs/ai/prompt-api.
