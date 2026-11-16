<!--
SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->
# M3 Original Checkstyle/PMD XML Report Adapter

This standalone Java 17 artifact is separate from the Maven root reactor and
does not change Maven's lifecycle or bundle analyzer binaries. It consumes
original Checkstyle or PMD XML **after** the original analyzer has executed,
and emits the exact M3-STATIC-ANALYSIS-V1 report consumed by Maven's native
M3StaticAnalysisService and Gradle's opt-in quality bridge.

## Build and run

~~~sh
mvn -f tools/m3-analyzer-report-adapter/pom.xml verify
java -cp tools/m3-analyzer-report-adapter/target/m3-analyzer-report-adapter-0.1.0-SNAPSHOT.jar \
  io.synexia.maven.analysis.NormalizeCli CHECKSTYLE PARTIAL \
  /source/project /source/project/target/checkstyle-result.xml \
  /source/project/target/analysis.tsv /source/project/target/analysis.sha256
~~~

The same artifact additionally exposes a normal Maven 3-compatible,
**explicit-only** goal named m3-analysis:normalize-xml. It has no default
lifecycle binding and delegates to the identical checked CLI implementation.
Install the standalone module first and invoke the goal on the actual project:

~~~sh
mvn -f tools/m3-analyzer-report-adapter/pom.xml install
mvn io.synexia:m3-analyzer-report-adapter:0.1.0-SNAPSHOT:normalize-xml \
  -Dm3.analysis.xml=target/checkstyle-result.xml \
  -Dm3.analysis.format=CHECKSTYLE \
  -Dm3.analysis.originalStatus=PARTIAL
~~~

The goal defaults to a project-local target/m3-analysis.tsv and
target/m3-analysis.sha256. It requires the original XML to exist and target
to be an existing output directory; it does not execute any Checkstyle or PMD
goal. The actual source owner may explicitly set COMPLETE only after the
original analyzer has reported successful and complete execution.

Exactly six arguments: format (CHECKSTYLE or PMD), **producer-attested**
status (COMPLETE, PARTIAL or FAILED), project root, original XML input,
normalized output, SHA-256 sidecar output. Parent output directories must
already exist. The converter never invokes Checkstyle/PMD, opens source files,
downloads tools, publishes Maven session receipts or rewrites source.

The original producer alone may authorize COMPLETE after verifying its
execution, chosen rules, suppression policy and full source coverage. Valid
XML and the resulting SHA-256 alone do not certify those conditions.

## Report semantics and donors

The normalized v1 format stores rule identifier, INFO/WARNING/ERROR severity,
canonical project-relative path, numeric line/column and original normalized
message encoded as unpadded URL-safe Base64. Rows are sorted by path, line,
column, rule, severity and message; duplicate normalized findings are refused.

Checkstyle's XML file/error tags provide source rule, severity, line, optional
column and message. error/warning/info are mapped to ERROR/WARNING/INFO.

PMD's original 2.0 report namespace and legacy unqualified namespace are
supported for file/violation tags. ruleset.rule identifies a rule when the
ruleset is present. Priority mappings are deliberately explicit: 1 and 2
are ERROR, 3 WARNING, 4 and 5 INFO. This is the adapter's choice, **not**
a PMD severity assertion. Original suppressed-violation rows are not
reintroduced. Processing/configuration errors prohibit COMPLETE; with a
PARTIAL or FAILED source status, bounded project-wide error findings record
that the original analyzer failed. Original provider logs remain authoritative.

This milestone does NOT convert SpotBugs XML, CPD XML, Semgrep JSON or SARIF.
Their schema-specific adapters require separate tests and licensing review.

## Defensive boundary

* Original XML and output each <=16 MiB, <=65,536 normalized findings and
  <=16,384 characters per row.
* DTD, external entities, instructions, unexpected XML nesting/namespaces,
  invalid severity/priority/location, unsafe/outside-project source paths,
  malformed UTF-8 and duplicate rows fail closed.
