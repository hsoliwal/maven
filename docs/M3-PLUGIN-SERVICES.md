<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->

# M3 Maven shared plugin services

M3 Maven promotes reusable plugin capabilities into native Maven services.
Existing plugin coordinates, goals, configuration, lifecycle bindings and
observable behavior remain the compatibility contract. A plugin can delegate
to a shared service while its existing Mojo continues to own execution.

This document separates existing services, the verified-evidence API added by
this change, and the next capabilities to qualify. It does not claim that the
listed plugins have already been absorbed or that their goals can be skipped.

## Existing owners

The inspected Maven baseline is
`eb644c9ea11b6fd40809d2e17cbc61410cf94e57` (`4.2.0-SNAPSHOT`).

| Concern | Existing owner | Integration rule |
| --- | --- | --- |
| Service access and injection | `Session.getService`, native Maven 4 plugin bindings | Extend existing services; use the current session. |
| Dependency resolution | `DependencyResolver`, `ArtifactResolver` | Preserve Maven Resolver selection, scope and conflict semantics. |
| Service-request memoization | `RequestCache` | Reuse request caching; persistent build-output restoration has a different contract. |
| Source roots and attached artifacts | `ProjectManager`, POM 4.1 source declarations | Register through the current project; preserve lifecycle ordering. |
| Toolchain matching | `ToolchainManager` | Respect explicitly selected tools and record their identities. |
| JDK class-dependency facts | `M3BuildToolsService` | Keep the selected JDK provider as the analysis owner. |
| Plugin evidence | `M3PluginEvidenceService` | Bind original producer identity, schema, capabilities and SHA-256 to the session. |

The relevant Maven 4/M3 service contracts remain `@Experimental`; compatibility
must be qualified against pinned Maven, plugin and configuration versions.

The canonical Synexia survey lives in
`synexia-openrewrite-recipes/recipe-crates/m3-project-delta-20261021/catalogue/MAVEN_PLUGIN_API_GOALS_DERIVED.tsv`.
Its discovery API is
`synexia-m3-recipe/src/main/java/com/synexia/m3/recipe/M3MavenPluginApi.java`.
Catalogue membership is an inventory fact, not proof of implementation,
semantic parity, or execution reuse.

## First API improvement: consume exactly the bytes that were verified

Previously, `find(...)` authenticated a producer-owned evidence file and
returned a `Receipt` containing its path. A consumer that subsequently opened
the path could read different bytes if the producer changed the file.

`M3PluginEvidenceService.read(...)` returns an immutable `Snapshot` containing
the receipt and the exact authenticated content. It checks the original
project/plugin/version/goal/execution/context/generation identity, the schema
and required capability mask. The native implementation selects and reads
under the existing session-store synchronization.

```java
M3PluginEvidenceService evidence =
        session.getService(M3PluginEvidenceService.class);

Optional<M3PluginEvidenceService.Snapshot> result = evidence.read(
        session,
        invocationIdentity,
        "m3.tests.results.v1",
        M3PluginEvidenceService.TESTS);

if (result.isPresent()) {
    byte[] authenticatedBytes = result.get().bytes();
    // The owning report adapter interprets its versioned schema.
    // Read authenticatedBytes, rather than reopening receipt.file().
}
```

The new method is a default interface method so implementations of the
existing `publish/find/revoke` contract remain usable. Its fallback performs
its own verified read after `find`; it does not assume that a path verified
earlier still contains the same data. `Receipt.verify()` performs bounded
streaming verification, while `Receipt.snapshot()` provides the same
authenticated-content operation for a receipt already held by a caller.
These operations share one specialized reader.

The original 16 MiB per-file limit and regular-file/no-follow checks apply.
The session store retains receipt references, not content arrays. A returned
snapshot owns its content: later file changes and changes to arrays returned
by `bytes()` do not alter it. Revocation affects later lookups, not snapshots
already returned to a caller.

A digest proves the relationship between the returned bytes and the declared
receipt. It does not establish that a report is true, that its producer is
trusted, that an output is reusable, or that a goal may be replaced. Those
decisions retain their existing owners and qualification gates.

## Capabilities to absorb next

These are proposed implementation priorities, not new automatic behavior.

| Priority | Plugin or extension donor | Capability to expose through Maven | Compatibility requirement |
| --- | --- | --- | --- |
| 1 | Maven Build Cache Extension and compiler incremental machinery | Declared inputs/outputs, cache eligibility, restoration requirements, and structured invalidation reasons | M3File physical deltas and M3AST semantic facts must supply evidence for the minimum safe work; Maven runs the original lifecycle. An incomplete input declaration cannot authorize reuse. |
| 2 | Maven Enforcer | Typed rule evaluation and findings shared by CLI, IDE, CI and smart-build consumers | Existing configured rules and failure behavior remain authoritative; new policy is opt-in. |
| 3 | Maven Dependency Plugin and Resolver | Dependency graphs, selection explanations and usage evidence | Reuse Resolver. Bytecode usage analysis alone does not authorize removing dependencies used through reflection or runtime loading. |
| 4 | Maven Toolchains Plugin | Installed-tool discovery and deterministic selection through the existing manager/provider model | Explicit user configuration wins; toolchain identity participates in reuse decisions. |
| 5 | Build Helper | Migration of compatible source-root and artifact-registration uses to existing native APIs | Maven 4 already covers multiple source roots; preserve scopes, generated-source timing and attached-artifact metadata. |
| 6 | Maven Artifact Plugin and archiver components | Reproducibility checks and structured build/output evidence | Compare actual outputs and retain plugin-specific archive semantics; timestamps alone are not reproducibility proof. |

