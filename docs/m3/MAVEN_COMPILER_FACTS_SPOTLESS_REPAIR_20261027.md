# Maven 4 additive source preservation — Spotless admission receipt (2026-10-27)

Scope: PR #14 only; do not rewrite master, do not rebase or squash, and retain both upstream Maven 4 and M3 fork histories.

Original PR head: `ce4530f12c60c00daded087c065d80b316f2b76d`.
Preservation ref: `preserve/pr14-before-spotless-repair-20261027`.
The original Java CI failed `maven-impl` Spotless for the two existing `DefaultCompilerFactsService` production/test owners.
One-shot workflow originally failed because it invoked missing `./mvnw`, exit 127.
Connector-authored invocation fix: `9f682f0d90ff5ce5c07ae0487c5dbfdd1277f3b3`.
One-shot formatter run: https://github.com/hsoliwal/maven/actions/runs/40435009977 — success.
Exact formatter commit: `e6d56bbc490309c1f8557f60057f502576b1bf00`.
The formatter changed only two compiler-facts Java files and removed its one-shot workflow. No other source file was reported changed.

**Admission remains open:** full Maven reactor, integration tests, additional JNI/M3 watcher parity and current-head GitHub CI must execute and pass. No canonical master merge is authorized solely by this receipt.

Source/provenance: https://github.com/hsoliwal/maven/pull/14
