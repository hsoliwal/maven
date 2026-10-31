<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Maven 4: shared capabilities, plugin-owned execution

Maven can absorb common plugin infrastructure without absorbing the MoJos themselves. This branch adds **native read-only plugin evidence custody** in the original Maven 4 API and implementation modules. The original project model, plugin realms, lifecycle, compiler, dependency resolver, JAR Archiver, Surefire/Failsafe, installer/deployer and build-cache extension remain the execution authorities.

## Current native APIs and proposed producer handoffs

| Plugin families | Original runtime owner | Shared Maven API | State |
| --- | --- | --- | --- |
| Compiler, Checkstyle, PMD, Error Prone | javac/compiler plugin and analyzer | `CompilerFactsService` original compiler fact image | Existing; consumers require schema/attribution proof |
| Dependency, Javadoc, SpotBugs | Maven Resolver and original JDK bytecode tool | `DependencyResolver`, `M3BuildToolsService` | Existing; original bytecode analysis remains provider-owned |
| Resources, Clean | Resources and Clean plugins | `M3PluginEvidenceService.INPUTS/RESOURCES/OUTPUTS` | API ready; actual receipt publisher NOT implemented |
| JAR/Assembly/Shade | Archiver/packaging plugin | `M3PluginEvidenceService.ARCHIVE/OUTPUTS` | API ready; archive semantics/producers NOT implemented |
| Surefire/Failsafe | Original test provider, process forks | `M3PluginEvidenceService.TESTS/OUTPUTS` | API ready; test discovery/producers NOT implemented |
| Install/Deploy | Original `ArtifactInstaller` and `ArtifactDeployer` | `M3PluginEvidenceService.PUBLICATION` is observation only | Existing publishing API; NO automatic effect authorization |
| Cache and incremental build | Maven Build Cache Extension, M3File/M3AST | Original cache admission + owned output manifest | No new cache, no goal skipping, no new graph |

This stage uses the installed original SessionData mechanism only for **immutable evidence references**. The new `M3PluginEvidenceService` requires exact G:A, version, goal/execution, compiler or plugin context SHA, positive generation, schema, and capability mask. The original plugin producer is responsible for creating the receipt and for classifying semantics. Maven validates bytes before publishing, and again on every read. The session can retain at most **512** sorted references and each image is limited to **16MiB**. Conflicting same-generation output, changed file bytes, symlinked files and missing capabilities are refused. Storage uses parallel arrays and binary search; it retains no source or class payload.

A fact receipt is **not** a proof that goals can be skipped. No inferred parity, Maven reactor skip/restore, remote artifact transfer, deployment, LLM recipe application, plugin ABI change or JNI export is added. Plugin implementation remains replaceable only after exact original contract and JUnit/JVM/JNI correctness evidence.

## Source handoff and qualification

Canonical recipe PR: [Synexia #14720](https://github.com/hsoliwal/com.synexia/pull/14720), existing `com.synexia.m3.ProjectDelta20261021` Maven/OpenRewrite owner, dataset `maven-core-plugin-evidence-20261031`. All three original Maven paths are **ABSENT** at base `master@758b456b567f758de5a2a91d1cafeb654c610b69`. The exact source SHA-256 and original reviewed Git blob identities are in [the receiver receipt](recipe-custody/maven-core-plugin-evidence-20261031.json).

Local Java21 host compiled original classes with `javac --release 17 -Xlint:all -Werror` against Maven API-shape stubs; a standalone Java runtime fixture passed **15 behavioral checks each in JIT and interpreted mode** (not original Maven JUnit). Real JUnit cases in `DefaultM3PluginEvidenceServiceTest` cover original Maven DI, byte drift, session isolation and the 512/513 boundary.

**HOLD until:** real original Maven 4 reactor/JUnit with Java 17/21, native Maven DI, canonical SDK Recipe.run and fixed point, original plugin publisher integrations, plugin ABI and complete build cache equivalence, multi-OS, M3JDK21/JNI and measured performance. This is an additive first API slice, not complete plugin migration.

Official upstream donors: [Maven core plugin index](https://maven.apache.org/plugins/index.html), [Maven Build Cache Extension](https://maven.apache.org/extensions/maven-build-cache-extension/), original Apache Maven API services. The external LeetCode/HackerRank/GeeksforGeeks catalogue contributes only algorithm/test categories; no editorial or contest source was copied.
