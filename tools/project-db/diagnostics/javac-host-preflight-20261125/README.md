<!-- SPDX-License-Identifier: Apache-2.0 -->
# Original Maven javac hosted JDK preflight — source-bound receiver (DRAFT/HOLD)

This directory is an **exact Java-source and source-hash diagnostic receiver** of
canonical [Synexia draft PR #19365](https://github.com/hsoliwal/com.synexia/pull/19365)
under [issue #19317](https://github.com/hsoliwal/com.synexia/issues/19317).
It is not a new Maven provider, recipe owner, compiler, cache authorization,
public API, CI acceptance result or production implementation.

## Frozen source and provenance

- Canonical source: `hsoliwal/com.synexia` PR head
  `f6bd4f672794840e58be8c85d356f441c41854c6`.
- Java source Git blob: `1175f31aebbe35b428e894b9422374b7b22a3451`;
  SHA-256 `752556842643fcf853e86e03f9916515b647f2ffa722e4c9fae92d52bf80ecd1`.
- Source seal Git blob: `74c62ae24cd039f8f796415f52108fc77bfce0bd`.
- Original Maven owner SHA `53d7bd782077968e596a54cb7948ea22810734c3`
  and module tree `3652731870e840170acca60acfeea298cc9c85fd`.
- License: Apache-2.0 for the copied diagnostic, preserving SPDX provenance.

## Hosted execution and limits

The additive [diagnostic workflow](https://github.com/hsoliwal/maven/blob/codex/hosted-javac-probe-20261125/.github/workflows/m3-javac-host-preflight-20261125.yml)
uses Ubuntu 24.04 with Temurin 17 and 21. It source-pins and builds the **original
Maven engine**; compiles the unchanged Synexia Java diagnostic against that
engine; runs its 9 bounded synthetic safety controls; and reads the selected
JVM's original Maven `JdkReuseInputs` eligibility and JDK link facts. Host
variables are emitted by **name only**, never their values. No Maven or Gradle
production classes, JDK installations or public APIs are changed. An `always()`
artifact retains the output even when a step fails.

This can establish runner preflight but not the Gradle TestKit JVM's exact
runtime, source/receiver fixed point, or a valid cache hit. The prior
[failed hosted provider run](https://github.com/hsoliwal/maven/actions/runs/45394442503)
remains the controlling negative product acceptance result. No workflow success
should be represented as full Maven–Gradle convergence.

## Local preparation and HOLD

The source and source-seal Git blobs are byte-identical to the Synexia owner;
`javac --release 17` and the original Maven JAR's Java 9-case self-test have
passed locally. YAML structure and bounded output/refusal conditions were
checked. The full Synexia Maven/OpenRewrite SDK, serial/two-worker replay,
receiver check/apply/replay, history ancestry and native hosted Gradle cache
matrix are **NOT_RUN or PARTIAL**. This diagnostic receiver is intentionally a
**draft/HOLD**, not a merge or product promotion. Never rebase `develop`.
