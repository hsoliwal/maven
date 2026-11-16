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


## Original PMD CPD duplication reports

The explicit CPD format accepts **PMD's original pmd-cpd XML** produced
by the CPD xml renderer (namespace
https://pmd-code.org/schema/cpd-report) and the legacy unnamespaced
xmlold renderer. It is a report **adapter**, not a tokenization,
duplication-detection, file-scan or new language implementation.

The upstream PMD 7.3+ report schema groups duplication occurrences as
duplication elements with positive lines/tokens attributes and at
least two nested file locations. The reader accepts all original
occurrences, including repeated occurrences **within the same source file**,
and emits one canonical M3 finding per reported occurrence. The original
path/line/column information comes from CPD's file path and
location attributes. Unknown optional columns remain zero, not fabricated.

Every occurrence uses the additive rule PMD.CPD.Duplication and an
explicit display severity **WARNING**. CPD itself has no native
warning/error severity for duplication groups; WARNING is a UI mapping,
**not** a change to CPD thresholds or original build-policy exit behavior.
The bounded finding message contains the original positive line/token
counts, an occurrence position within the sorted group, and a canonical
SHA-256 group identity derived from sorted locations and those counts.
The original codefragment contents are **never copied into the
normalized report**, protecting source confidentiality and avoiding
unnecessary duplicate text storage.

Original PMD 7.3+ CPD error elements denote recoverable analysis failures.
A caller must not request COMPLETE when errors exist; with PARTIAL or
FAILED status the converter emits a project-wide ERROR finding
PMD.CPD.AnalysisError. The original error messages remain in the CPD
producer's own XML/log, and neither Maven nor Gradle hides them.

The JDK-only, original-owner parser checks XML namespaces, positive and
canonical numeric attributes, nested structure, bounded counts, duplicate
locations, path traversal, strict UTF-8, DTD and external entity refusal.
A CPD group without two distinct locations or the required codefragment
is refused. Its bounded transient location array is sorted in place;
the original source fragment is not retained.

The original Maven CLI and optional normalize-xml Mojo accept CPD,
and the separate Gradle quality bridge reuses the **same pinned adapter
JAR** by selecting CPD on its existing m3NormalizeAnalyzerXml task.
The artifact remains separately versioned and the original PMD Maven/Gradle
plugin remains authoritative for producing the XML and its execution
status. Real PMD CPD CLI parity, Gradle TestKit and Maven 17/21 runtime
qualifications remain distinct release gates.


## SARIF 2.1.0 report import (CodeQL and other original SARIF producers)

The explicit SARIF format accepts a strict, bounded **original SARIF 2.1.0**
JSON report and projects selected result metadata into the unchanged M3
STATIC-ANALYSIS-V1 format. It does not execute CodeQL, Semgrep, Error Prone,
or any other analyzer; the original tool/rule pack, compilation database,
query suite, suppressions and exit policy remain authoritative.

The adapter uses Apache-2.0 Jackson Core 2.22.4 with strict duplicate-key
detection, bounded nesting/string/number fields and a streaming JSON cursor.
Jackson is relocated inside the standalone Maven converter JAR using
Maven Shade; Gradle reuses that **same pinned JAR**. Jackson is not added to
Maven Core or Gradle's task runtime, and no third-party analyzer engine is
copied. License and NOTICE provenance: NOTICE-JACKSON.md.

The original SARIF version must be exactly 2.1.0. For each run, the
original tool.driver.name is retained as a prefix of the rule identifier,
with the original result.ruleId following it. The rule/message/severity
come from original results. Display mappings: SARIF error -> ERROR,
warning -> WARNING, note/none -> INFO; the SARIF default when level is
omitted is warning. A result with kind pass/notApplicable is not emitted
as an issue. Other supported findings remain observational.

The canonical finding uses the **first physical source location** with a
resolvable artifactLocation.uri or artifactLocation.index. Supported
location inputs include project-relative source URIs, approved file: URIs,
run.artifacts indexes, and originalUriBaseIds chains (maximum eight levels).
These are resolved lexically under the explicit project root; remote,
outside-project, unsupported/cyclic source references and malformed
positions are refused. Unlocated results remain project-wide (empty path,
line and column zero), not guessed Java source paths. Other SARIF
locations/code flows and source snippets remain in the original SARIF
rather than being silently projected into duplicate M3 findings.

Original message.text is preferred; message.markdown is used only as
fallback. Text line breaks are normalized without storing source
snippets/fixes. Invalid/missing rule and message data, duplicate
normalized findings and unsupported field shapes fail closed.

The original producer must explicitly attest COMPLETE. A SARIF invocation
reporting executionSuccessful=false forbids COMPLETE, and PARTIAL/FAILED
includes a project-wide SARIF.ToolExecutionError finding. Absence of
failure metadata is **not** independent proof that an original scan finished.

Example after the original CodeQL CLI has already written results.sarif:

    java -cp target/m3-analyzer-report-adapter-0.1.0-SNAPSHOT.jar \
      io.synexia.maven.analysis.NormalizeCli SARIF PARTIAL \
      /source/project /source/project/target/results.sarif \
      /source/project/target/m3-analysis.tsv \
      /source/project/target/m3-analysis.sha256

Maven's original explicit normalize-xml Mojo keeps its previous goal name
for compatibility, but also accepts m3.analysis.format=SARIF. The Gradle
quality bridge's existing m3NormalizeAnalyzerXml task accepts SARIF and
provides the additive format-neutral originalReport property alias.

**Scope limits:** this is not a full SARIF 2.1 validator. SARIF may encode
logical-only locations, ruleIndex-only descriptors, external property
references, multiple locations, explicit sourceRoot mappings, and
message catalogs; unsupported forms are refused or retained only in the
original source report, not claimed fully normalized. Actual original
CodeQL/Semgrep SARIF golden fixtures, shaded artifact execution and
Gradle TestKit/IDE qualification remain mandatory release gates.


## Native Semgrep CLI JSON report import

The explicit SEMGREP format accepts the output of an **already completed
Semgrep CLI scan using JSON reporting**, as described in the original
semgrep-interfaces/semgrep_output_v1.jsonschema specification. Original
Semgrep binaries, rules, target discovery, timeout/exit policy, suppressions
and licensing remain external. This converter does not invoke or copy
Semgrep, download rule packs, rescan source, or apply a Semgrep autofix.

The bounded stream reader is a distinct package-private SemgrepJsonReader,
which shares the same defensive Jackson JSON-parser settings with SARIF
through OriginalJsonStreams: strict duplicate-key checking, maximum
nesting/field-name/string/number length and no JSON object graph. Jackson
Core remains shaded inside this separately packaged adapter only, not
inside Maven Core or the Gradle quality bridge.

Each original result requires check_id, project-contained path, positive
start/end line and col, and extra.message plus extra.severity. Its original
rule ID and start location are kept in M3 STATIC-ANALYSIS-V1; the original
end range remains in source JSON. Metadata, metavars, source snippet lines,
candidate fixes, matching traces, engine implementation details and secret
material are **not copied** into the normalized report.

Semgrep's original severity vocabulary is deliberately displayed as:
- ERROR, HIGH, CRITICAL: M3 ERROR
- WARNING, MEDIUM: M3 WARNING
- INFO, LOW, EXPERIMENT, INVENTORY: M3 INFO

This is a UI projection, **not** a change to original Semgrep severity,
scan exit status, --error policy, rule configuration or validation decision.
Results explicitly marked extra.is_ignored=true are not reactivated as
active findings; their original records remain in Semgrep's own JSON.

The source JSON must contain distinct results, errors and paths fields.
Any original non-empty errors array disallows producer status COMPLETE
even if the tool classified an error as a warning, deliberately failing
closed until original exit and error policies are qualified. When the
producer declares PARTIAL or FAILED, a project-wide ERROR finding
Semgrep.ScanError keeps scan incompleteness visible. Absence of errors in
a well-formed JSON document is not independent proof that every target
or rule was scanned.

Example after running the original Semgrep command with explicitly local
rules and writing target/semgrep-results.json:

    java -cp target/m3-analyzer-report-adapter-0.1.0-SNAPSHOT.jar \
      io.synexia.maven.analysis.NormalizeCli SEMGREP PARTIAL \
      /source/project /source/project/target/semgrep-results.json \
      /source/project/target/m3-analysis.tsv \
      /source/project/target/m3-analysis.sha256

The opt-in Maven normalize-xml Mojo still accepts the historical
m3.analysis.xml property for source report paths; setting
m3.analysis.format=SEMGREP accepts JSON bytes without changing the old
plugin goal, descriptor or original Checkstyle/PMD/CPD/SpotBugs callers.
The corresponding Gradle m3NormalizeAnalyzerXml task selects SEMGREP
and passes the original JSON file through its format-neutral
originalReport alias, reusing the **same installed, digest-pinned JAR**.

No original Semgrep task, CodeQL query, compiler or OpenRewrite recipe
runs automatically. The normalized SHA-256 proves bytes, not tool
execution, target coverage or cache eligibility. The original Semgrep
CLI JSON specification and original rule engine remain with the
Semgrep project; only the external schema fields are used here.
