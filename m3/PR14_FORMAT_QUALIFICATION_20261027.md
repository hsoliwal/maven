<!---
 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to You under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

# Maven 4 M3 additive PR #14 — source-format qualification (2026-10-27)

This is an append-only audit of the opt-in M3 Maven fork's two-parent candidate. **It is not an admission receipt** and does not authorize merging or replacing the fork's default branch.

## Provenance

- Pull request: https://github.com/hsoliwal/maven/pull/14
- Original candidate head: `e40f37e4ce201bd0334d64c6fa8a0e04282a16cd`
- Independent preservation branch: `preserve/pr14-before-checkstyle-20261027-e40f37e4ce20`
- M3-specific source fixes were applied to the PR branch with four non-rebased commits, through `517879f1195bf25f0728791f27b9b1ccc5569bfb`.
- The separate one-shot formatter ran under `ops/maven-pr14-format-20261027` with exact-head checkout and no default-branch mutation. Its executed job passed on https://github.com/hsoliwal/maven/actions/runs/40444151617.
- Format postimage commit: `8d620a357219925169bf1d2c31022d7e7368589a`. Compared against its direct parent `517879f1195bf25f0728791f27b9b1ccc5569bfb`: one commit ahead, zero behind, exactly six permitted original M3 CLI Java files modified, no file deleted.

## Observed gates

1. Original Java CI run 40439825533 failed on **29 Checkstyle violations** in four M3 Java files.
2. After mechanical brace and one-statement-per-line repairs, Java CI run 40442694956 executed and recorded **zero Checkstyle violations**. The next blocker was Spotless, not a source compiler or JDK regression.
3. Maven 4 wrapper bootstrap, `spotless:apply`, original source-credit retention, `spotless:check`, six-file path allowlist, and non-forced push completed successfully on isolated operations workflow run 40444151617.
4. The PR's new postimage GitHub checks on `8d620a357219925169bf1d2c31022d7e7368589a` were initially marked `action_required` (unexecuted); this status must not be represented as source/test success.

## Admission holds

- Exact-head Java CI must execute after this postimage and show a passing Maven distribution build.
- Full Maven reactor and integration tests, Checkstyle/Spotless/RAT, M3 watcher/compiler-facts behavior, and the documented source/ABI compatibility gates must complete.
- Any newly discovered code-level failure requires a bounded source-owning repair and refreshed verification evidence. Do not disable tests or acceptance gates to get green.
- Keep PR #14 as **DRAFT / HOLD**, with both Git parents and prior preservation branches intact, until all required gates have actual executed proof.
