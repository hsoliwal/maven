<!--
SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->
# M3 Maven IDE delta assessment goal (experimental)

This is a Maven 4-only, **opt-in** standalone plugin, outside Maven's default reactor.
It consumes an original Eclipse JDT/m2e (or other IDE) `M3-IDE-DELTA-V1` packet
and passes its claims to the native `M3ExternalBuildService`. No Maven goals,
files or sources are modified. It performs no source scan, no incremental Java
compilation, no cache admission and no IDE installation.

## Build and invoke

Install the **actual** Maven fork 4.2.0-SNAPSHOT API/implementation before building
this plugin with the fork's Maven executable:

```text
<fork-mvn> -f tools/m3-ide-bridge/maven-plugin/pom.xml verify
<fork-mvn> -f tools/m3-ide-bridge/maven-plugin/pom.xml install
```

After an IDE consumer has **explicitly** written its own source-delta bytes into
`target/ide/changes.ide`, run from the matching project:

```text
<fork-mvn> io.synexia:m3-ide-bridge-maven-plugin:0.1.0-SNAPSHOT:ide-assess \
 -Dm3.ide.packet=target/ide/changes.ide \
 -Dm3.ide.generation=2 \
 -Dm3.ide.modelSha256=<caller-verified-64-lowercase-hex> \
 -Dm3.ide.toolchainSha256=<caller-verified-64-lowercase-hex> \
 -Dm3.ide.dependenciesSha256=<caller-verified-64-lowercase-hex> \
 -Dm3.ide.sourceRootsSha256=<caller-verified-64-lowercase-hex> \
 -Dm3.ide.contextComplete=true
```

For a contiguous known previous generation, additionally supply:
`-Dm3.ide.previousGeneration=1`, four matching `previousModelSha256`,
`previousToolchainSha256`, `previousDependenciesSha256`,
`previousSourceRootsSha256` values, and `-Dm3.ide.previousComplete=true`.
If the prior successful generation is unavailable or incomplete, leave its
arguments unspecified and Maven will conservatively report reconciliation.
All model/toolchain/dependency/root digests come from the original build
owner; **the plugin does not fabricate or authenticate these values**.

The plugin reads the packet only from within the Maven project root, refuses
symbolic links and over-budget files, and delegates strict format validation
to the existing Maven API. It emits only a disposition, reasons and change
count. It does not dump untrusted file names or start any build automatically.

The Eclipse `M3WorkspaceDelta.Snapshot.toExchangeBytes()` method constructs
this packet without a Maven 4 runtime dependency. The original Eclipse
participant currently stores its snapshot in a transient m2e facade property;
a host must **explicitly** export those bytes to an owned project-local path.
There is no default automatic filesystem writer and no invisible IDE-to-Maven
transport. In particular, a complete-looking delta or digest is **not** proof
that existing Maven compiler, test, archive or publication outputs are reusable.

Windows Visual Studio/MSBuild can invoke this Maven goal explicitly in a
user-selected task, but the existing opt-in Visual Studio commands remain
unchanged. Cross-platform native IDE integration, full Maven reactor/JUnit,
OSGi/Tycho and Windows tests are still required for release qualification.
