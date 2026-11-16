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
