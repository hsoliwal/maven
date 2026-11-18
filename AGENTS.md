<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Agent Guide for maven

## Priority 0 — universal additive superset, full-history merge and last-checked rule (2026-11-18)

Preserve the **backward-compatible optimized superset** of all applicable accepted upstream/fork capabilities, source/binary/public APIs, behavior, fixes, platform/runtime contracts and previous qualified optimizations. Compare candidate algorithms and branches on differential correctness, worst-case complexity, CPU/heap/native cost, scalability and maintainability; choose the best **proven** implementation, allowing compatible additive API extensions but no silent removal. Before merge, inspect the earliest reachable ancestors across every relevant branch/tag, old merged/closed/reverted PR, merge base and both merge parents, to identify bad commits, lost code, removed APIs and lost optimizations; never rebase `develop`. Persist last-checked date, precise ref/head SHA, scope, oldest ancestor checked, last verified SHA, branch/PR coverage, loss findings and PENDING/PARTIAL/VERIFIED/HOLD in the canonical Synexia checkpoint register. An observed HEAD is **not** an exhaustive history audit. First-party separable new work prefers Apache-2.0; preserve original upstream and derivative license/NOTICE obligations. LLM atomization/patternization does not by itself change copyright or code licensing. Source-changing work follows canonical Synexia Maven/OpenRewrite recipe/historical-proof gates; public product forks remain clean postimage-only receivers. Policy and initial explicitly **PENDING** repository inventory: [Synexia PR #18764](https://github.com/hsoliwal/com.synexia/pull/18764).


This file is read by automated agents (security scanners, code
analyzers, AI assistants) operating on this repository.

## Security

Security model: [SECURITY.md](./SECURITY.md)

Agents that scan this repository should consult `SECURITY.md` and the
threat model it links before reporting issues.


## Superset merge and history-audit rule (2026-11-18)

Read [.github/MERGE_RULE.md](.github/MERGE_RULE.md) before changing an existing implementation or integrating any branch or PR.
Preserve the supported additive API/behavior superset and select implementations by correctness, predictability, measured performance, then elegance.
Audit all reachable history back to every root, all branches/tags and all PR states for lost code or regressions; preserve append-only history.
Record actual UTC coverage and remaining work in [.github/HISTORY_AUDIT.tsv](.github/HISTORY_AUDIT.tsv); policy installation is not a completed source-history audit.
Prefer verified Apache-2.0 sources and independent implementations while preserving all existing third-party rights; LLM rewriting does not remove license obligations.
Detailed private recipe/proof records remain in canonical Synexia; the compact local policy and checkpoint make the rule discoverable here.
<!-- MERGE-SUPERSET-HISTORY-20261118-2 -->

## MOP SDK cross-repository agent read gate (2026-11-18)
<!-- MOP-AGENT-2026-11-18-1 -->
Before any source analysis, refactor, PR, merge, licensing decision, or history checkpoint, also read [AGENT.md](AGENT.md). It governs bidirectional atomization/patternization, documented contract-sealed atoms, additive best-of-best API supersets, complete available-history loss checks, dated last-verified proof, Apache-2.0 eligibility and upstream rights. Existing stronger project-specific and locked-canon constraints remain in force. Installation of this pointer does not establish that a source or historical audit has run.

## Owner directive: best-of-best additive convergence and full-history checkpoint (2026-11-18)

Inspect all accessible Git history to its roots, all branches/tags and paginated open/closed/merged/rejected PRs with their head/merge refs before replacing code. Compare actual pre/post source and public API/behavior/test inventories; a merged PR/ancestry alone is not proof of surviving functionality. Identify and recover lost verified optimizations, API extensions and tests via the canonical Synexia Maven/OpenRewrite recipe, not destructive rebase.

Converge the strongest compatible correct algorithms into an additive superset; retain original type identity, source/binary/reflection/serialization contracts, native/resource guarantees, regressions and build/test gates. Reusable recipes and evidence belong to `hsoliwal/com.synexia`; this repository receives only appropriate product code and tests.

Prefer Apache-2.0 for separately original and legally eligible new work; retain all upstream GPL/EPL/Apache/LLVM exception or other per-file licensing and NOTICE as applicable. A new, independently expressed implementation of an abstract algorithm may be used when permitted; LLM paraphrase cannot erase another author's code copyright or patent rights.

Write exact last-check date, repo/default SHA, root/ref/PR coverage, delivered-source loss findings and test status to `hsoliwal/com.synexia/docs/M3-SCALE/invariants/SYNEXIA-HISTORY-REVIEW-LAST-CHECKED.tsv`. Until complete, mark PARTIAL; do not claim full-history audit from a sampled head.

## MOP SDK — source-bound atoms and known/unknown algorithm registry (2026-11-18)

Read the [canonical Synexia MOP SDK invariant](https://github.com/hsoliwal/com.synexia/blob/develop/docs/M3-SCALE/invariants/SYNEXIA-MOP-SDK-BIDIRECTIONAL-LATTICE-1.md) and existing universal API atom/pattern policy before source changes. Decompose from repository -> project/module -> package -> file -> declarations -> meaningful behavioral atoms, sealing public API/ABI, state, effects, order, concurrency, native and resource contracts. Match `KNOWN_ALGORITHM`/`KNOWN_PATTERN` only to versioned, role- and applicability-qualified catalogue entries with recorded evidence. Otherwise preserve the original and create source-bound machine-readable `UNKNOWN_ALGORITHM`, `UNKNOWN_PATTERN` or `UNPROVEN_MATCH` residue with atom/owner identity, dependency/effect context, provenance, and verification status; link valid documentation or existing supported annotations, never invent noncompiling markers. Recompose and verify only the affected dependency closure upward; independent file preparation may be parallel, canonical mutation and proof remain serial. Preserve the strongest demonstrated **compatible additive API superset** after full available ancestry/branch/PR audit, and preserve inherited licenses and notices. Code/proof/recipe owners stay in Synexia, receiving products accept only source-sealed qualified postimages. This text does **not** certify a full-history scan, OpenRewrite run, compilation, tests or runtime.
