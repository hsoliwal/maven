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

# M3 Maven — native open-source static-analysis evidence

## Architecture and compatibility

The new experimental `M3StaticAnalysisService` is a read-only Maven API
exposed by the original Maven 4 DI container. It sits *after* existing
analyzers and compilers, never instead of them. An original plugin/adapter
must run its own scan and explicitly publish an immutable, versioned receipt
through the existing `M3PluginEvidenceService` in the original Maven
session. The native consumer validates receipt membership, exact
project/plugin/goal/context/generation, capability, schema and source
bytes. It does not scan source, infer source coverage or execute a Mojo.

```text
Original Maven / M3FS / compiler inputs
        |
        +-- Checkstyle / PMD & CPD / SpotBugs / Error Prone
        +-- Semgrep / CodeQL (external and separately licensed)
        +-- JDK javac -Xlint / future M3AST analysis
        |
Original goal or permitted adapter owns execution and normalization
        |
M3PluginEvidenceService (exact original identity and SHA-256 custody)
        |
M3StaticAnalysisService (bounded typed parser + deterministic aggregation)
        |
Eclipse/IDE, Maven reporting, CI policy adviser, approved Rewrite recipes
```

Existing POM plugin coordinates, goals, rulesets, compiler options, reports,
failure policies and class realms do not change. Analysis is opt-in; Maven
must not implicitly download engines, run rules, change a build outcome or
silently skip an existing goal. A validated receipt authenticates bytes,
**not** the report's semantic truth or the tool that produced them.

## Original engines and licensing boundaries

| Original engine | Native integration candidate | Licensing/ownership |
| --- | --- | --- |
| Apache Maven Checkstyle Plugin / Checkstyle | Original style/Javadoc/naming findings | Original Checkstyle engine LGPL-2.1; keep isolated |
| Apache Maven PMD Plugin / PMD and CPD | Original Java rules and duplication findings | PMD BSD-style license; preserve original conditions |
| SpotBugs Maven Plugin | Original bytecode bug/priority findings | LGPL-2.1; external provider |
| Google Error Prone | Compiler-produced diagnostics | Original javac/plexus compiler plugin owns execution |
| Semgrep OSS | Multi-language rules and source ranges | LGPL-2.1; external provider |
| github/codeql libraries/queries | Semantic security findings through SARIF | Queries MIT; CLI/engine separately licensed |
| JDK javac / CompilerFactsService / M3AST | Existing compiler facts or locally computed static checks | Preserve original attribution and JDK/compiler owner |
| OpenRewrite | Registered recipes for approved fixes | Analyzers only propose; recipe proof and tests are separate |

The `tools/m3-polyglot-maven-plugin` integration already delegates CodeQL
and OpenRewrite to their original tools; it is not supplanted. Analyzer
output format adapters are separately qualified, version-pinned components.
This commit **does not** implement PMD XML, Checkstyle XML, SpotBugs XML,
Semgrep JSON or CodeQL SARIF conversion, and does not copy third-party
engines into Maven Core.

## Canonical v1 normalized report

A producer adapter writes strict UTF-8 with LF-only rows (including the
final LF), no BOM, no carriage returns, and six tab-separated columns:

```text
M3-STATIC-ANALYSIS-V1
status\tCOMPLETE
rule\tseverity\tpath\tline\tcolumn\tmessage64
UnusedImports\tWARNING\tsrc/main/java/App.java\t12\t3\tVW51c2VkIGltcG9ydA
```

In this example, `message64` is Base64URL without padding, encoding the
UTF-8 diagnostic message. Real tab bytes separate fields, not the
backslash-letter escape sequence shown above. Every finding preserves the
original rule identifier, the producer's mapped INFO/WARNING/ERROR severity,
the canonical project-relative path, nonnegative line/column, and an
explanation. An empty path with position 0/0 represents a project-wide
finding. The native service rejects traversal, drive/absolute paths,
ambiguous UTF-16, invalid status/severity/positions, duplicate or unordered
rows, bad Base64URL encoding, overlong records and malformed UTF-8.

Status is one of `COMPLETE`, `PARTIAL`, `FAILED`. `MISSING` is produced
only by the consumer when an expected original invocation is absent. The
canonical finding order is: path, numeric line, numeric column, rule,
severity, message. A producer cannot claim a successful aggregate by
omitting an expected analyzer. To preserve distinct findings, entries from
different original analyzer invocations are never silently coalesced.

