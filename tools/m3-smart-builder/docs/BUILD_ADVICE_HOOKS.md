<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Build advice before and after Maven

The Smart Builder can ask a local model for advice before and after a Maven build. It uses ChromeLLM's MCP stdio
client, and the model runs on local llama.cpp: either the `cli` backend with a llama.cpp executable, or the `jni`
backend with the JNI library. There is no browser path. The installed-Chrome (`chrome`) provider and the chat
bridge to a Chrome-served model (`bridge`) were removed on 2026-11-17, together with the `chrome-ai` GitHub Action
and its live Chrome lane. Advice is opt-in. The model's text never changes Maven's exit status.

## Java command and Maven goal

The installed plugin JAR contains a dependency-free executable class:

```sh
java -cp m3-smart-maven-plugin-0.1.0-SNAPSHOT.jar \
  io.synexia.maven.smart.BuildHookMain \
  --phase post-build --project-dir /work/project --build-dir .github-build/run-1 \
  --build-outcome failure --log-file build.log --source-root src \
  --backend cli --chrome-llm-jar /tools/chromellm-standalone.jar \
  --llama-bin /tools/llama.cpp/llama-cli --model /models/model.gguf --timeout-seconds 600
```

`--backend jni --jni-lib ... --model ...` selects the in-process JNI library instead. `--chrome-llm-jar` names the
ChromeLLM engine, which takes its name from the product and involves no browser. To collect facts without
inference, omit the backend or select `none`.

The Maven goal uses the same implementation:

```sh
mvn io.synexia:m3-smart-maven-plugin:0.1.0-SNAPSHOT:build-hook \
  -Dm3.smart.phase=pre-build -Dm3.smart.llmBackend=cli \
  -Dm3.smart.chromeLlmJar=/tools/chromellm-standalone.jar \
  -Dm3.smart.llamaBin=/tools/llama.cpp/llama-cli -Dm3.smart.modelPath=/models/model.gguf \
  -Dm3.smart.llmTimeoutSeconds=600
```

`m3:plan`, `m3:diagnose` and `m3:code-agent` accept the same provider properties. You can bind `build-hook` to
`validate`, and to `verify` for analysing a successful build. The lifecycle can stop before `verify`, so diagnosing
a failed build needs an explicit post step after Maven.

### CPU summarizer and lifecycle binding

`--summarizer "<command line>"` (Maven: `m3.smart.summarizer`) adds a CPU summarizer of the operator's choice:

- **Command line:** double or single quotes group an argument; an unterminated quote is refused before launch.
- **Input:** the hook appends the project's `pom.xml` and, after a build, the given log. The command receives these
  original files, and its retained output lines enter the model prompt. Configure it to emit only the information
  appropriate for that prompt.
- **Run:** the command runs in the project directory.
- **Admission:** rejected requested preparation or atom/pattern receipts prevent command launch. The report records
  `summaryStatus: blocked-admission` and empty `summaryLines`; an earlier summary file is not consumed as current facts.
- **Recorded:** `summaryStatus`, and up to 40 non-blank output lines of at most 300 characters as `summaryLines`
  among the facts the model sees. Truncation preserves complete Unicode characters.
- **Output file:** at most 1 MiB of standard output is kept as `<phase>.summary.txt` next to the report.
  Existing links and non-files are refused before launch. The bounded output replaces an ordinary file atomically;
  a command that cannot start leaves an earlier output unchanged. A started command replaces it with the output
  captured during this attempt, including a partial prefix on failure. The current report's `summaryStatus`
  determines whether that output is valid advice; failed output never contributes `summaryLines`.
- **Failure:** a command that exits non-zero, outlives `--summarizer-timeout-seconds` (default 60) or cannot start is
  recorded as `summaryStatus: failed: ...`, and the hook goes on. Output beyond 1 MiB is refused as
  `failed: output-limit`. The deadline covers stdout collection and process completion. The hook retires its
  child and observed descendants on completion, timeout or cancellation, with a bounded cleanup wait.
  Descendant observation is best effort; this is not a sandbox for a daemonizing or hostile command.
- **Cancellation:** an interrupted caller retains its interrupt flag, records `failed: interrupted`, skips model
  inference and returns hook status 130. An already interrupted caller does not launch the summarizer.

`m3.smart.failOnError` (default `true`) keeps the goal's contract: a hook that does not complete fails the goal. Set it
to `false` where `build-hook` is bound into a POM's lifecycle, so that advice never changes a build result. The report
still records the failure. Neither goal resolves project dependencies.

```xml
<plugin>
  <groupId>io.synexia</groupId>
  <artifactId>m3-smart-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <configuration><failOnError>false</failOnError></configuration>
  <executions>
    <execution><id>advice-pre-build</id><phase>validate</phase><goals><goal>build-hook</goal></goals>
      <configuration><phase>pre-build</phase></configuration></execution>
    <execution><id>advice-post-build</id><phase>verify</phase><goals><goal>build-hook</goal></goals>
      <configuration><phase>post-build</phase><buildOutcome>success</buildOutcome></configuration></execution>
  </executions>
</plugin>
```

