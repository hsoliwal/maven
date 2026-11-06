<!-- SPDX-License-Identifier: Apache-2.0 -->
# Maven additive exact source/history integration of all six currently open PRs

Date: 2026-11-03. **DRAFT / GIT SOURCE-CUSTODY ONLY / HARD RELEASE HOLD.** This contract is committed before any Git merge operation. It does not authorize a write to protected `master`, Maven public Java/SPI APIs, native JNI, or dependent M3JDK21 product trees.

## Current six-PR source graph and frozen heads

At inventory, GitHub reported exactly **six open** `hsoliwal/maven` pull requests: #22, #23, #24, #25, #26, and #28. The fork `master` ref was frozen at `758b456b567f758de5a2a91d1cafeb654c610b69`.

- [#22](https://github.com/hsoliwal/maven/pull/22) opt-in Java static/security quality gate `8a545ebd11a568392427098445c1ccda04b726aa`.
- [#23](https://github.com/hsoliwal/maven/pull/23) original six-file Maven build-tools Java/provider capacity guard, current head **`3930678bf68e6eb7a45b7c09654d4fc84912cdd2`**; first original source-changing commit `e604259afc3f24bb568ca04739a6e402102a1091`, then **one additional source-identical commit**, head tree `dc3786ee20a696f26044ae0d459ffcb079c6e21b` equal to its predecessor tree SHA.
- [#24](https://github.com/hsoliwal/maven/pull/24) Maven core shared plugin evidence SPI `f99436b087cf840c2a17e4cf348375e70a0e40c6`.
- [#25](https://github.com/hsoliwal/maven/pull/25) upstream master sync `edb74629f61c7f4dc883de82f0f982efcfa9a730`; original donor history/source retained, including deferred source variants. This is *not* a claim of runtime acceptance of upstream changes.
- [#26](https://github.com/hsoliwal/maven/pull/26) original all-open-PR additive source `9d39c02678b9e382ca86a9ca306ddcef52b76b6c`.
- [#28](https://github.com/hsoliwal/maven/pull/28), **receiving exact head `8f776dbded6b46dc0f5261e86bc93da4ae8b07ee`**, source-sealed all-first-five original PR lineage; its two-parent original transport retained original #26 with *identical full Git tree*.

## Exact missing history versus source semantics

Before the new merge, GitHub `compare(#23 current head, #28)` returned 30 ahead / **one behind**. GitHub comparisons for #22, #24, #25 and #26 heads against #28 returned **zero behind**. The missing one commit on current #23 is **source-identical** to the source already contained in #28. This is NOT inferred from a PR title:

- Original #23 changed-file inventory vs frozen master is **six paths**, four modified and two added.
- All six donor paths were fetched independently from current #23 and receiving #28 and compared **entire Git blob SHA-1 plus full source bytes**; **6/6 identical**:
  - `.github/workflows/m3-maven-buildtools.yml` Git blob `c980d8f924dca0e14efaf82faede558882fb8f40`
  - `impl/maven-impl/src/main/java/org/apache/maven/impl/DefaultM3BuildToolsService.java` Git blob `2908655040cc01b61cfa8f3a89d8b1a78ce259da`
  - `impl/maven-impl/src/test/java/org/apache/maven/impl/DefaultM3BuildToolsServiceTest.java` Git blob `c430e17bf7f54a6a1946cd0b97946eb1fd80db6c`
  - `m3/FORK_CONVERGENCE_20261031.md` Git blob `1569f8f4cc10b9159439d49331a3bb95b408784f`
  - `m3/NATIVE_BUILD_TOOLS.md` Git blob `979ce79b27c97c66c5ad1f6cb492f4b64b189fd4`
  - `m3/recipe-custody/maven-buildtools-budget-20261031.json` Git blob `37a79877086cccb29d9c4e30fd6cf4e94a7c28f4`.
- Git commit object `3930678...` has exactly one parent `e604259...`, and both have **identical entire Git trees** `dc3786ee20a696f26044ae0d459ffcb079c6e21b`. Thus only history ancestry remains missing, not an unmerged source operation or public API difference.

## Proposed exact additive no-source-change integration

1. Create dedicated private branch `m3/maven-all-six-pr-ancestry-20261103` at #28 exact current head. Publish this document **first**. No original PR/default branch modification.
2. Build one actual **two-parent Git merge commit** using first parent = this documentation-bearing receiving branch, second parent = exact **current #23 head** `3930678bf68e6eb7a45b7c09654d4fc84912cdd2`; **Git tree exactly equals the first parent**. This is a **verified no-source-delta merge**, because current #23 source blob/tree identity was independently proven, not an arbitrary ours/theirs semantic override.
3. Advance only the isolated branch by non-force expected-head lease. Read back Git commit parent ordering, tree SHA and remote ref. Require complete `compare(first-parent, two-parent merge)` to return **zero changed files**, zero behind; require `compare(all six frozen original heads, new merge)` each **zero behind**. Stop on any moving original PR SHA.
4. Publish post-execution `VERIFICATION.md` with actual merge/tree/ref checks, then a **draft** PR against Maven fork `master`. Keep #22/#23/#24/#25/#26/#28 open and reviewable.
5. Maven root/build-tools/DI/compiler/native plugin SPI compatibility, original core service constraints, real Java17/JDK21 JUnit, upstream-master/runtime behavior, Maven/OpenRewrite recipe `Recipe.run` fixed-point/refusal, M3JDK21 JNI/ABI, full reactor, third-party notices and performance must be source-built and tested before any production/default branch merge. GitHub mergeability is not compile or runtime acceptance.

## Google Drive and donor rights

The Google Drive November-3 additive convergence checkpoint is the audit record. Append only a **verified actual Git result** using revision control and readback; a Google Doc is continuity evidence, while GitHub source SHA/tree/modes are code authority. LeetCode/HackerRank/GeeksforGeeks supply problem categories and independent oracles, not unrestricted code-copy rights. No third-party challenge solution, Java product source or JNI binary is being changed by this history-only operation.

**This closes the exact six-currently-open-Maven-PR commit ancestry, not all `hsoliwal/*` repositories or a passing Maven product release.**
