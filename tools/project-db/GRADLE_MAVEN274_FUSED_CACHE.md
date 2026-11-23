<!-- SPDX-License-Identifier: Apache-2.0 -->
# Original Maven #274 fused portable/cache state: Gradle consumer evidence

## Exact provenance

- **Source owner:** [merged Maven #274](https://github.com/hsoliwal/maven/pull/274), exact merge commit `53d7bd782077968e596a54cb7948ea22810734c3`. The original `InputStamps.pairedStamps` and `TaskRunner` share one **bounded input observation** across two distinct existing cache keys per observation phase, not a new cache format.
- **Gradle receiver:** [draft Gradle #75](https://github.com/hsoliwal/gradle/pull/75), exact immutable candidate `8a5daf6fd7ea87230dada80f50c55264e002e4e5`. Gradle's `M3PolyglotManifestTask` was already `@UntrackedTask` and exposed opt-in Maven `incremental`, `embedded` module state and an explicitly external Maven portable output cache.
- **No new engine:** no DB adapter, generic source hash, launcher, native compiler, Maven lifecycle, JNI, Gradle cache key or task dependency is authored in this batch. Existing `EnableM3PolyglotStateBridge` OpenRewrite recipe remains its selected opt-in consumer pathway.

## Java 17/21 cross-fork acceptance

Maven-hosted `.github/workflows/m3-gradle-maven274-fused-cache.yml` checks out only exact merged Maven #274 source and runs its original `tools/m3-polyglot-maven-plugin/pom.xml verify`, **requiring** the original `BuildCacheStateContractTestMain` success receipt. It stages the resulting original Maven engine JAR, binds its actual bytes with SHA-256 and `M3-FUSED-MAVEN274-V1` source/JDK/Gradle Git receipt, then sparse-checks out exact Gradle #75 to run three named real-engine TestKit cases. Git SHA and artifact byte seals are verified before and after execution.

Positive contract: two independent Java17 fixture modules share only a source-owned Maven portable output cache. Original tool execution stores declared output; a second checkout restores that output with zero calls to its Java tool and commits an independently verified module receipt. Local warm reuse, changed/deleted output restoration, identical-length/mtime-restored modified input that must reexecute, missing declared input refusal, wrong source engine SHA refusal, project-local cache refusal and symlink-ancestor refusal are all part of the same original Maven mechanism.

The hosted gate rejects a missing, skipped, failed, foreign or duplicated TestKit JUnit testcase; requires exactly three direct named cases on **each** JDK; verifies original artifact JAR hashes again after the consumer; uploads the real original Maven log and actual Gradle XML/HTML result documents on both success and failure.

## Distinct pending releases

A test source and its workflow configuration are **not** execution evidence. Do not mark this batch qualified without the exact new Java17/21 hosted JUnit reports and original Maven build receipts. The original Maven #275 M3File watcher/M3JDK module integration and [pending Maven #278](https://github.com/hsoliwal/maven/pull/278) Checkstyle correction are independently owned by Maven Core and require separate original JDK/runtime certification. This pin is deliberately **#274**, not a claim that a later Maven master or original M3JDK21 runtime was tested.

Also pending: independent OpenRewrite SDK recipe replay fixed point, native Linux/macOS/Windows proofs beyond this Ubuntu runner, license/DCO and reviewer sign-off, and full Git all-ref lost-optimization audit. No user-defined Gradle task may be skipped because these tests pass. Keep source-comparison PRs in draft until those gates are reviewed.
