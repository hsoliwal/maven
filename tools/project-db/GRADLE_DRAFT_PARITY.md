<!-- SPDX-License-Identifier: Apache-2.0 -->
# Maven-side source-built acceptance for Gradle draft #71–#73

## Why this check belongs on Maven

On 22 November 2026, the accessible GitHub Actions run inventory for `hsoliwal/gradle` showed no runs for new Gradle PRs #71–#73; the repository's observed Actions history ended in October 2026. The accessible `hsoliwal/maven` Actions system is active. Absence of checks must **not** be interpreted as success or explained away by a green Git commit status.

The new `.github/workflows/m3-gradle-draft-parity.yml` is an independent, opt-in, CI-only **Maven-owned proof lane**, not a second scheduler, Gradle plugin implementation or native language execution engine. It uses immutable published Gradle draft heads:

| Consumer | Exact Gradle commit | Source-owned proof |
| --- | --- | --- |
| Gradle #71 — Clang `compile_commands.json` exporter | `a5b5b0a1b297f54d7782ae306e452aff59354220` | Maven #255's original `PolyglotCli llvm-compile-db`, three fake and two real TestKit cases |
| Gradle #72 — Derby `ext-derby-v1` | `62783f252977c7dfc7fcd4a568dc4f7db2fdc817` | Maven #235/#236 JDBC, rollback/crash/restart and four Gradle TestKit cases |
| Gradle #73 — project-DB nine-case gate | `3510a6c277efe7b9b1c3a365325d42728959dee6` | Maven #246's original secure Java17 `current-nine` receipt |

The original Maven producer source is fixed at **merged** `master@dc9c0fcdffe89c2b0388c13612ffc197efb37cbb`, which contains all three Maven-owned capabilities. This is a *newer unified Maven source test* and is not presented as a rerun of each PR's earlier historical exact source commit; those original tests remain separately versioned.

## Evidence protocol

The producer runs on **Temurin Java 17 and 21**. It executes the actual original Maven plugin `install`, `-Pm3-derby verify` (requiring real native Derby JDBC and restart/crash contracts), and original `project-db verify`; stages the two built Maven JARs, complete optional Derby runtime JAR closure, original LLVM C17 fixtures and the original secure project-DB JUnit verifier; then emits a `M3-GRADLE-DRAFT-SOURCES-V1` receipt bound to the exact Maven commit and JDK plus an SHA-256 seal over every staged file. No driver/compiler is downloaded by Gradle.

The consumer matrix consists of **three isolated slices per JDK**. Each uses `actions/checkout` with one literal, immutable Gradle PR-head SHA and the original Gradle wrapper/source, never a branch name. SHA-256 of all received files and the matching source/JDK receipt are rechecked before TestKit. The CI executes:

- #71: three fake Maven + two real source-owned Clang database TestKit cases, no Clang output, same-byte fixed-point checks, invalid/foreign receipts and digest refusal
- #72: four real Derby TestKit cases including missing optional driver, live sealed-image content reuse, changed output/input, wrong driver bytes and linked directory refusal
- #73: exactly nine current original project-DB TestKit cases; Maven's own Java17 secure XML verifier with `current-nine` mode admits only the expected identities, no skipped/errors/failures, and rejects DTD/external entities

All JUnit/HTML artifacts are retained on failure, and any producer failure prevents consumer admission. `@UntrackedTask`, manifest, Maven storage and original source ownership remain unchanged. No Gradle `build`/`check` hook or cache admission is inserted.

## Qualification boundary

This is executable CI **source**, not a passed runtime result. Verify the exact PR-head workflow run, source/JDK receipts, JUnit report names and counts, and Maven producer logs before lifting either draft gate. Maven main's separate Apache RAT checks may fail, but an unrelated failure does not prove this source-specific lane green or red; inspect the particular job.

The canonical Synexia recipe-first standard is preserved: no production code is generated in this CI-only PR, and the existing Gradle consumer OpenRewrite recipes remain separate. Actual SDK replay/fixed point, source-built custom LLVM fork ancestry, Windows/macOS, M3JDK21 native runtime and human DCO/license review remain independent **HOLD** gates.

History audit scope: relevant merged Maven #143/#147/#184/#228/#235/#236/#246/#247/#250/#253/#255/#259 and Gradle #58–#73; the full all-ref/all-tag/lost-optimization scan is **not** claimed complete. Never rebase or overwrite `com.synexia/develop`.
