<!--
SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# Continuous builds with M3File

The Maven fork can watch saved project files and run the existing Maven lifecycle when
their contents change. Optional batching combines a burst of edits into one pending
generation. The first build starts immediately, and only one lifecycle runs at a time.

## Requirements

Use a built installation of this Maven fork and an M3JDK21 installation containing
the current `org.m3jdk.file` module (which exports `jdk.m3.file`). Resolve it in
the JVM running Maven with `--add-modules org.m3jdk.file`, preserving other JVM
options. The older `jdk.m3.file` and `com.m3.file` named modules remain compatible;
an explicitly exported `java.base/jdk.m3.file` package is also recognized.
Check `mvn --version` and configure the appropriate JDK/module path.

The ordinary bootstrap Maven used to build this repository does not acquire this feature
from a project property. The enhanced Maven executable must be running. Watch mode refuses
an unavailable SDK and requires explicit goals, such as `verify`.

## Run a watched build

With the enhanced fork on `PATH` and the SDK module enabled:

```sh
mvn -Dm3.watch=true -Dm3.watch.quietMillis=250 -Dm3.watch.maxBatchMillis=1000 verify
```

The same Maven arguments work in PowerShell:

```powershell
mvn.cmd '-Dm3.watch=true' '-Dm3.watch.quietMillis=250' '-Dm3.watch.maxBatchMillis=1000' verify
```

In this fork's source checkout, the [build entry points](M3-BUILD.md) can select an installed
enhanced fork through `M3_MAVEN` or `-Maven` and forward those properties. For example:

```sh
M3_MAVEN='/path/to/enhanced-maven/bin/mvn' sh ./m3-build.sh verify \
  -Dm3.watch=true -Dm3.watch.quietMillis=250 -Dm3.watch.maxBatchMillis=1000
```

Use ordinary Maven project, profile, settings and toolchain options to choose the build
scope. Each watched execution receives a fresh request, repository cache and session.

## How batching works

After the initial build, the example waits for 250 milliseconds without a new input
generation, subject to a fixed 1000 millisecond batching limit. Every newly offered
generation replaces the pending snapshot and restarts the quiet period. It does not move
the fixed maximum deadline. Empty polls do not restart either deadline, and a snapshot
returned by a late poll is retained.

Changes observed during an active build remain pending for a later execution. A failed
build retains the last successful checkpoint. An interrupted build reports interruption
and does not acknowledge the pending snapshot, even if its execution callback returned
an otherwise successful result. The M3File watcher verifies the latest offered snapshot
again before committing it.

With `m3.watch.quietMillis=0`, later builds retain immediate scheduling. Both batching
values accept integers from 0 through 60000. A positive quiet period requires a maximum
at least as large. The maximum is inactive when the quiet period is zero. These values
bound additional scheduling wait; filesystem reconciliation, hashing and build execution
can take longer.

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `m3.watch` | `false` | Enable continuous execution. |
| `m3.watch.quietMillis` | `0` | Quiet period after the latest input generation. |
| `m3.watch.maxBatchMillis` | `1000` | Fixed maximum additional wait for a pending batch. |
| `m3.watch.inputs` | `.` | Comma-separated input paths under the project root. |
| `m3.watch.excludes` | empty | Explicit paths to exclude, including custom output directories. |
| `m3.watch.ignoredDirectoryNames` | `target,.git` | Excluded directory names throughout the root. |
| `m3.watch.timestampChanges` | `true` | Include timestamp changes alongside content and size changes. |
| `m3.watch.reconcileSeconds` | `30` | Full filesystem reconciliation interval, from 1 to 86400 seconds. |
| `m3.watch.mode` | `AUTO` | Shared impact-selector policy: `AUTO`, `FULL` or `INCREMENTAL`. |
| `m3.watch.fullFileCount` | `-1` | Affected-source count threshold; `-1` disables it. |
| `m3.watch.fullPercent` | `-1` | Affected/live-source percentage threshold; `-1` disables it. |
| `m3.watch.maxBuilds` | `0` | Bound completed build attempts; zero continues watching. |
| `m3.watch.idleSeconds` | `0` | Bound idle waiting; zero waits indefinitely. |

## Build correctness and scope

**Each watched generation currently runs `clean` followed by the selected lifecycle.**
The available M3AST facts have partial coverage and cannot yet prove complete Java
dependency closure, annotation-processor inputs or generated-output ownership. The
conservative clean rebuild handles same-timestamp source edits and removed-source
outputs. Requested impact modes and thresholds retain this fallback. Batching reduces
the number of scheduled executions for a burst; it does not establish safe per-file
compilation, plugin skipping or a measured build-time speedup.

Every observed `.mvn` startup-configuration change requires restarting Maven, including
a change seen while collecting a batch. Inputs and source roots must stay within the
watched root. Custom output directories need explicit exclusions. The successful checkpoint is stored through the original M3File ledger in
`.m3-watch/state.bin`. This source-owned ledger is distinct from the opt-in
Maven task receipts in `.m3/module.m3db`. The watcher still reads and hashes
input bytes during reconciliation; it cannot authorize cross-process cache reuse
or arbitrary Mojo skipping without independent, complete build evidence.

The watcher observes saved filesystem state. It does not capture unsaved editor buffers,
all external/environment inputs, or every change-and-restore sequence during a build.
The filesystem is not a transaction. A preset embedding `MavenProject` is refused because
it cannot be reloaded safely.

The original implementation is
[`M3ProjectWatch`](../impl/maven-core/src/main/java/org/apache/maven/M3ProjectWatch.java),
with its product tests in
[`M3ProjectWatchTest`](../impl/maven-core/src/test/java/org/apache/maven/M3ProjectWatchTest.java).
Canonical recipes and execution evidence remain in `hsoliwal/com.synexia`, under
`synexia-openrewrite-recipes/recipe-crates/m3-project-delta-20261021`. Consult the receiving
PR for the current native Maven/SDK, platform and full-reactor validation status.
