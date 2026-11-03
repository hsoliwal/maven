<!-- SPDX-License-Identifier: Apache-2.0 -->
# Maven 4 compiler facts and plugin capability negotiation

This fork's build model, dependency resolution, reactor, toolchains,
plugin semantics and compiler extension points remain Maven-owned.
M3JDK21 is ONE SDK product, not a Maven layer over another JDK.
M3File owns source/file observations, M3AST/javac owns the original
parse/attribution events, and M3FS/M3IndexDB owns durable fact storage.

The new additive Maven 4 API service
`org.apache.maven.api.services.CompilerFactsService` is available through
`Session.getService(CompilerFactsService.class)` once its matching service
implementation is installed. Its session-scoped reference inventory uses
bounded parallel arrays and binary search, not a second parser or AST cache.
A producer may `publish(session, Image)` after its original compiler
operation successfully generates the image. An analyzer requests
`find(session, exactSourceIdentity, requiredSchema, requiredCapabilities)`.
A missing capability or schema is a miss, not a request to reconstruct a new
AST. The producer retains ownership of the image bytes and controls removal;
Maven checks SHA-256 on admission and again before returning a reference.
SessionData holds pointers only for that session, NOT an unbounded global
cache. Persistent reuse is separately entrusted to the SDK storage owner.

For `m3jdk21.javac.geometry.v1`, request
`SYNTAX | POSITIONS | PARENTS`. Do not ask for `ATTRIBUTION` or `BYTECODE`
from that syntax-only image. Its source identity is the JDK's exact UTF-16
SHA-256, **not** the SHA-256 of the original file bytes. The context digest
must include toolchain/compiler binary, compiler switches, annotation
processors, ordered classpath/module path and participating generation
inputs. Producer/source claims remain advisory until compiler provenance is
independently qualified.

Maven 4's current opt-in `-Dm3.ast=true` already injects
`-Xplugin:M3AST` into ordinary compile and testCompile configurations,
without re-reading the source. This PR does NOT add automatic image
publication or silently turn on plugins: completing that needs the original
compiler event publisher and a success-only atomic M3FS generation.
Original analyzers must opt in to the exact schema and retain their own
algorithms. There is no claim that a Maven service can transparently replace
all analyzer ASTs.

## Candidate plugin capabilities to absorb into Maven's SDK-aware core

| Donor/plugin area | Maven core/native build API | Keep plugin-owned |
| --- | --- | --- |
| Maven Compiler Plugin | build-input identity, incremental planning, JavaCompiler facts and output ownership | compiler/toolchain/annotation processing/goal-specific configuration |
| Maven Dependency Plugin | resolved Maven graph, dependency-use and classpath facts | user-facing analyze goals, reporting, pluggable dependency analyzers |
| Resources/JAR | fingerprints, deterministic input/output manifests, reproducible packaging primitives | filtering, archive/plugin-specific transformations |
| SpotBugs | reuse class bytes and compiler-origin lookup facts | **bytecode** CFG, BCEL/dataflow and rule engine; it is not just another source AST |
| PMD/Checkstyle | source identity, lexer/token geometry and compatible syntax facts | their specific AST conventions, rules and suppression semantics |
| Error Prone/OpenRewrite | original javac compilation events and bounded projections | attributed checks, rewrite LST/printing and recipe semantics |
| Surefire/Failsafe | test dependency/config/output evidence | discovery, fork lifecycles, engine/provider behavior |

Use Apache Maven official plugin source under its respective license;
SpotBugs source has distinct LGPL implications. LeetCode/HackerRank/GFG
are algorithm *categories* for rank/select, bitmaps, CSR and traversal,
not automatically reusable licensed code. Qualify every donor with
contract parity, JVM/JNI/platform tests, attribution and benchmarks.

## Verification

Java 17 baseline standalone source compilation against M3 Maven API-shape
stubs: PASS. Session isolation, schema mismatch, capability refusal, changed
content and exact context checks: **10 executable checks PASS**.
The original Maven reactor, real dependency-injected Maven Service,
compiler-produced publisher, SDK installed image, actual analyzer plugin
migration, full tests and performance measurements: **NOT RUN**.
