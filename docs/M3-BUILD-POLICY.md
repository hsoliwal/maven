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
selected dependency set. Missing resolution, resolver exceptions, a missing
root or a non-`RESOLVE` request yield an explicit incomplete finding, not a
successful result. `WARN`
findings do not fail `Report#accepted()`.

## Compatibility boundaries

- The Maven Enforcer plugin remains authoritative for every rule the native
  subset cannot express exactly (`dependencyConvergence`, `bannedDependencies`,
  `requireFilesExist`, Java upper bounds, and the rest). An enforcer execution is
  absorbed (see below) only when all of its rules map exactly; this is not a full
  Enforcer parser or a claim of Enforcer configuration parity.
- The `DependencyResolverResult` supplied by the caller must cover the intended
  selection; a scoped or filtered result cannot prove a project-wide absence
  of snapshots. The service never resolves dependencies itself.
- Neither Maven version policy nor runtime JVM feature policy is a build cache
  or semantic proof. No rule produces an authorization to skip goals, tests or
  publishing checks.
- Policy results and request rules are immutable; the evaluator retains no
  project, session, artifact or graph state between calls.

## Native evaluation in every build (enforcer absorbed)

`NativeBuildPolicyParticipant` (maven-core) calls this service for every project
before any goal runs, with no plugin declaration and no POM change:

- Rules come from the project's `maven-enforcer-plugin` executions (goal
  `enforce`, configuration of the execution or the plugin) and from properties:
  `maven.policy.mavenVersion` (minimum or range), `maven.policy.javaFeature`
  (integer) and `maven.policy.severity` (`ERROR` default, or `WARN`).
- `requireMavenVersion` maps unchanged. `requireJavaVersion` maps only when it is
  exactly a minimum feature: `17`, `17.0`, `1.8`, `[17,)`, `[1.8,)`; patch levels,
  exclusive and upper bounds stay with the plugin. A rule's `<level>` and an
  execution's `<fail>false</fail>` become the severity.
- An execution whose rules all map is removed from the build: the plugin is neither
  resolved nor run. Other executions are kept and the plugin evaluates them.
- An `ERROR` finding fails the build before any goal with
  `Native build policy failed`, one line per project and rule; `WARN` findings
  are logged. `-Dmaven.policy.native=false` leaves everything to the plugin.

## Validation

`NativeBuildPolicyParticipantTest` covers the Java mapping (including what must
stay with the plugin), properties, rule levels, `fail=false`, and that only fully
covered executions are absorbed. `apache-maven/src/it/test_native_policy.sh` runs the
distribution offline with an empty local repository: a covered execution builds
green without the plugin, unmet Java and Maven rules fail natively, properties and
`WARN` work, an uncovered rule and `maven.policy.native=false` leave the plugin in
charge, and stock Maven cannot build the covered case offline.

`DefaultBuildPolicyServiceTest` tests minimum/range comparisons through Maven's
`VersionParser`, Java runtime feature checks, severity, failure on incomplete
dependency resolution, sorted snapshot diagnostics and immutable results.
Full reactor verification and Enforcer behavior-differential testing are
required before claiming production parity.
