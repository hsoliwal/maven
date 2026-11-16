<!--
SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# M3 Maven external IDE/build-owner bridge (experimental)

This is an **additive Maven 4.2.0-SNAPSHOT session service**, not a replacement
for Eclipse JDT, m2e, Visual Studio, VS Code, MSBuild, CMake, Gradle, Ant, Make,
Ninja, CodeQL or OpenRewrite. Existing Maven project graphs, plugin goals,
toolchains, dependencies, compiler execution and lifecycle behavior are unchanged.

## Use the new Maven API

Call session.getService(M3ExternalBuildService.class) from a Maven 4 consumer.
Supply the host's previous successful Generation, current Generation, and
List<M3ExternalBuildService.Change> (added/modified/deleted paths).
Call service.assess(previous, current, changes).

The resulting Assessment has one of two **advisory** dispositions:

- HOST_DELTA_AVAILABLE: the host may supply changes to its **own** incremental
  compiler/builder. Maven goals are never skipped as a consequence.
- MAVEN_RECONCILIATION: the host delta cannot justify incremental confidence.
  Ask the ordinary Maven lifecycle/model integration to reconcile as needed.
  The service does **not** run Maven, alter task graphs, or start a compiler.

A host Generation binds provider identity, project identity, sequence, effective
build model, toolchain, dependencies/classpath and source roots. Host hashes and
"complete" flags are **claims**, not authenticated evidence. This service does
not rehash source files, keep another source cache, or create ASTs. No result
proves semantic equivalence, output completeness, or build-cache eligibility.

Missing baselines, incomplete delta coverage, sequence gaps, changed model,
toolchain/dependency/roots, source deletions, build descriptor changes and
generated-output changes require conservative reconciliation. Duplicate paths,
unsafe paths, invalid SHA-256, malformed Unicode and excess size are refused.
A sorted, packed array-backed report retains only paths, kinds and optional
digest strings. Value records are materialized only when requested.

## Eclipse JDT and m2e integration

Eclipse Platform IResourceDelta plus IncrementalProjectBuilder remain the
**source owners** of change notifications, compilation state, output deletion,
cancellation and problem markers. A separately installed, opt-in m2e adapter
may translate IResourceDelta.ADDED/CHANGED/REMOVED to the service ChangeKind
and attach one host Generation to each completed owner build. Do not create
a second source scanner or a second Java incremental compiler inside Maven.

m2e's lifecycle mappings and AbstractBuildParticipant still determine which
Maven goals execute during Eclipse auto/incremental/full builds. Never let an
IDE edit event automatically bypass resources, tests, packaging or publishing.
Do not trigger a second Maven watcher on generated output trees, since recursive
Eclipse rebuilds can result. If model/classpath/annotation processor or
generated roots change, go through m2e Maven-project reconfiguration first.

This new service requires an **M3 Maven 4 runtime**. Existing Maven 3 m2e
embedders do not automatically provide it: install and qualify a separate
adapter; do not silently substitute a Maven 3 internal API.

References:
- https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/resAdv_builders.htm
- https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/guide/jdt_api_compile.htm
- https://eclipse.dev/m2e/documentation/m2e-execution-not-covered.html

## Visual Studio, MSBuild, CMake and VS Code

Visual Studio **Open Folder** can configure an external Maven command using
tasks.vs.json and can retain MSBuild/CMake for .NET and C/C++ project ownership.
It is not the same product as VS Code. VS Code's tasks.json and LSP/Java build
services require their own adapter. The IDE decides when to invoke its
original incremental or clean build; only normalized provider-owned change
notifications cross the M3 exchange API.

If a consumer intentionally wants Maven goals executed, use the existing
Maven 4 LifecycleExecutor planning/execution API, not a delta advisory result.
MSBuild/CMake still own their own graph and incremental state.

References:
- https://learn.microsoft.com/en-us/visualstudio/ide/customize-build-and-debug-tasks-in-visual-studio?view=vs-2022
- https://learn.microsoft.com/en-us/cpp/ide/non-msbuild-projects

## Other adapters and ownership

Namespaced provider IDs may include eclipse.jdt, visualstudio.msbuild,
visualstudio.cmake, vscode.java, gradle, ant, make, ninja, codeql and
openrewrite. Source compilers and their own incremental/build-cache engines
remain authoritative. CodeQL findings are advisory evidence; OpenRewrite
recipes run only on explicit request, then through compile/test/contract gates.

This module currently implements **only a typed exchange and conservative
classifier**. It does not include an installed Eclipse extension or VSIX,
and there is no claim that the user can yet run this feature inside an IDE.

## Required verification

1. Maven API/implementation reactor, JUnit/DI, Checkstyle, Spotless and
   Maven source/binary compatibility.
2. Eclipse JDT/m2e full/incremental/clean/edit/delete/multi-project and
   output/marker validation with an actual, separately qualified adapter.
3. Visual Studio Windows Open Folder, MSBuild/CMake outputs, cancellation,
   model refresh, diagnostics and exit codes; VS Code LSP adapter separately.
4. Java toolchain changes, compiler/classpath updates, generated resources
   and cross-platform path rules, including case-insensitive workspaces.
5. Only after independent output verification and complete dependency evidence
   could a separate cache-admission design even be considered.

The native service is not a permission to skip compilation, testing,
packaging or publication.
