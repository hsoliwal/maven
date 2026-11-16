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

# M3 Resources operation evidence adapter

Status: experimental and opt-in; executed qualification is recorded separately.

This opt-in Maven 4 plugin extends Apache's released Resources Mojo and delegates
copy operations to the original Maven Filtering implementation. It records the
actual selected copy calls and their unchanged boolean results, publishes an
immutable receipt through the existing M3PluginEvidenceService, and consumes
that receipt through the verified snapshot API supplied by Maven PR #49.

## Pins and ownership

- Maven base: hsoliwal/maven PR #49, commit
  3f00e21566a11859745560be7cc998762db59129, version 4.2.0-SNAPSHOT.
- Resources donor: org.apache.maven.plugins:maven-resources-plugin:4.0.0-beta-2,
  source commit 5c08057fc76dc25bc54faf332baf3af998b89b4a.
- Filtering donor: org.apache.maven.shared:maven-filtering:4.0.0-beta-2,
  source commit 131d0cbb9848e320eccf181c83e9b2557f53880b.
- This artifact: io.synexia:m3-plugin-evidence-maven-plugin:0.1.0-SNAPSHOT.
- Public goal: resources. Evidence activation: m3.evidence.enabled=true.
- Canonical transformations remain owned by hsoliwal/com.synexia's existing
  hash-pinned Java/text recipe engines. No new transformation engine is added.

The module is outside Maven's root reactor. It does not change the existing
Maven 3 Smart Builder artifact, original Resources coordinates, default lifecycle,
Maven core signatures, resolver semantics or compiler-fact ownership. It requires
the pinned M3 Maven runtime; upstream Maven does not provide the M3 service.

## Execution contract

When evidence is disabled, the subclass calls the original Resources implementation
directly. Original skip handling remains in the inherited execute method. There
are no receipt writes on the disabled or skipped path.

When explicitly enabled, the adapter qualifies the default Resources-filtering
and file-filter provider classes, obtains actual project/session/MojoExecution
identities, and installs a locally constructed original filtering engine with a
recording file-filter delegate. The original engine's current nonfiltered-extension
list is copied into this local engine. The original field is restored in finally.
The engine implementation is reused; its object identity is not preserved.

The captured profile requires one resource root, filtering=false, targetPath=null
(an empty targetPath is also unsupported),
includeEmptyDirs=false, fileNameFiltering=false, a non-incremental BuildContext,
and the exact default Resources-filtering and file-filter provider classes.
Source and output trees must not overlap. User filtering components are outside
this first captured profile. Unsupported declared profiles fail before the original
engine starts. Include and exclude patterns remain owned by the original scanner.

A guarded BuildContext delegates to that scanner, then snapshots and validates its
selected file and directory names before the original engine consumes them. It
performs no second scan. This guard is required because the donor may create
parent directories before invoking the file-filter callback. Incremental contexts
are refused because their deletion path bypasses that callback. The donor always
requests its original deletion scanner; this profile requires that scanner to
select no files or directories and refuses any nonempty selection before deletion.
Symlinks, external
input/output paths and overlapping output operations are refused. Validation is
bounded to 1,024 selected files and 1,024 selected directories. File observations
allow at most 32 MiB per file and 256 MiB of cumulative digest reads, including
final revalidation; encoded receipts allow at most 16 MiB.

The adapter preserves the delegate's boolean result as `delegateResult`:

- DELEGATE_TRUE means that the original delegate returned true for that operation.
  Within the qualified default nonfiltering donor, copying uses byte streams even
  when different encodings are declared; the schema therefore validates equal
  source/output digest and size for this result. This invariant is donor-specific.
- DELEGATE_FALSE means that it returned false. It does not establish whether
  a physical write occurred, source/output equality, or successful reuse.

The pinned donor can return false after creating or changing a small file in
CONTENT mode: it samples a buffered output stream's modification flag before
closing that stream. The adapter preserves this behavior. Its report counts
`delegateTrueCount` and `delegateFalseCount` are counts of returned values;
they are never physical-write counts. Output hashes and sizes are independent
observations taken after delegation returns.

Source bytes are hashed before and after delegation. Output bytes are hashed
after delegation. Final validation after the original Mojo completes rejects
changed source/output observations. A matching pre-existing output is recorded
with the actual delegate result.
Observation or publication failure may occur after original copies have completed;
this adapter does not roll back donor effects or claim transactional execution.

Evidence describes recorded copy operations. It is not a complete resource-tree
inventory: directory metadata and other donor effects are not captured. The
qualified profile excludes incremental stale-output deletion. Completeness and
cache eligibility are
always false in v1. No goal is skipped, no output is restored, and no stale file
is deleted by the adapter itself.

