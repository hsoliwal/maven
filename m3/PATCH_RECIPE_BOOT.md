<!--
 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements. See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to You under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License. You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->
# M3JDK21 / Synexia GitHub PR patch bootstrap — Maven 4 CLI

The fork's existing Maven 4 `MavenCling` entrypoints invoke
`M3GitHubPatchBootstrap.beforeMaven(args)` **before** ordinary Maven project
model loading. This is built into the Maven CLI, not a Maven plugin added to
each reactor module. All POMs using a distribution built from this fork get
this opt-in capability; **stock Maven installations are unchanged**.

The authoritative reusable recipe and HunkResolver belong to
[hsoliwal/com.synexia PR #12692](https://github.com/hsoliwal/com.synexia/pull/12692),
the source-sealed continuation of the original
`m3-pr-stack-preservation` Maven/OpenRewrite DAG. The native Maven CLI is only
a constrained PR transport and Git check/apply entrypoint. It does not
implement a competing AST/compiler, semantic conflict resolver, or SDK layer.

## Configure in the target repository

Copy `.m3/patch-boot.example.properties` from this fork to a target project
as `.m3/patch-boot.properties` and set all pinned fields. The manifest must
be inside that target Git worktree (or an ancestor of the process directory).
Do not commit secrets; the format has no authentication token field.

Properties:

| Key | Required | Meaning |
| --- | --- | --- |
| `repository` | yes | Exact `owner/repo` of GitHub origin and PR |
| `pr` | yes | Positive numeric PR number |
| `base` | yes | Exact 40-hex local Git HEAD preimage |
| `head` | yes | Exact 40-hex live PR head to check |
| `patchSha256` | yes | SHA-256 of the **exact** GitHub .patch bytes |
| `autoCheck` | optional | `true` asks native Maven to inspect on each invocation; defaults false |

Only a Maven distribution built from this fork can discover the manifest.
Using the working example without replacing its sentinel fields is rejected.
Do not enable `autoCheck=true` until it has a valid, reviewed manifest.

Usage:

```sh
# Default: no network and no patch check.
mvn verify

# Explicitly inspect the pinned GitHub .patch, validate exact source SHA,
# and run git apply --check --index. No source files are written.
mvn -Dm3.patch.check=true verify

# Explicitly opt into applying a checked **clean** patch to the local
# index/worktree. No remote push, PR merge, or branch ref mutation occurs.
mvn -Dm3.patch.apply=true verify
```

## Mechanical contract and safety

The CLI verifies repository root and allowed GitHub origin, current Git HEAD
against `base`, live `refs/pull/<pr>/head` against `head`, HTTPS GitHub
patch bytes against pinned SHA-256, mbox final commit head, safe unique paths,
and supported ordinary text patch modes. It rejects binary, rename, deletion,
symlinks, unsafe paths, opaque/differing path headings and failed clean Git
application. Check/apply operate against Git's **index** and exact source
context, not a line-number-only heuristic. Before apply, the CLI also
requires a clean tracked worktree and rechecks the PR's current head.
The patch transport has bounded byte limits and timeouts.

If a patch overlaps with previous hunk changes, `git apply --check --index`
refuses without changing the tree. That conflict remains input for the
canonical Synexia `GitHubPatchHunkChronology`, `ExactFileHunkReplay`,
`M3HunkResolver`, `M3RecipeCompiler`, AST/ABI/JNI contract tests, and
`MergeCodeLossGuard`. The bootstrap must never silently choose entire
`ours` or `theirs` source bodies. It does not invoke the Synexia SDK or
claim to perform semantic resolution itself.

This initial transport is intended for **public GitHub PRs**. Private PRs
requiring bearer-token authentication, quoted/renamed/binary patches, and
larger patch sets require a separately reviewed authenticated adapter.
No credentials are embedded in a POM, build log, manifest or HTTPS URI.

## Test and review

The added `M3GitHubPatchBootstrapSelfTest` verifies 21 fail-closed parser,
flag, pin and actual two-commit Git mbox check/apply cases with strict Java 17 compilation; the JUnit wrapper
runs it in Maven's original CLI test lane. This is a standalone source
oracle, not evidence that a full Maven distribution or live PR fetch has
already passed. Verify the actual Maven 4 reactor and Java 21/JNI receiver
against the pinned source tree before production promotion.

The Maven hook is source-compatible with the fork's existing Java 17 CLI
baseline and leaves Maven's plugin classloaders, reactor dependencies,
compiler tasks and ordinary default lifecycle unchanged.

## Optional Chrome AI conflict review — pinned, user-activated

When `git apply --check --index` fails after an otherwise complete SHA-256-pinned GitHub PR intake, this Maven fork can create a local *unverified suggestion request*, without applying the patch. The original Git error still aborts the explicit patch check; ordinary Maven builds remain unchanged.

```sh
# Inspect the pinned PR and, only on Git hunk conflict, write a bounded
# local review packet; no patch is applied, no Chrome process is launched.
mvn -Dm3.patch.check=true -Dm3.chrome.ai.review=true verify
```

The new `M3ChromeAiReviewPacket` refuses mismatched source head/digest, non-UTF-8, NUL, symlinked `.m3` custody and patches exceeding 20 KiB. It creates one immutable `.m3/patch-ai-reviews/pr-<number>-<sha12>.json` with `M3-CHROME-HUNK-REVIEW/1`, original GitHub base/head/patch SHA-256, the exact bounded patch bytes and `mutationAuthorized=false`. An existing receipt is never overwritten. The source-bearing review directory receives a local `*` `.gitignore` to prevent accidental `git add .` of the JSON, and any replaced/symlinked ignore policy is refused. It writes *only* after the user explicitly enables both patch checking and Chrome review, and **never** sends data to an AI server or over an arbitrary network endpoint.

Open the existing [Synexia Chrome MV3 side panel](https://github.com/hsoliwal/com.synexia/pull/12721) and choose the local packet JSON. Its browser-native `LanguageModel` (on-device Gemini Nano Prompt API) is invoked only after clicking Analyze and after checking availability. A SHA-256 mismatch, edited source text, unsupported API or unavailable model results in a HOLD; no source is changed. The suggested repair is exported as an **AI_UNVERIFIED** JSON receipt, then evaluated by the canonical source-sealed OpenRewrite/Maven recipe compiler, `M3HunkResolver`, Java/JNI/ABI tests and active-tree code-loss checks. Never apply a model-generated edit directly.

Chrome's on-device Prompt API requires eligible desktop Chrome and a downloaded compatible model. No proprietary Chrome/Gemini model weights, Chrome2api binaries, user tokens, or external AI credentials are included in this Maven fork. [Chrome's official Prompt API reference](https://developer.chrome.com/docs/ai/prompt-api) describes availability and user activation. The user may instead paste a held hunk manually, which is marked unsealed.

**Verification status for this increment:** `javac --release 17 -Xlint:all -Werror` passed and the existing 21-check Git bootstrap plus 16 new packet checks passed in isolated local execution. Maven 4 full reactor/JUnit, live GitHub patch fetch, installed Chrome extension, model inference, product M3JDK21 HotSpot/JNI/jtreg and cross-platform runs are NOT_RUN. This is draft/source-tested, not automatic fleet merge authority.
