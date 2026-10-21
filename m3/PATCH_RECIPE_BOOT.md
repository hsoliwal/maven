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

The added `M3GitHubPatchBootstrapSelfTest` verifies 19 fail-closed parser,
flag, path and pin cases with strict Java 17 compilation; the JUnit wrapper
runs it in Maven's original CLI test lane. This is a standalone source
oracle, not evidence that a full Maven distribution or live PR fetch has
already passed. Verify the actual Maven 4 reactor and Java 21/JNI receiver
against the pinned source tree before production promotion.

The Maven hook is source-compatible with the fork's existing Java 17 CLI
baseline and leaves Maven's plugin classloaders, reactor dependencies,
compiler tasks and ordinary default lifecycle unchanged.
