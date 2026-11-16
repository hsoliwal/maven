<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# M3 Maven external owner delta exchange (experimental)

The immutable `M3ExternalBuildService` added by Maven PR #78 classifies caller-owned
incremental build metadata. The companion `M3ExternalBuildWire` encodes **only** the
host's changed paths, kind and completeness claim. It is not a second filesystem
watcher, compiler, resolver or build-cache controller.

## Wire format v1

Canonical UTF-8 (no BOM), LF only, final LF, at most 8 MiB:

```text
M3-IDE-DELTA-V1
provider	eclipse.m2e
project64	ZXhhbXBsZQ
kind	INCREMENTAL
complete	true
change	ADDED	src/main/java/A.java
change	MODIFIED	src/main/java/B.java
```

`project64` is canonical unpadded Base64URL of the UTF-8 project identifier.
`provider` is the adapter identifier, such as `eclipse.m2e`, `vscode.java`,
`visualstudio.msbuild` or `gradle`. `kind` is `INCREMENTAL`, `AUTO`, `FULL`
or `CLEAN`. For FULL/CLEAN, completeness must be `false`. Each change kind is
`ADDED`, `MODIFIED`, or `DELETED`. Rows must be unique and sorted strictly by
project-relative path. Absolute paths, traversal, colons, backslashes,
noncanonical encodings and control characters fail validation.

This schema intentionally excludes toolchain/model/dependency/source-root hashes
and content digests: an editor change notification does **not** establish them.
The host ID and project ID must be scoped by the receiving workspace; a
project name alone is not globally unique across Eclipse workspaces.

## Consumer API

```java
byte[] sourceOwnerPacket = ...; // from the selected IDE's source-owned delta
M3ExternalBuildWire.Envelope change = M3ExternalBuildWire.decode(sourceOwnerPacket);
M3ExternalBuildService.Generation previous = ...; // optional, last verified baseline
M3ExternalBuildService.Generation current = ...;  // separately verified host build facts
M3ExternalBuildService.Request request = M3ExternalBuildWire.bind(change, previous, current);
M3ExternalBuildService.Assessment assessment = session.getService(M3ExternalBuildService.class).assess(request);
```

The adapter cannot invent `current.modelSha256()`, `current.toolchainSha256()`,
`current.dependenciesSha256()` or `current.sourceRootsSha256()`. When facts are
unknown, mark the generation as incomplete or omit the baseline so Maven
reconciles ordinarily. `bind` explicitly ANDs the packet's completeness flag
with the host generation's separately provided completeness flag. A foreign
provider/project or gap cannot be treated as a valid continuation.

`HOST_DELTA_AVAILABLE` means only that a host may reuse its **own** incremental
builder state. Neither this packet nor any assessment can skip Maven
compilation/tests, validate output completeness, or authorize build-cache reuse.
Generated outputs, deleted sources, build-file changes and missing baselines
require normal reconciliation in the owning host and/or Maven lifecycle.

## Producers

The opt-in Eclipse m2e participant lives in
[hsoliwal/m2e-core PR #2](https://github.com/hsoliwal/m2e-core/pull/2).
Its `M3WorkspaceDelta.Snapshot.toExchangeBytes()` creates byte-identical packets
without linking Maven 4 into the existing Maven 3 m2e embedder. It does not
serialize ASTs, issue compiler requests or create persistent file ledgers.

Visual Studio Open Folder and MSBuild command integration is separately proposed
in [Maven PR #80](https://github.com/hsoliwal/maven/pull/80). Those commands do
**not** automatically produce workspace delta packets; a future Visual Studio
extension must supply actual owner events to use this wire format.

## Qualification

The standalone JVM checks include exact Eclipse 21 -> Maven 17 packet compatibility,
canonical round trips, malformed UTF-8/paths/encodings, owner identity mismatch,
full-build fallback and the invariant that `authorizesMavenGoalSkipping()` is false.
The full Maven reactor, JUnit/DI, Spotless/Checkstyle, live Eclipse and native
Windows Visual Studio builds remain separate gates. No release readiness is claimed.
