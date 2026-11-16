<!--
SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# M3 Maven Build Inputs Service (experimental)

`BuildInputsService` is an additive Maven 4 session service that consumes **existing, authenticated**
`M3PluginEvidenceService` receipts. It does not replace the Maven Resources Plugin or
Build Helper plugin, copy files, reconstruct file changes, or authorize goal skipping.

A resource-goal producer may publish an immutable manifest using `M3PluginEvidenceService.publish`:

- Identity: exact producer project, `groupId:artifactId`, version, goal, execution, context SHA-256,
  and generation.
- Schema: `m3.resource-io.manifest.v1`.
- Required capability mask: `INPUTS | OUTPUTS | RESOURCES`.
- Manifest receipt: absolute producer-owned file path with canonical lowercase SHA-256 of its **actual bytes**.

## Manifest format

The source-owned file uses strict UTF-8, LF line endings and a final LF:

```text
M3-RESOURCE-IO-V1
role	path	sha256	bytes
I	src/main/resources/a.txt	<64 lowercase hex digits>	10
O	target/classes/a.txt	<64 lowercase hex digits>	10
```

Here `I` means input and `O` means output; rows are ordered first by role, then ascending
logical relative path. An empty manifest (just the two header lines) is valid but **does not
prove** there are no inputs or outputs. No row may have an absolute path, `..`, `.`, empty
segments, backslashes, a colon, or control characters. Each hash is a producer-supplied
claim about the file, not an independently rehashed file digest.

The bounded decoder accepts at most 65,536 rows and the original evidence reader caps
receipt files at 16 MiB. Returned manifests store only parallel arrays of paths, roles,
digests and sizes. `entry(i)` materializes a Java record on demand.

## Example consumer

```java
BuildInputsService inputs = session.getService(BuildInputsService.class);
Optional<BuildInputsService.Manifest> manifest = inputs.read(session, identity);
if (manifest.isPresent()) {
    for (int i = 0; i < manifest.get().size(); i++) {
        BuildInputsService.FileFact fact = manifest.get().entry(i);
        // Diagnostics/provenance only; not a cache/admission decision.
    }
}
```

Missing evidence returns empty; malformed, stale, untrusted or changed receipt bytes fail.
A successful decode authenticates **only the manifest**, not the listed files, completeness,
producer semantics, coverage of generated outputs, or build-cache safety. Producer side
verification of output ownership and changes remains a separate prerequisite.

## Compatibility and verification

This service adds no POM syntax, CLI option or lifecycle binding and retains all existing
Maven Resource/Build Helper goals untouched. `DefaultBuildInputsServiceTest` covers native DI,
authenticated receipts, deterministic ordering, malformed/suspicious paths, byte-count validation,
missing receipts, mutation refusal, and empty manifests. Run the Maven API and implementation
reactor with Checkstyle, Spotless, unit tests and full verification before considering a merge.
