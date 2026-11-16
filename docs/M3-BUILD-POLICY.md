<!--
SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# M3 Maven native build policy API (experimental)

`BuildPolicyService` is an additive service available from `Session#getService`.
It promotes three reusable checks toward Maven Core without changing the
`maven-enforcer-plugin`, lifecycle defaults, or any existing POMs.

```java
BuildPolicyService policies = session.getService(BuildPolicyService.class);
BuildPolicyService.Report report = policies.evaluate(
        BuildPolicyService.Request.currentJvm(
                session,
                dependencyResult,
                List.of(
                        new BuildPolicyService.MavenVersion("[4.0,5.0)", Severity.ERROR),
                        new BuildPolicyService.JavaFeature(21, Severity.ERROR),
                        new BuildPolicyService.NoSnapshotDependencies(Severity.WARN))));
if (!report.accepted()) {
    // The caller chooses how to display findings and fail its own goal.
}
```

The API deliberately has **no automatic POM parsing or goal binding**. Rules run
only when a caller explicitly requests them. An unbracketed Maven requirement
is a minimum; a bracketed requirement uses the installed Maven version parser.
`JavaFeature` checks the JVM executing Maven rather than the compiler toolchain.
A dependency snapshot check consumes the original `DependencyResolverResult`
selected dependency set. Missing resolution, resolver exceptions and a missing
root yield an explicit incomplete finding, not a successful result. `WARN`
findings do not fail `Report#accepted()`.

## Compatibility boundaries

- The Maven Enforcer plugin remains authoritative for existing
  `<requireMavenVersion>`, `<requireJavaVersion>`, `dependencyConvergence`, and
  other legacy rule semantics. This native subset is **not** a full Enforcer
  parser, goal replacement, or a claim of Enforcer configuration parity.
- The `DependencyResolverResult` supplied by the caller must cover the intended
  selection; a scoped or filtered result cannot prove a project-wide absence
  of snapshots. The service never resolves dependencies itself.
- Neither Maven version policy nor runtime JVM feature policy is a build cache
  or semantic proof. No rule produces an authorization to skip goals, tests or
  publishing checks.
- Policy results and request rules are immutable; the evaluator retains no
  project, session, artifact or graph state between calls.

## Validation

`DefaultBuildPolicyServiceTest` tests minimum/range comparisons through Maven's
`VersionParser`, Java runtime feature checks, severity, failure on incomplete
dependency resolution, sorted snapshot diagnostics and immutable results.
Full reactor verification and Enforcer behavior-differential testing are
required before claiming production parity.
