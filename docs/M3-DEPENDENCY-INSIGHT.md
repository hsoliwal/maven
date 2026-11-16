<!--
SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# M3 Maven native dependency insight API (experimental)

`DependencyInsightService` exposes compact, immutable, read-only views of the
**original** Maven 4 dependency graph, without a second resolver or dependency
version-selection algorithm.

```java
DependencyResolverResult resolved = /* existing Maven Resolver result */;
DependencyInsightService service = session.getService(DependencyInsightService.class);
DependencyInsightService.Snapshot graph = service.inspect(
        new DependencyInsightService.Request(resolved, 100_000));
for (int index = 0; index < graph.size(); index++) {
    DependencyInsightService.Entry entry = graph.entry(index);
    if (entry.omitted()) {
        System.out.println(graph.path(index) + " -> winner " + entry.winningCoordinates());
    }
}
```

A snapshot stores parent indexes, artifact keys, conflict-winner keys and
management/scope facts in parallel arrays. It creates individual `Entry` records
and root-to-entry path lists only when requested. Iterative depth-first traversal
avoids Java-stack overflow on deeply nested graphs, and input `maxNodes`
prevents unbounded snapshots. Cyclic back-edges and truncated scans make the
snapshot explicitly incomplete. The result retains **no original resolver nodes**.

For conflict/omission and management evidence, callers should resolve with
`DependencyResolverRequest.builder().verbose(true)`; `verboseRequested()`
reports whether the original request used that mode. When it is false, an
`omitted()==false` entry cannot prove that a dependency was selected. A
`complete()` snapshot describes graph traversal completeness only, not a claim
that version-conflict evidence is available when verbose mode was disabled.

This service never changes dependency resolution, generates POM upgrades,
authenticates build-cache reuse, skips any Mojo, replaces `maven-dependency-plugin`
goals, or proves Enforcer's dependency convergence. Existing Maven Resolver
remains the authority. It can feed future version diagnostics or build-policy
rules once complete original evidence and parity fixtures are in place.

`DefaultDependencyInsightServiceTest` covers original verbose winner/management
information, ordered parent paths, bounded scans, cycles, resolver errors,
immutable path results and detachment from original resolver nodes. Full Maven
reactor verification and real resolver integration are required before release.
