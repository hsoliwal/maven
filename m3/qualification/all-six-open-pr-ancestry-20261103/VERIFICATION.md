<!-- SPDX-License-Identifier: Apache-2.0 -->
# Actual six-open-PR Maven source-history additive Git merger — verification receipt

2026-11-03. **Git history merged on isolated private integration branch, product release HOLD.** This receipt follows the real Git object operations; no assumptions of Maven/JNI compatibility or all-repository PR completion are made.

## Original source and PR identity (complete six-head snapshot)

- Protected Maven fork `master@758b456b567f758de5a2a91d1cafeb654c610b69` remains unchanged by this operation.
- Original #22 `8a545ebd11a568392427098445c1ccda04b726aa`, #23 **`3930678bf68e6eb7a45b7c09654d4fc84912cdd2`**, #24 `f99436b087cf840c2a17e4cf348375e70a0e40c6`, #25 `edb74629f61c7f4dc883de82f0f982efcfa9a730`, #26 `9d39c02678b9e382ca86a9ca306ddcef52b76b6c`, and initial receiving #28 `8f776dbded6b46dc0f5261e86bc93da4ae8b07ee` were all current open PRs at the frozen inventory.
- Original receiver #28 retained #22/#24/#25/#26 head ancestry already; one historical head, current #23, was missing precisely one source-identical commit, compared as `compare(#23,#28): ahead30/behind1`. Original first source-changing #23 commit `e604259afc3f24bb568ca04739a6e402102a1091` was already an ancestor of #28.
- The current #23 head is a direct child of `e604259...`, and their **entire Git tree** is identical `dc3786ee20a696f26044ae0d459ffcb079c6e21b`. The complete #23 six-file delta vs original fork master (four modified, two added) is **byte-identical** in original #23 and receiving #28, independently fetched by exact Git blob plus full source bytes:
  - `.github/workflows/m3-maven-buildtools.yml` `c980d8f924dca0e14efaf82faede558882fb8f40`
  - `impl/maven-impl/src/main/java/org/apache/maven/impl/DefaultM3BuildToolsService.java` `2908655040cc01b61cfa8f3a89d8b1a78ce259da`
  - `impl/maven-impl/src/test/java/org/apache/maven/impl/DefaultM3BuildToolsServiceTest.java` `c430e17bf7f54a6a1946cd0b97946eb1fd80db6c`
  - `m3/FORK_CONVERGENCE_20261031.md` `1569f8f4cc10b9159439d49331a3bb95b408784f`
  - `m3/NATIVE_BUILD_TOOLS.md` `979ce79b27c97c66c5ad1f6cb492f4b64b189fd4`
  - `m3/recipe-custody/maven-buildtools-budget-20261031.json` `37a79877086cccb29d9c4e30fd6cf4e94a7c28f4`.

## Actual executed merge and source-readback

1. Created a new isolated branch `m3/maven-all-six-pr-ancestry-20261103` at original #28 head. Published source-history [README.md](README.md) before modifying any branch history, commit `1aea3f277fd088911aca13a212307823fd38e0aa`.
2. Created a real Git **two-parent** merge commit `14b5bacb57db5a1c765ab659e8ba1ed7a6c52a05`, ordered parents [documentation-first receiver `1aea3f277fd088911aca13a212307823fd38e0aa`, exact current #23 head `3930678bf68e6eb7a45b7c09654d4fc84912cdd2`]. Git tree `e1919fd13aaaa6328fb045547f212cebcb4f7783` equals first parent **exactly**. Git commit was reread; parents and tree matched required source identities. No source file or mode changed, no rebase/squash/force/delete.
3. Advanced **only** the new isolated integration branch using a non-force, exact-previous-head lease and verified remote SHA readback `14b5bacb57db5a1c765ab659e8ba1ed7a6c52a05`.
4. GitHub's actual `compare(documentation parent, two-parent merge)` returned **two commits ahead, zero behind, zero changed files**. This establishes a no-source-delta Git history merger, not an untested new implementation.
5. Re-ran GitHub `compare(original PR head, two-parent merge)` for **all six** original exact SHA heads, each returned **`behind_by=0`**: #22 +33/0, #23 +32/0, #24 +33/0, #25 +93/0, #26 +4/0, #28 +3/0 (ahead/behind). All six original commit histories are retained.
6. This verification file is a subsequent **new documentation blob only**. The final review PR must preserve both exact parents and the existing #28 product source. The #25 original upstream synchronization held its original variant source; no provider/JNI/Java signature change or runtime execution is claimed by this history merge.

## Default-branch, JDK/runtime and complete-fleet release HOLD

The original first-party Maven 4 Java session build-budget/provider contract, read-only plugin-evidence APIs, opt-in quality/security profiles, Maven native plugin lifecycles, original public Maven SPI, Java 17/21/JNI dependencies, licenses and full historical Maven reactor must remain compatible. **NOT RUN on this new head:** real Maven root compile, JUnit, Maven/OpenRewrite `Recipe.run` source-seal fixed-point, exception and provider capacity tests, source-level API/binary/observable compatibility, full native JNI integration, cross-platform release verification, hosted CI and performance benchmarks. A `mergeable` state or zero-diff Git comparison does not establish product correctness.

The Google Drive November 3 convergence ledger is the companion human audit record; GitHub exact original PR tree and ref objects remain source authority. Append a verified source receipt to Drive by revision-controlled write/readback only after the review PR exists. This bounded Maven six-PR source-history result is **not** the merger of every `hsoliwal/*` PR.
