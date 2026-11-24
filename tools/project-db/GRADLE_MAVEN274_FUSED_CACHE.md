<!-- SPDX-License-Identifier: Apache-2.0 -->
# Original Maven #274 fused portable/cache state: Gradle consumer evidence

## Exact provenance

- **Source owner:** [merged Maven #274](https://github.com/hsoliwal/maven/pull/274), exact merge commit `53d7bd782077968e596a54cb7948ea22810734c3`. The original `InputStamps.pairedStamps` and `TaskRunner` share one **bounded input observation** across two distinct existing cache keys per observation phase, not a new cache format.
- **Gradle receiver:** [original Gradle #75](https://github.com/hsoliwal/gradle/pull/75), extended by the deleted-output regression at exact immutable candidate `c37aebffa4a7040f2bb5d1f783ce18237354fd90`. Gradle's `M3PolyglotManifestTask` was already `@UntrackedTask` and exposed opt-in Maven `incremental`, `embedded` module state and an explicitly external Maven portable output cache.
- **No new engine:** no DB adapter, generic source hash, launcher, native compiler, Maven lifecycle, JNI, Gradle cache key or task dependency is authored in this batch. Existing `EnableM3PolyglotStateBridge` OpenRewrite recipe remains its selected opt-in consumer pathway.

## Java 17/21 cross-fork acceptance

Maven-hosted `.github/workflows/m3-gradle-maven274-fused-cache.yml` checks out only exact merged Maven #274 source and runs its original `tools/m3-polyglot-maven-plugin/pom.xml verify`, **requiring** the original `BuildCacheStateContractTestMain` success receipt. It stages the resulting original Maven engine JAR, binds its actual bytes with SHA-256 and `M3-FUSED-MAVEN274-V1` source/JDK/Gradle Git receipt, then checks out the exact Gradle deleted-output regression to run three named real-engine TestKit cases. Git SHA and artifact byte seals are verified before and after execution.

Positive contract: two independent Java17 fixture modules share only a source-owned Maven portable output cache. Original tool execution stores declared output; a second checkout restores that output with zero calls to its Java tool and commits an independently verified module receipt. Local warm reuse, changed/deleted output restoration, identical-length/mtime-restored modified input that must reexecute, missing declared input refusal, wrong source engine SHA refusal, project-local cache refusal and symlink-ancestor refusal are all part of the same original Maven mechanism.

The hosted gate rejects a missing, skipped, failed, foreign or duplicated TestKit JUnit testcase; requires exactly three direct named cases on **each** JDK; verifies original artifact JAR hashes again after the consumer; uploads the real original Maven log and actual Gradle XML/HTML result documents on both success and failure.

## Local deleted-output qualification (2026-11-24)

The pinned Gradle test now deletes a previously restored `result.txt` while retaining its local receipt. It requires `FROM-CACHE verify`, exact restored `AAAA` bytes and no `calls.txt`, alongside every original assertion. The original Gradle 9.9.1 wrapper executed all three named TestKit cases with zero failures, errors or skips on both full Temurin 17 and 21 JDKs against the same source-built original Maven #274 JAR.

The existing canonical ProjectDelta recipe emitted this exact test after its five original SDK tests passed on the original POM default OpenRewrite 8.17.1, serially and with two JUnit workers, and separately on explicit 8.90.4. The original result receiver completed check/apply/check with change counts 1/1/0. These local receipts qualify the exact source and runtime path; fresh hosted reports for this new pinned commit remain required.

## Distinct pending releases

A test source and its workflow configuration are **not** execution evidence. Do not mark this batch qualified without the exact new Java17/21 hosted JUnit reports and original Maven build receipts. The original Maven #275 M3File watcher/M3JDK module integration and [merged Maven #278](https://github.com/hsoliwal/maven/pull/278) Checkstyle correction are independently owned by Maven Core and require separate original JDK/runtime certification. This pin is deliberately **#274**, not a claim that a later Maven master or original M3JDK21 runtime was tested.

Also pending: hosted reports for this exact candidate, native macOS/Windows consumer proofs, license/DCO and reviewer sign-off, and full Git all-ref lost-optimization audit. The local canonical SDK replay and fixed-point gates above do not complete those separate obligations. No user-defined Gradle task may be skipped because these tests pass. Keep source-comparison PRs in draft until those gates are reviewed.

Original Maven's `InputStamps.engineSources()` binds the **absolute code-source JAR path** as part of its engine transcript. The Gradle fixture therefore stages one original Maven engine outside both project checkouts and supplies that same File to both runs; copying an identical JAR into each checkout would correctly produce different keys and is not a valid portable-cache proof.

The same original Maven input stamp must cover the Java fixture's executable bytecode: `MarkerTool.class` is explicitly declared beside `source.txt` in the manifest. A changed compiled fixture class is therefore an input mutation, not an unaudited portable cache reuse. Both checkouts use the same externally pinned Maven JAR code-source path.\n

## Included-build identity hosted companion

The same registered workflow now contains an independent, bounded JDK 17/21 job
for [Gradle #78](https://github.com/hsoliwal/gradle/pull/78), exact source
507d1830b61bb6afaaf80268f0f6e815d7c9ee01 and quality-bridge module tree
36a98dcba8578d34a418739afcbae53ab5de3e0f. It executes the existing exporter,
reviewer and plugin TestKit owners: six, two and one methods respectively.
These tests use native Gradle InputChanges and configuration-cache behavior;
they do not consume or qualify another Maven engine.

The original fused Maven #274 / Gradle #77 job remains byte-for-byte unchanged,
including its producer pin, deleted-output regression, three-method validator,
JAR retention and all artifact paths. #77 and #78 are sibling commits of the
same base. Replacing the #77 pin with #78 would lose the deleted-output test
extension, so their source identities and results remain separate. Passing both
jobs would not qualify a combined Gradle tree or merge either draft.

The identity job verifies its exact Git commit and complete module tree, hashes
every tracked source and wrapper/build input against its Git blob, and rejects
source drift, links and additional Java inputs. It records the actual JDK
properties and wrapper version, runs the three exact existing test classes with
task rerun enabled, and requires all nine original method identities with zero
skips, failures or errors. Missing, duplicated, foreign or entity-bearing XML
receipts refuse admission. Source bytes are checked again after execution.

The optional --identity mode of the existing shell guard checks this companion
job's fixed source/test/admission bindings before checkout. Its no-argument
fused-cache behavior and original refusals remain unchanged. Runtime XML
validation remains a separate gate after execution.

Actual logs, source/toolchain receipts, XML/HTML reports and the compiled plugin
classes/resources/TestKit classpath metadata are retained even when a preceding
step fails. The identity job has two matrix entries, at most two parallel jobs,
a 30-minute timeout and 14-day artifact retention. This edit adds no registered
workflow, trigger, secret context, writable permission or new action provider.

The existing nine-method local JDK 17/21 qualification belongs to canonical
[Synexia #19144](https://github.com/hsoliwal/com.synexia/pull/19144). Replaying
those retained XML files verifies receipt admission; it is not a new TestKit
execution. Fresh hosted results for this companion remain required. Global
history, provenance, platform and release admission remain separate obligations.