`M3PluginEvidenceService.ANALYSIS` is the new capability bit (128).
The published receipt uses schema `m3.static-analysis.findings.v1` and the
same original `Identity` as the owning plugin. The existing 16 MiB receipt
budget applies, as do a maximum 65,536 findings per report and 64 expected
analyzer invocations per aggregate. All lists and snapshots are immutable;
the internal per-report index uses parallel arrays, with finding records
materialized on demand.

## Native API

```java
M3StaticAnalysisService analysis =
        session.getService(M3StaticAnalysisService.class);

Optional<M3StaticAnalysisService.Manifest> checked =
        analysis.read(session, originalCheckstyleIdentity);

M3StaticAnalysisService.Report aggregate = analysis.aggregate(
        session, List.of(originalCheckstyleIdentity, originalPmdIdentity,
                originalSpotBugsIdentity));
if (!aggregate.complete()) {
    // Distinguish MISSING, FAILED and PARTIAL from original COMPLETE.
    // A missing analyzer must not be reported as zero violations.
}
```

The exact `Identity` includes the original context SHA-256 and generation;
a change in source, build configuration, rules, analyzer version, scope,
toolchain or classpath needs a new context binding from the producer. The
service does not compute those facts. Source completeness, actual analyzer
execution status, suppression semantics, false positives, external tools,
source mutations, and performance budgets remain their original owners.

A complete aggregate is an observation of producer claims. It is **not**
a release gate, a trustworthy clean-code verdict, a cache hit, or permission
to apply an OpenRewrite transformation. A Maven policy layer may separately
interpret correctly qualified diagnostics, but must preserve the original
plugin's severity, suppressions, exit behavior and explicit user opt-in.

## Qualification gates and next native donor migrations

The first milestone is the original Maven API + implementation + JUnit/DI
contract, including exact producer receipt membership, tampering, absent/
partial/failed coverage, sorted range data, malformed report refusal,
budgets and cross-session isolation.

Before broad release, run JDK 17/21/25 where supported; Maven reactor
`verify`, Checkstyle, Spotless, plugin compatibility and Maven integration
fixtures. Add separately tested source-bound adapters for PMD, Checkstyle
and SpotBugs before connecting CI policy. Qualify Error Prone through the
actual compiler, and Semgrep/CodeQL via external licensed processes and
their original output formats. Use original OpenRewrite recipes only after
DIFF → LINT → COMPILE → TEST and source ABI/behavior checks.

See [shared plugin services](M3-PLUGIN-SERVICES.md) for previously merged
resource, compiler, dependency, policy and fingerprint primitives.

## Private atomization and Gradle-compatible report pattern (2026-11-16)

The native analyzer service refactors its original operations into small,
contract-preserving, private Java atoms: `verifiedManifest` checks exact
receipt ownership; `sortedExpected` validates/sorts/deduplicates invocation
identities before any evidence read; `appendFindings` enforces an aggregate
budget before materialization; `canonicalRow` checks framing and LF/NUL/CR
refusal; `declaredStatus` admits only the original completion states;
`parseFinding` independently validates typed position and message facts.
The original `M3StaticAnalysisService` API, `Identity`, schemas, session
membership, array-backed row index, byte limits, ordering, exceptions and
plugin behavior remain unchanged.

The canonical v1 wire format is intentionally consumable by the original
`hsoliwal/gradle` build without installing Maven inside Gradle. The Gradle
`m3/quality-bridge` provider exposes an explicit `m3ReviewAnalysis` task
that checks a producer-declared report SHA-256, decodes the same typed rows
into compact indexed arrays and can optionally publish adapter-claimed
findings through Gradle's native Problems API. Its default is read-only and
not wired into `check`. A digest validates exported bytes, **not** Maven
session membership, real analyzer execution or source completeness.

Both builds preserve their existing analyzers, independent task/goal
scheduling, dependency resolution and build caches. Original analyzer
plugins must still execute and remain the authority for their reported
findings and suppression policies. Differences between Maven's receipt
membership guarantees and Gradle's standalone digest check are explicit.

## Incremental analyzer diagnostic comparison (2026-11-16)

`M3StaticAnalysisService.compare(earlier, later)` is an additive **default
interface method**, preserving source/binary compatibility with implementations
that provide only `read` and `aggregate`. It performs an O(n) merge over
the canonical sorted findings already held in authenticated, source-bound
manifests. There is no second analyzer run, source parse, retained entry graph
or new Maven plugin lifecycle binding. The result reports unchanged, newly
introduced and resolved findings, plus a count of newly introduced ERRORs.

