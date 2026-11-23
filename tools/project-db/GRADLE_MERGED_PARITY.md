<!-- SPDX-License-Identifier: Apache-2.0 -->
# Combined merged Maven → Gradle acceptance (23 November 2026)

## What the separate PR-head acceptance did — and did not — prove

The original Maven-side [PR #272](https://github.com/hsoliwal/maven/pull/272) reached [successful run 45018172932](https://github.com/hsoliwal/maven/actions/runs/45018172932), attempt 3. It built original Maven's sealed polyglot and project database engines, verified Derby transactions, and executed **36/36 named tests** across Java 17 and 21. Each of the three **separate** Gradle PR-head source trees (#71/#72/#73) was selected independently for its TestKit slice. Those three PRs and Maven #272 then merged.

An independent concern remains: *do the three features coexist on the actual merged Gradle `master`?* A clean text merge cannot prove that. This new Maven-side CI-only source check uses a single merged Gradle checkout, runs all seven named test suites together in one Gradle TestKit invocation, and requires the original Maven's secure nine-case project-DB parser in addition to exact LLVM/Derby non-skipped JUnit receipts.

## Immutable heads and original source ownership

- **Maven producer:** `hsoliwal/maven@2cb32473cced0c1ef3570fc481a2dfeb11e92553` (merged through Maven #267/#270/#272 and the standalone LLVM verifier repair #273).
- **Gradle consumer:** `hsoliwal/gradle@a8ab91be59f414e39245762445f1057a8677fba2` (merged #71–#73 and prior #70).
- These are source-pinned reviewed Git commits; neither follows a moving branch. All original Gradle toolchain/compiler/build-cache semantics, Maven `TaskRunner`, native M3IndexDB, Derby storage and LLVM Clang command parsing remain in their original owners.
- The Maven producer verifies the original `tools/m3-polyglot-maven-plugin` including live Derby 10.16.1.1 JDBC/transaction/restart/recovery, and `tools/project-db`; it also runs the original Java 17 JUnit parser negative/mode verifier. No Gradle clone is used to build Maven.
- The Maven source SHA, Gradle source SHA, JDK version, original Maven JARs, original Java17 JUnit parser/wrapper, test-only Derby closure and LLVM fixtures are carried in `M3-MERGED-CROSSFORK-V1` with a digest for every file. A producer failure prevents the consumer job. An artifact with wrong source, wrong JDK or changed bytes is rejected before Gradle starts.

## Native Gradle combined consumer

The Java 17/21 consumer jobs each sparsely check out the **same** merged Gradle source, reuse the corresponding original Maven artifacts, and run a single `./gradlew -p m3/quality-bridge test --rerun-tasks` with seven exact TestKit classes:

| Domain | Class groups | Required cases/JDK |
| --- | --- | ---: |
| Clang compilation database | `M3LlvmCompileDbTaskTest`; `M3LlvmCompileDbRealEngineTest` | 3 + 2 = **5** |
| Original Derby JDBC | `M3DerbyRealEngineTest` | **4** |
| Original project DB | `M3ProjectDbRealEngineTest`; `M3ProjectDbGradleNativeRealEngineTest`; `M3ProjectDbTaskTest`; `M3ProjectDbPrunedSymlinkRealEngineTest` | 1 + 1 + 6 + 1 = **9** |
| **Total** | **7 classes** | **18 cases/JDK (36 combined)** |

After Gradle finishes, the original Maven secure Java17 JUnit parser requires the `current-nine` cases with zero skips/failures/errors. A bounded no-DTD/foreign-entity Python receipt check admits only the five named LLVM and four named Derby test cases, again zero skips/errors/failures. The original Maven and Gradle hashes are reverified after execution; any tracked Gradle source change fails the job.

The original previous PR-head source-pinned workflow is **preserved**; this one is independent and has no authorization to replace its historical evidence. The full two-JDK matrix, source-built LLVM-fork binary provenance, Java21 native M3JDK database, Apple/Windows native toolchains, root/branch all-history source-loss audit and real OpenRewrite SDK idempotence/fixed-point replay remain distinct gates. A passing combined TestKit cannot certify arbitrary Gradle task-cache hits or a universal Maven/Gradle compiler backend.

## Qualification and promotion

This file documents CI **source**, not success until the original GitHub Actions job runs. Record job IDs, exact-head JUnit XML, SHA-256 artifact receipt, any transient Maven Central failures, and negative test admissions. Do not suppress failed steps, fabricate contributor signatures, rebase shared history, or merge without independent reviewer/rights approval.

No new compiler/JDBC/SQLite implementation, donor source, recipe plugin, Maven POM or Gradle public API is introduced. This CI-only PR reuses existing OpenRewrite opt-in consumer recipe paths but does **not** claim actual SDK replay; the latter must be run independently using the canonical private Synexia recipe catalogue.