## Identity, publication and reports

The receipt identity names the actual adapter Mojo, not the original Resources
plugin. Donor coordinates/source pins are separate provenance metadata. Exact
project/plugin/version/goal/execution identity and invocation generation are
obtained from the active invocation. Context fingerprints cover the declared
observation, not a complete build/cache context. Malformed Unicode configuration
is refused before UTF-8 framing; it is never silently replaced.

One invocation produces one envelope with INPUTS | OUTPUTS | RESOURCES (7).
Publication uses the original session service. Consumption uses read(...).bytes()
from PR #49, never find followed by reopening the receipt path. The registry's
identity conflicts, capability masks, receipt count and byte limits are retained.

The bounded binary receipt schema m3.resources.operations.v1 uses explicit UTF-8
length framing and fixed field order. Its decoder rejects wrong magic/version,
oversized lengths/counts, malformed UTF-8, invalid hashes, duplicate destinations,
invalid paths, trailing bytes and mismatch with the outer receipt identity.
Operation entries are sorted by destination/source identities because duplicate
destinations are disallowed. No timestamp is serialized.

A deterministic UTF-8 JSON capability report exposes the identity, schema,
adapter/donor attribution, completeness=false, cacheEligible=false, operation
counts and input/output observations. Known means verified supported bytes, not
trusted producer semantics or permission to reuse. Missing evidence produces
UNAVAILABLE with null counts; an unsupported profile or invalid evidence fails
explicitly and is never rendered as a successful empty observation.

Receipt and report paths are immutable, content-bound, project-local files under
the project's build directory in m3-evidence. They must be outside every source
resource root and the Resources output tree. Repeating exactly the same bytes at
the same destination is idempotent; conflicting existing content is refused.
There is no root/home write, overwrite, cleanup or hidden generation bump.

Path checks include ancestor symlinks. The workspace must be exclusively owned;
these checks do not claim a race-proof sandbox against concurrent hostile changes.

## Usage and qualification boundary

Build and invoke this plugin with the pinned fork Maven distribution after its
API and implementation artifacts have been installed in the selected local
repository. Upstream Maven distributions do not supply M3PluginEvidenceService.
Keep the build repository and temporary directory explicit for isolated runs.

```text
<fork-mvn> -f tools/m3-plugin-evidence-adapter/pom.xml checkstyle:check
<fork-mvn> -f tools/m3-plugin-evidence-adapter/pom.xml compile
<fork-mvn> -f tools/m3-plugin-evidence-adapter/pom.xml test
<fork-mvn> -f tools/m3-plugin-evidence-adapter/pom.xml install
<fork-mvn> -Dm3.evidence.enabled=true io.synexia:m3-plugin-evidence-maven-plugin:0.1.0-SNAPSHOT:resources
```

The last command runs in the consuming project with its normal resource
configuration. The enabled profile requires one declared nonfiltering resource
root and the path restrictions described above. Configure `changeDetection` using
the donor's normal plugin configuration; `CONTENT` is appropriate when qualifying
same-size source changes with preserved timestamps. Disabled evidence is the
default. The inherited `maven.resources.skip` property retains donor skip handling.

Successful enabled execution logs the immutable JSON report path beneath the
project's build directory. Report bytes come from the session's verified receipt
snapshot. Separate Maven sessions normally restart the occurrence counter; reuse
of identical immutable artifact bytes does not claim reuse of resource outputs.

This Resources-specific schema remains experimental. A second independent
producer and separate consumer qualification are required before treating it as a
shared execution API or approving any Build Cache reuse decision. This adapter
does not add Enforcer, toolchain selection or archive execution authority.

## Verification order

Run documentation/patch preparation, source lint, compile, unit tests, then actual
Maven runtime qualification. Failures stop the sequence. Repatching resets it to
lint. Keep source pre/post hashes, exact commands, exit codes and stage logs.

The runtime lane must use the packaged plugin in the pinned Maven 4 distribution:
compare ordinary donor output with disabled adapter output, then exercise enabled
capture and verified reporting. Repeat unchanged content, mutate source bytes
without changing timestamps, exercise a pre-existing mismatched output with a
nonwriting copy policy, and refuse unsupported filtering/path configurations.
Stub-only or source-only tests never qualify actual Maven injection or packaging.

No performance claim, cross-platform claim, release claim, or automatic cache
decision follows from this first adapter. Subsequent Enforcer/toolchain/recipe
consumers must retain their own semantic and execution contracts.