Shared resource scanning/filtering, test-result consumption and archive
metadata are also reasonable service extractions when a concrete duplicate
is identified. Compiler backends, Surefire/Failsafe providers, code generators,
shading and deployment retain their specialized implementations until a
specific replacement is qualified.

## Qualification and migration

For each extraction, inventory the exact plugin version and source revision;
freeze its effective parameters, dependency-resolution scope, lifecycle forks,
class-realm behavior, output registration, properties, errors and side effects.
Keep unqualified configurations on the original Mojo. Shared code retains
donor license notices and provenance.

The canonical transformation for this API addition is
`synexia-openrewrite-recipes/recipe-crates/m3-maven-plugin-evidence-20261115`
in `hsoliwal/com.synexia`. It uses the existing hash-pinned Java and text
OpenRewrite engines, with exact preimages and postimages, deterministic
replay, fixed-point and refusal tests. The Maven fork consumes its generated
source, tests and this document.

Normal Maven behavior remains usable with the M3 services. ChromeLLM advice
can consume verified reports and recommend registered recipes; advice does
not bypass compilation, tests or the original execution contract.

## Primary references

- [Maven 4 changes and plugin API compatibility](https://maven.apache.org/whatsnewinmaven4.html)
- [Maven service access](https://maven.apache.org/ref/4.0.0-rc-7/api/apidocs/org/apache/maven/api/Service.html)
- [Maven 4 source declarations](https://maven.apache.org/plugins/maven-compiler-plugin-4.x/sources.html)
- [Build Cache Extension concepts](https://maven.apache.org/extensions/maven-build-cache-extension/concepts.html)
- [Enforcer built-in rules](https://maven.apache.org/enforcer/enforcer-rules/)
- [Dependency analysis and its limits](https://maven.apache.org/plugins/maven-dependency-plugin/analyze-mojo.html)
- [Toolchain discovery](https://maven.apache.org/plugins/maven-toolchains-plugin/toolchains/jdk-discovery.html)
- [Reproducible builds](https://maven.apache.org/guides/mini/guide-reproducible-builds.html)

The web references describe upstream capabilities; this fork's exact baseline
and generated-source hashes identify the implementation being changed.


## Native dependency insights and toolchain selection (2026-11-16)

The new `DependencyInsightsService` inspects a **previously obtained**
`DependencyResolverResult`. It does not call Resolver, access repositories,
open dependency JARs, infer dependency usage, or change resolution decisions.
Its immutable report includes the original flattened selected artifacts and,
when the caller requested verbose resolution, explicit omitted/winning nodes
and managed-version evidence. Output is stable and sorted by Maven artifact
identity and version, with duplicate evidence removed. The graph walker is
iterative, keeps visited references by identity, and refuses graphs with more
than 262,144 nodes/edges instead of returning partial evidence as clean.

```java
DependencyResolverResult resolved = session.getService(DependencyResolver.class)
        .resolve(existingRequest); // Caller retains ownership of this request.
DependencyInsightsService.Report insights = session
        .getService(DependencyInsightsService.class).inspect(resolved);
if (!insights.complete()) {
    // Do not promote an incomplete graph to a dependency-policy verdict.
}
if (!insights.verbose()) {
    // No omission evidence does NOT prove version convergence.
}
```

`complete()` only reflects a RESOLVE request with a root and no reported
resolver errors. It is not proof of downloaded bytes, semantic usage,
conflict freedom, or permission to omit a plugin. To obtain omitted/winning
metadata, callers must opt in to `DependencyResolverRequest.getVerbose()`
when constructing the original request. The new service only observes it.

The native `ToolchainManager` now exposes an optional read-only
`selectToolchain(session, type, requirements)` convenience method. It
prefers a type-correct, matching toolchain from the existing build context,
then checks matching toolchains in the manager's existing configured/provider
order. It copies requirement inputs and never stores a new toolchain,
downloads a JDK, overwrites a build-context selection, or changes the
Toolchains plugin's existing goals. An empty result requires the caller to
handle missing tooling; it does not trigger fallback to an unqualified JDK.

```java
Optional<Toolchain> chosen = session.getService(ToolchainManager.class)
        .selectToolchain(session, "jdk", Map.of("version", "21"));
// Existing toolchain Mojo selection and storage remain unchanged.
```

These two APIs are a narrow, opt-in extraction from Dependency/Enforcer and
Toolchains plugin concerns into the existing Maven 4 owners. Neither plugin
is replaced. No Build Cache Extension, Compiler, Surefire or archive engine
was migrated by this change. In particular, `M3PluginEvidenceService`
receipts are not cache admission or goal-skipping permissions.

Original Maven API/DI tests live under
`impl/maven-impl/src/test/java/org/apache/maven/impl/`, covering DI,
sorted immutable selection results, verbose winner/management explanations,
incomplete resolution, cycles/shared graph nodes, bounded refusal and
read-only toolchain fallback. Required qualification before merging:
Maven reactor tests, Checkstyle/Spotless, the upstream API compatibility
matrix and differential plugin behavior fixtures. Source transformation
recipes and actual plugin producer adapters are separate migrations, not
claimed as completed here.