In `<configuration>`, `<phase>` is the hook's own phase parameter, not the lifecycle phase. To give every project of
every build this binding without editing a POM, load the core extension `io.synexia:smart-maven-extension`
(`../extension`) and run builds with `-Dchromellm.advice=true`. See [the extension's README](../extension/README.md).

## Require canonical Java atomization and patternization receipts

The optional receipt gate consumes the checkpoint output of the canonical Java convergence DAG. It does not
implement another atomizer, and it does not execute source changes.

- **Options:** `--require-atom-pattern true --atom-pattern-manifest receipts/manifest.tsv`, with an explicit
  `--source-root`. The Maven properties are `m3.smart.requireAtomPattern` and `m3.smart.atomPatternManifest`.
- **Default:** the requirement is off.
- **Validation:** a manifest that is supplied is always validated, even when the requirement is off.
- **Gating Maven:** to make the gate a prerequisite, run a mandatory pre-build hook with `--backend none` and the
  requirement before Maven. The hook exits non-zero on rejected evidence.

The UTF-8 manifest starts with these two lines; the header columns are separated by tabs:

```text
# m3.atom-pattern.manifest.v1
source_path	input_sha256	output_sha256	checkpoint_path	checkpoint_sha256
```

Manifest rows:

- **Coverage:** every following row names one current `.java` file recursively below `source-root`, unchanged files
  included.
- **Paths:** source and checkpoint paths are project-relative POSIX paths, without `.` or `..` segments, repeated
  separators, backslashes or control characters.
- **Order:** rows are strictly sorted by source path.
- **Bindings:** each row binds the original source digest, the final source digest, the checkpoint path, and the
  exact checkpoint byte digest. All hashes are lowercase SHA-256.
- **Rejected:** missing, extra, duplicate or reordered rows.
- **Currency:** the final source digest must match the bytes on disk at every invocation, post-build included.

Each checkpoint TSV uses the exact `M3Java21ConvergenceDag` format:

```text
round	phase	source_path	before_sha256	after_sha256	compiler_root	contract_root	behavior_root	jit_root
```

Checkpoint rows:

- **Count and order:** exactly eight data rows. Rounds 1 and 2 each contain `INVENTORY`, `FILE_ATOMIZATION`,
  `FILE_PATTERNIZATION` and `FILE_DOCUMENTATION`, in that order.
- **Hash chain:** the source hashes chain continuously from the manifest's input digest to its output digest.
- **Stability:** every round-2 row, and every inventory row, must be unchanged.
- **Proof roots:** the four proof roots must be present as SHA-256 identities. The hashes bind the supplied
  attestations to the manifest. This consumer does **not authenticate** that the host actually ran compiler,
  contract, behavior or JIT verification.

The report and the model prompt carry the manifest, source-set and checkpoint-set identities, plus counts. They set
`atomPatternStatus: structurally-validated` and `semanticProofAuthenticated: false`.

Missing, stale, malformed, incomplete or misordered evidence writes `atomPatternStatus: rejected`, a bounded reason
code and `adviceStatus: blocked-evidence`. The hook then exits non-zero **before launching the summarizer or provider**.
After summary collection and again before publishing a successful advisory result, the hook reruns the same evidence
parser and compares the admitted manifest, source-set and checkpoint-set identities. Source membership changes,
changed bytes and replacement valid receipts refuse the current advisory. A refusal after summary collection skips
provider construction; a refusal after inference clears the candidate. The report retains the original receipt facts
for diagnosis with a rejected status. These boundary checks do not lock source files against other writers.

Limits:

- **Scanner:** up to 262,144 ordinary files and 262,144 directories.
- **Java files:** 16 MiB each.
- **Manifest:** 128 MiB.
- **Checkpoints:** 64 KiB each.
- **Paths:** 4,096 characters.
- **Refused:** symlinks and other non-ordinary source entries.

## Report contract and qualification

Schema `m3.smart.build-hook.v1` records:

- the phase and the supplied build outcome;
- the selected provider and the advice status;
- bounded POM, log and source facts.

Built-in fact extraction sends bounded counts and hashes without raw log lines or source contents. An explicitly
configured CPU summarizer receives the original POM and log; its bounded output is untrusted and is included in the
model prompt. A provider failure writes `adviceStatus: failed` and a non-zero hook status. Model prose cannot change
the observed `buildOutcome`.

`scripts/verify.sh` runs the core, adapter and synthetic subprocess tests. The provider scenarios use a protocol
fixture selected by the model file name (`mcp-<scenario>.gguf`). `scripts/verify-build-hook-workflow.sh` runs a
real failing Maven compilation between pre-build and post-build `BuildHookMain` calls, with the synthetic cli
provider. Neither claims trained-model inference.

## Optional canonical OpenRewrite preparation admission

A second, independent source-bound admission:

- **Hook options:** `--preparation-required true --preparation-report .reports/preparation.json`.
- **Maven properties:** `m3.smart.preparationRequired` and `m3.smart.preparationReport`.
- **Input:** a canonical private OpenRewrite runner emits `synexia.smart-build.preparation.v1` outside the selected
  source root. The hook does not run that runner, and does not accept a recipe from model output.
- **Refusal:** when a requested report is missing, incomplete, stale or refused, the hook exits 2 **before** any
  summarizer or model call. The original receipt and source binding are checked again after summary collection and
  after a successful model call. Even a replacement receipt that parses successfully must match the admitted identity.
  Drift records `preparationStatus: refused` and `adviceStatus: blocked-preparation` (or `blocked-evidence` when the
  independent checkpoint gate also fails), clears any candidate and retains the supplied Maven `buildOutcome`.
- **Explicit opt-in:** supplying a preparation report without requiring it is rejected.
- **Authority:** preparation facts are `PRODUCER_REPORTED_NOT_REEXECUTED`. They do not authenticate the OpenRewrite
  execution, and do not authorize source mutation.
