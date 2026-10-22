<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# M3JDK21 project watch and shared compiler facts

This fork keeps the normal Maven lifecycle and compiler-plugin ownership. Opt in to continuous execution with `-Dm3.watch=true` and explicit goals such as `verify`. The running SDK must contain the named `com.m3.file` module, resolved with `--add-modules com.m3.file`. Shared compiler receipt production uses `-Dm3.ast.buildFacts=true` and the original builtin M3AST plugin in the selected M3JDK21 compiler.

**Current correctness boundary:** every watched generation uses `clean` followed by the requested lifecycle. M3AST facts are PARTIAL, so they cannot certify arbitrary plugin skipping, complete Java dependency closure or generated-output ownership. This fallback prevents timestamp-only compiler no-ops and stale classes after source removal. The original request remains unchanged. Normal invocations without `m3.watch` retain the previous execution path.

| Property | Default | Meaning |
|---|---|---|
| `m3.watch.inputs` | `.` | Comma-separated paths under the discovered root |
| `m3.watch.excludes` | empty | Explicit output paths; custom Maven output directories require these |
| `m3.watch.ignoredDirectoryNames` | `target,.git` | Excluded path-component names throughout the root |
| `m3.watch.timestampChanges` | `true` | Count timestamp changes in addition to SHA-256/size changes |
| `m3.watch.reconcileSeconds` | `30` | Complete reconciliation interval, 1 through 86400 seconds |
| `m3.watch.mode` | `AUTO` | Existing shared selector policy: AUTO, FULL or INCREMENTAL |
| `m3.watch.fullFileCount` | `-1` | Affected-source count threshold; -1 disables |
| `m3.watch.fullPercent` | `-1` | Affected/live-source percentage threshold; -1 disables |
| `m3.watch.maxBuilds` | `0` | Zero runs continuously; positive bounds completed attempts |
| `m3.watch.idleSeconds` | `0` | Zero waits indefinitely; positive bounds idle waiting |

Safety fallback takes precedence over the requested mode and cost thresholds. The log reports total files, sources, direct changes, affected and selected sources, and the reason. Native source-selective scheduling is not claimed by this version.

The loop stores its last successful M3File ledger in `.m3-watch/state.bin` and uses its existing lock/atomic persistence. Failed builds remain pending. Edits during execution remain pending when they are still observable at the post-build reconciliation; a change-and-restore (ABA) sequence of mutable inputs is not guaranteed to be detected. Startup `.mvn` edits require a restart; a failed initial build does not make unchanged startup files appear changed. The loop renews requests, repository caches and Maven sessions while preserving profile/project activation strength, toolchains, settings, parallelism and model-build policy. A preset embedding MavenProject is refused because it cannot be reloaded safely.

The compiler hook appends fresh invocation-bound fact arguments to exactly one `-Xplugin:M3AST` argument. It preserves other flags and fails explicit contradictory opt-ins or an incompatible compiler. Fixed-path old receipts cannot certify a new invocation. Compiler facts are produced by the existing compiler task, never by another AST parser.

Inputs outside the root and arbitrary plugin/external/environment inputs are not fully covered. The watcher observes saved filesystem state; it does not build unsaved editor buffers or provide a filesystem transaction. Recipe and evidence authority is `hsoliwal/com.synexia`, crate `synexia-openrewrite-recipes/recipe-crates/m3-project-delta-20261021`.
