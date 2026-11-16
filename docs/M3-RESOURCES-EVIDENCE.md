<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Resources execution evidence

The optional Resources producer observes the original `maven-resources-plugin`
through Maven's existing execution listeners and failure event stream. It does not
replace the plugin, change its configuration, select modules, skip goals, restore
outputs, or delete stale resources.

Enable it for an ordinary invocation:

```sh
mvn -Dm3.resources.evidence=true process-resources
mvn -Dm3.resources.evidence=true process-test-resources
```

The default is disabled. User properties take precedence over project properties,
then system properties. The supported goal names are `resources`, `testResources`
and `copy-resources`; an unrecognized configured implementation is left unchanged
and produces no receipt. The observer uses public configured-Mojo getters, so new
or replaced implementations must remain compatible with those getters.

## What an observation means

`ResourceObservation.SCHEMA` identifies `maven.resources.observation.v1`. This
immutable API value records ordered resource declarations, the configured output
directory, an observed-configuration digest, the public resource-skip getter, and
actual before/after file inventories. Files retain their SHA-256, size, observed
modification time and POSIX rwx mode where available. Missing roots and directories
are represented explicitly. Property maps and source contents are not serialized.
Configured paths, target paths and patterns are retained, including interpolated
configuration values.

The source inventories cover the observed roots; they do not establish the plugin's
selected membership. The output directory can contain compiler outputs and stale
files. Observation grants no exclusive output ownership. All v1 observations are
`PARTIAL` and carry explicit limitation reasons. A successful goal return may mean
the goal skipped work. In particular, the separate `testResources` skip field is
not exposed by its inherited public resource-skip getter and remains unobserved.

Filtering, private implementation state, custom filtering components, dynamic
properties and plugin dependency replacement are not certified by this schema.
Filesystem scanning is not an atomic snapshot. A configuration digest is evidence
identity, not a universal cache key or a proof of complete goal inputs.

Resources versions can behave differently. For example, Resources/Filtering 3.3.1
can leave a newer destination's existing bytes in place; a standalone build can
retain an output after its source is deleted. The observer records actual files and
preserves that behavior. It cannot turn a successful resource goal into proof that
its outputs match a clean build.

## Native Java API

The producer publishes a normal `M3PluginEvidenceService.Receipt`, using only the
`RESOURCES` capability. Its identity includes the project, plugin version, goal,
execution ID, observed context digest and a positive session generation. Consumers
can discover those identities through the existing session service:

```java
M3PluginEvidenceService evidence = session.getService(M3PluginEvidenceService.class);
for (M3PluginEvidenceService.Identity identity : evidence.identities(
        session, ResourceObservation.SCHEMA, M3PluginEvidenceService.RESOURCES)) {
    Optional<ResourceObservation> observation = evidence.findResourceObservation(session, identity);
}
```

Discovery returns an immutable snapshot from the existing bounded registry, ordered
by the registry's exact identity comparison. It performs no file reads and is not
evidence validation. A receipt can be revoked or its file changed after discovery;
consumers must use the typed reader. Older service implementations inherit an empty
discovery result without having to implement a new abstract method.

The typed reader uses the existing authenticated `read` operation to obtain an
immutable byte snapshot, then verifies the decoded schema and embedded context.
It does not reopen the receipt's path. Unknown schema, truncation, invalid
counts, changed bytes, mismatched context and trailing data are refused. The format
is a bounded versioned binary encoding and does not use Java object serialization.
The read/write methods leave caller-owned streams open.

The receipt file alone is a payload; its invocation identity is retained in the
session receipt registry. A historical payload has no automatic admission in a
later session. There is no goal-reuse or output-restoration API in this change.

## Lifetime and resource bounds

Only successful original goal returns publish evidence. Both the original failure
callback and Maven's existing `MojoFailed` event clear pending observations; checked
Mojo failures can take the latter path. Pending observations are session-scoped and
matched by the original execution/instance. Parallel projects do not share a mutable
current invocation.

Evidence collection is bounded to 128 roots, 4096 observed entries per phase,
64 directory levels, 64 MiB per file and 256 MiB read per phase. The encoded payload
is at most 8 MiB. A session permits at most 64 pending observations and 512 reserved
generations. Reaching a limit refuses evidence; it does not change the original
goal's outcome. No performance improvement is claimed by this observer.

Successful payloads are retained under the project's configured build directory in
`m3-resources-evidence`. The evidence destination must not overlap an observed input
or output tree. Symbolic paths, unsupported filesystem entries and unstable file
reads refuse observation. Paths containing a missing ancestor followed by `..` are
also refused; normalization must not change the filesystem location being observed.
Evidence failures emit at most three fixed warnings per
session; exception messages, property values and secrets are not logged. A failed
publication removes only the producer's unpublished temporary file.

The operator retains normal ownership of the checkout and build directory. These
checks are not a sandbox against another process concurrently replacing filesystem
ancestors. Normal Maven clean operations own removal of the build directory.

## Qualification and provenance

Canonical recipes and source-sealed fixtures remain in Synexia under
`com.synexia.m3.ProjectDelta20261021`, dataset `resources-evidence-20261116`.
Maven receives executed source/test/documentation postimages. Product unit tests
exercise the real public codec, session service and listener callbacks; the private
qualification also compares instrumentation-disabled/enabled original Resources
executions. Qualification records distinguish those native runs from mocked-session
unit fixtures and from Maven/OpenRewrite recipe execution.

The new implementation uses Java and Maven's existing services. No new runtime
library, parser, plugin executor or scheduler is introduced. Original Apache Maven
and Maven Resources/Filtering source and license references are retained in the
canonical donor review. Existing public signatures remain present; the typed read
method and observation value are additive experimental API.