* CLI requires project-local regular non-symlink XML input; no source files
  are opened. Outputs must remain under an approved project root, without
  symlink ancestors. Outputs use atomic replacement and a SHA-256 sidecar.
* The digest proves byte identity only. It cannot authenticate the tool that
  ran, project source completeness, or authorize cache hits or goal skipping.

Maven's M3PluginEvidenceService continues to own source-bound session
publication. A qualified original Mojo adapter may explicitly publish a
receipt for the produced v1 bytes. Gradle's separate m3NormalizeAnalyzerXml
task invokes this very same JAR with a user-pinned JAR digest; it does not
copy the converter or embed Maven.

The hermetic test main covers real XML shapes, stable sorting, PMD namespace,
suppression/error behavior, XML entity refusal, path traversal, duplicates,
output SHA-256 and invalid inputs. Actual Maven lifecycle, external analyzer
binaries, full cross-platform Gradle TestKit and license checks remain
independent release gates.


## SpotBugs original BugCollection XML

The opt-in SPOTBUGS format consumes the original BugCollection report,
not SpotBugs' alternative Maven xdoc XML layout. It uses the same checked
Java 17 CLI, Maven normalize-xml goal, canonical M3 v1 report writer and
SHA-256 sidecar as Checkstyle and PMD. For a separate Gradle build, set the
existing m3NormalizeAnalyzerXml format property to SPOTBUGS; the original
Maven converter JAR remains an explicitly SHA-256-pinned external provider.
No SpotBugs engine, JVM bytecode analyzer or dependency is embedded in either
build system.

The adapter retains each original BugInstance type as its rule ID. It maps
the original SpotBugs priority to the normalized display severities:
priority 1 -> ERROR, priority 2 -> WARNING and priority 3/4 -> INFO.
This is an **adapter presentation mapping**, not a replacement for the
original SpotBugs filter threshold, detector severity, rank or build verdict.

The original SourceLine sourcepath and start attributes are retained as a
logical location. A primary=true annotation wins when provided; otherwise a
direct BugInstance source annotation takes precedence over nested Class,
Method and Field annotations. When no source path is present, the bug remains
a **project-wide finding (empty path, line 0, column 0)**; the adapter never
fabricates a class-name-derived path. The XML sourcepath may be relative to
SpotBugs' source directory rather than the Maven/Gradle project root. Do not
assume that it locates a real source file without the original source-root map.

Existing ShortMessage/LongMessage text is bounded; LongMessage wins.
Without either, a short message based on the original bug type is used.
Existing user review annotations, including NOT_A_BUG, do not automatically
disappear: their treatment belongs to the original SpotBugs plugin/filter
and report settings. Analyzer Errors or missing-class evidence prohibits
the caller from labeling the report COMPLETE; with PARTIAL/FAILED status it
creates a project-wide processing-error finding. No original analyzer error
is silently converted into a clean result.

Strict streaming StAX parsing refuses DTD, entities, foreign namespaces,
malformed priorities/error counters/source paths, nested or unknown bug
collection sections, overlarge report depth/events/messages and duplicate
canonical findings. SpotBugs report metadata, such as Project, BugPattern,
BugCode, BugCategory, FindBugsSummary and History, is traversed without
executing or interpreting the original analysis.

The bounded SpotBugs parser is based on the upstream SpotBugs
spotbugs/etc/bugcollection.xsd and published original sample
spotbugs/src/sampleXml/analysisResultsWithFilterAndUserAnnotations.xml.
Its source and rules remain upstream; no LGPL SpotBugs engine code has been
copied. Hermetic fixtures exercise real BugCollection annotation shape,
primary source selection, original priorities, missing location, processing
errors, unsafe paths, DTD refusal and stable v1 bytes. Actual original
SpotBugs version/runtime differential verification remains a release gate.
