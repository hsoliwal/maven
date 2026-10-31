<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# hsoliwal/maven — complete fork enhancement work board, 2026-10-31

**This is a bounded, factual work board, not a claim of completed whole-fork qualification.** Upstream source product remains Apache Maven, Maven 4.1.0-SNAPSHOT; original root `pom.xml` sets Java **17** release compatibility. The M3JDK21 extensions are opt-in and may require the installed M3 SDK. Preserve Apache source license and the original Maven lifecycle, model builder, resolver, reactor, toolchains and plugin execution.

| Area | Original owner / entry | Current verified capability | Remaining admission gate |
| --- | --- | --- | --- |
| Maven API and model | `api/*`, `api/maven-api-core` | Existing Maven 4 public contracts and M3 session service shapes | Source/binary API parity across upstream 4.1 source and Java 17 |
| Resolver / effective POM | `impl/maven-impl`, `impl/maven-core` | Original Maven model, repository cache and profiles | Parents/BOM/settings/snapshot metadata invalidation, model/cache differential |
| Reactor and lifecycle | `impl/maven-core`, `impl/maven-cli` | Original goals, parallelism and execution paths | Full DAG change closure, mixed-module cycle handling and failure-atomic outputs |
| Compiler | `M3AstCompilerConfiguration` | Opt-in `-Dm3.ast=true` javac plugin injection | True compiler facts/negative lookup proof and class output ownership |
| File delta | M3JDK21 `M3File` external owner | Content-aware observation contract and ledger | Publisher generations and source+output closure; metadata-only skips forbidden |
| Watch mode | `M3ProjectWatch` | Opt-in per-generation clean+lifecycle correctness fallback | Native incremental scheduling only after actual compiler evidence |
| M3 native class analysis | `DefaultM3BuildToolsService` | 256-slot session cache / SHA256 / ToolProvider verification | This PR: capacity preflight; then real native ABI/sanitizer/JDK evidence |
| Maven plugin ecosystem | Maven Compiler/Resources/JAR/Dependency/Surefire, etc. | Plugin-owned goals remain authoritative | Per-goal contract, effects, inputs/outputs, cache qualification before any skip |
| Quality plugins | existing Maven CI + opt-in PR #22 | Normal upstream gates unaffected | Complete Checkstyle/PMD/SpotBugs/Spotless/JaCoCo and multi-OS evidence |
| JNI/FFM | M3JDK21 native product, not Maven | No new JNI import in this repair | Exact ABI, borrow/pin lifetime, Java fallback, differential and crash evidence |
| Recipe governance | Synexia `M3HashPinnedJavaSnapshotRecipe` | SHA-sealed originals/postimages and per-file atom | Executed Maven SDK, fixed point, repo receiving, no-loss Git tree readback |
| Upstream convergence | `apache/maven` master vs fork `master@758b456...` | Fork is intentionally separate and diverged | Native upstream compare/merge with source retention and full reactor CI |

**Order for remaining work:** inventory and branch-history witnesses → category/donor review → atom-level contract/spec → Maven/OpenRewrite recipe and exact preimages → Java 17+21 unit and differential JUnit → additive receiving of exact source postimages → full Maven 4 reactor and other supported JDKs/OSes → output/state invalidation proof → source readback → measured benchmarks → PR. No parallel caches/AST/compilers or challenge solution bodies; external donor algorithms are reference-only unless license and contract permit source transfer.

The original [Maven Build Cache Extension](https://github.com/apache/maven-build-cache-extension), [Maven Compiler Plugin](https://github.com/apache/maven-compiler-plugin), [Apache mvnd](https://github.com/apache/maven-mvnd), [Eclipse JDT](https://github.com/eclipse-jdt/eclipse.jdt.core), and JDK ToolProvider are review donors. Their algorithms are not automatically safe substitutions for Maven core or JNI.

The specific capacity fix here changes **one private Java method atom** and adds **one original JUnit test**. All other areas remain separate follow-up admission work.