Both reports must originate from the **same project, plugin group/artifact,
plugin version, goal and execution**. The invocation SHA-256 context and
generation may change as sources evolve. A change to the ruleset, source
inventory, suppression policy, toolchain or classpath can still change report
meaning; matching producer coordinates do **not** prove that those underlying
conditions are equivalent. A rule/severity/message/position change is recorded
as a resolved finding and a newly introduced finding, rather than automatically
considered the same diagnostic.

```java
var before = analysis.read(session, earlierIdentity).orElseThrow();
var after = analysis.read(session, laterIdentity).orElseThrow();
var changes = analysis.compare(before, after);
if (!changes.comparable()) {
    // At least one producer run claims PARTIAL/FAILED/MISSING; do not report clean.
} else {
    int newErrors = changes.introducedErrors();
}
```

Each comparison refuses different producers, out-of-order entries, excessive
finding counts or invalid null data, and reports `comparable=false` with
zeroed **non-authoritative** counts when either original run is not COMPLETE.
Consumers must check the `comparable` flag; zeroed counts are not evidence
of no regressions. The native Maven service remains the original receipt
authenticator. The comparison itself makes no build-cache, compiler,
OpenRewrite or release decision.

The independent Gradle `m3ReviewAnalysis` task uses the same normalized
two-pointer comparison in its Java 17 reader when a baseline report, digest,
and both declared producer keys are explicitly configured. It must verify
both report byte hashes. The original Maven receipt membership and producer
integrity do not transfer across build systems merely because Gradle receives
the digest. Both Gradle baseline comparison and gating on introduced ERRORs
are **disabled by default**, and no original analyzer task is skipped.
## Constant-time native analyzer severity summaries

The additive `Manifest.count(Severity)` default method preserves existing
third-party and prior Maven implementations: its fallback delegates to the
existing public `size()` and `entry()` methods without a new abstract
contract. Native `DefaultM3StaticAnalysisService` overrides it with a
precomputed three-counter array populated alongside its compact severity
byte lane, making repeat summaries O(1), with no retained per-entry objects.
Severity interpretation and source provenance remain the original analyzer's
responsibility.

The independently implemented Gradle `M3AnalysisV1` reader follows the
same pattern, with a byte-array severity lane and parser-maintained error
counter. This is an optimization of existing evidence indexing, **not**
an automatic Maven/Gradle policy decision or a plugin-goal replacement.


## Original Checkstyle/PMD XML adapters (2026-11-16)

The standalone tools/m3-analyzer-report-adapter module is the first
producer-side format adapter for native analyzer evidence. It provides both
a Java 17 NormalizeCli and an **explicit Maven normalize-xml Mojo goal**
(no implicit lifecycle binding). The pure Java implementation handles already
produced Checkstyle file/error XML and PMD 2.0 file/violation XML; their
engines, rule configuration, suppression decisions and exit statuses remain
with the original Checkstyle/PMD plugins.

The converter maps canonical, in-project source paths and original finding
fields to the same M3-STATIC-ANALYSIS-V1 row ordering and Base64URL message
schema understood by M3StaticAnalysisService. The original producer must
supply COMPLETE, PARTIAL or FAILED explicitly. PMD processing/configuration
errors cannot be accepted as COMPLETE. DTD/entity expansion, invalid source
paths, duplicate canonical findings and oversized inputs are refused.

The converter does **not** automatically publish a
M3PluginEvidenceService receipt: actual source-bound publication requires
the original Maven Session, plugin goal, generation and full execution
context. A checksum alone is not a producer certificate, a cache admission
gate or permission to skip checks.

The Gradle quality bridge's independent m3NormalizeAnalyzerXml task can
invoke the very same built Java adapter JAR by direct argv with an explicit
SHA-256 pin and status; it never loads Maven Core or reimplements the XML
decoder. Its output remains the existing Gradle m3ReviewAnalysis input
format. Both tasks are opt-in, and neither triggers original analyzers
automatically.

SpotBugs, CPD, Semgrep, CodeQL SARIF and additional XML dialect adapters
remain separate, independently qualified donor work. Java 17/21 standalone
tests, actual Checkstyle/PMD provider fixtures, Maven plugin descriptor,
full reactor, Gradle TestKit and original behavior parity must pass before
promoting the converter as release-qualified.
