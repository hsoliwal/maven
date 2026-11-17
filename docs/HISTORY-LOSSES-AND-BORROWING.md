<!--
SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# History losses and borrowing from Gradle

This page records an audit of this fork's commit history, made on 2026-11-17. It covers three things:

- what the fork lost in its own merges;
- which capabilities upstream Maven removed that are worth bringing back as opt-in features;
- what this fork and the Gradle fork (`hsoliwal/gradle`) can borrow from each other.

Every statement names the commit, file or command that backs it. Work that was not executed is marked **NOT RUN**.

## Scope and method

| Item | Value |
|---|---|
| Fork head audited | `284da5bfe279` (#139); `981eaaab6` (#141) adds only CI and recipe files |
| Upstream | `apache/maven` master `68efb356909` is an ancestor of the fork head: 530 fork-only commits and 0 upstream commits missing |
| Merges audited | 152 fork merges since 2026-10-16 up to `284da5bfe`, 0 skipped. A second run over the 35 merges since 2026-11-16 up to `981eaaab6` (which includes #140 and #141) found nothing. |
| Merge audit | `merge_loss_audit.py <clone> HEAD 2026-10-16 <out> --all-merges`. For every merge, it compares the blob ids of the base, both sides and the result. A blobless clone (`--filter=blob:none`) is enough. |
| Follow-up | `merge_loss_verify.py` checks whether each finding's lines or file still exist at the head |
| Triage rule | A file dropped inside a merge, with no later commit that deletes it, is a real loss. A file that a later commit deleted on purpose is not a loss. |
| Gradle side | `hsoliwal/gradle` master `beabedc9cc4a` against `gradle/gradle` master `c2c0eb5a5250`. Its page is `m3/HISTORY-LOSSES-AND-BORROWING.md` in that fork. |

Raw verdicts of the merge audit: `DROPPED_INCOMING` 64, `DROPPED_MAINLINE` 1, `TOOK_INCOMING` 8, `TOOK_MAINLINE` 6. The follow-up turned these into 79 findings: `HEAD_HAS_VERSION` 15, `LOST_FILE` 18, `LOST_NAMES` 1, `MOVED` 5, `RECOVERED` 26, `REWORDED` 14.

## Fork losses

### Real loss: the session-services recipe bundle (restored outside this repository)

Commit `c1afa5f5d749` (2026-10-17, "Preserve plugin session service bindings for shared compiler integrations") added 21 files under `m3/recipe-custody/`. Merge `bd11c44cd98a` ("preserve Maven shared-compiler conflict ancestry") kept the master tree and dropped all of them. No later commit deleted them, and they never reached master. So the evacuation of `m3/` to `hsoliwal/com.synexia` `forks/maven/` (#30, `9d4d961ac` / `7f72d2351`) did not carry them either.

**Restored** in `hsoliwal/com.synexia` PR #18288 (branch `history/restore-losses-20261117`): 20 files, byte for byte, at `forks/maven/<original path>`, and every blob id equals the original. They do not come back to this repository, because #30 moved fork-only `m3/` content out of it on purpose.

| Files | State before the restore |
|---|---|
| `SOURCE_CUSTODY.json`, 8 `evidence/*.json`, the `before` dataset copy of `DefaultMavenPluginManager.java`, the hash-pinned crate `m3-maven-qualified-session-services-20261017` (3 files), the verification crate `m3-build-shared-ast-20261017` (`pom.xml`, `README.md`, `M3BuildSharedAstRecipeTest.java`) | absent everywhere in com.synexia (checked `forks/maven/m3/recipe-custody` and the 3,270 crates and 443 verification entries of `synexia-openrewrite-recipes`) |
| `M3EditScope.java`, `M3ScopeInference.java` | byte-identical copies in `forks/maven/m3/recipe-custody/sdk-owner` |
| `M3HashPinnedJavaSnapshotRecipe.java`, `M3HashPinnedTextSnapshotRecipe.java` | `sdk-owner` holds supersets (+2 and +11 added lines) |

The last four are restored so that `SOURCE_CUSTODY.json` (which pins those paths and their SHA-256 values) and the verification crate's `../../src/main/java` source folder stay consistent.

Proof (JDK 21, Maven 3.9.14, offline):

```sh
mvn -o -f synexia-openrewrite-recipes/verification/m3-build-shared-ast-20261017/pom.xml \
  -Dm3.crate=m3-maven-qualified-session-services-20261017 -Dm3.kind=java \
  -Dm3.input=<restored before-dataset> -Dm3.output=<fresh dir> verify
```

- Result: `Tests run: 1, Failures: 0`. The fixed point and the drift, duplicate and missing refusals pass, and both postimages match `manifest.tsv`.
- With the two current `sdk-owner` recipes in place of the restored ones, the build passes again and gives an identical `application.tsv`.

### Not losses

| Finding | Why it is not a loss |
|---|---|
| `DefaultM3PluginEvidenceService.java`, `LOST_NAMES` in `a8589c16c59a` | `3f00e2156` (#49, "Expose verified plugin evidence snapshots through the Maven API") removed these lines on purpose |
| `m3/README.md`, `m3/recipe-custody/README.md`, `m3/recipe-custody/maven-core-plugin-evidence-20261031.json` | deleted by the #30 evacuation; present in com.synexia `forks/maven/m3/` |
| 163 files of `m3/`, `.m3/`, 2 workflows and `docs/m3/` | moved byte-identical by #30 (ledger `forks/_evacuation-20261114/maven.tsv` in com.synexia) |
| 15 `HEAD_HAS_VERSION`, 26 `RECOVERED`, 14 `REWORDED` findings | Every name and literal is still at the head. They are mostly license headers and `@since` lines from the `9d39c02678b9` dialect SPI, the `4ef96d094615` javadoc, and the `a8589c16c59a` evidence API. |
| Chrome/browser providers (7 files) | removed on purpose by #138 (`be738f4bb`, `ba0dbdc96`) and #139 (`284da5bfe`, `ea24c615f`) |
| `.github/workflows/maven.yml` Java 17 changed to 21 | deliberate baseline change (#119) |
| `tools/code-patch`, `tools/maven-agent-plugin`, `tools/maven-mcp-server`, `tools/native-build-maven-plugin` | `git log --all --full-history` finds no commit for these paths on any of 184 branches. They never existed, so nothing was lost. The nearest owners at the head are the smart-builder `CodeAgentMojo` and `McpAdviser`, `NativeBuildPolicyParticipant` (#121) and `M3GitHubPatchBootstrap`. |
| Upstream files | `git diff-tree --diff-filter=D upstream/master HEAD` lists no files. Every upstream file is present. |

### Deliberate drift from upstream (kept, listed for upstream syncs)

`09e3b1321` (2026-10-28) removed the final blank line of six upstream test fixtures in `impl/maven-classworlds/src/test/test-data/`:

- `dupe-realm.conf`
- `optionally-existent.conf`
- `set-using-existent.conf`
- `set-using-existent.properties`
- `set-using-missing.conf`
- `set-using-nonexistent.conf`

These six fixtures are the only upstream files whose content the fork shortens. Checking them out from `upstream/master` would make them byte-identical again. The fork's formatting gate decides whether that is wanted.

## Upstream capability losses worth restoring as opt-in

The upstream history was searched for removals:

- 801 commits delete files under `src/main`;
- 1,506 commit messages mention a removal, 367 of them with MNG keys.

25 capabilities were classified. Only the following are worth an opt-in restoration:

| Capability | Removed in | Commits | Proposal |
|---|---|---|---|
| Prefixless expressions such as `${version}` | Maven 4 | `c6af9d259` (MNG-7404) | A compatibility flag or an `mvnup` rewrite. Medium value. |
| Plexus `BeanConfigurator` API (`org.apache.maven.configuration.*`, Maven 3 extension API) | Maven 4.x, 2026-11-06 | `7c2ee31b8` (12 classes, replaced by `V4MojoConfigurator`) | A compatibility shim, only if a consumer exists. **NOT RUN:** consumer search. |
| Non-unique SNAPSHOT deployment (`uniqueVersion=false`) | Maven 3.0 | `929016ddf` (MNG-4059); the field is still in `maven.mdo`, ignored | Opt-in, low value |
| Super POM `release-profile` | Maven 4 | `6ff4e40e4`, `eae3074c6` (MNG-7029) | Opt-in, low value |
| `-N` tolerating absent subprojects | Maven 4 | upstream commit not identified | **Done in this fork** (`17983e66e`, #125 `8b4ee2a0e`). Candidate for an upstream PR. |

Not worth restoring, each replaced or reverted upstream:

- `profiles.xml`
- plugin registry options
- reports in core
- the Maven 1 repository layout
- `-r` reactor mode
- `pom.*` expressions
- `-llr`
- `-emp`/`-ep` (now `mvnenc`)
- `--define`
- Java 8/11 runtime
- `maven-compat` on the core class path
- `PathTranslator`/`UrlNormalizer` SPI
- the `addPluginArtifactMetadata` binding
- the wrapper inside core
- `par`/`ejb3` lifecycles
- CDI API export
- Maven 2 shims
- `MAVEN_CONFIG`-style variables
- `mvnup --force`
- `ReportingConverter`

## Borrowing from Gradle

### Gradle features this fork lacks

Gradle paths are under `platforms/` of `gradle/gradle` `c2c0eb5a5250`, read through the fork `hsoliwal/gradle` `beabedc9cc4a`.

| Capability | This fork | Gradle | Value |
|---|---|---|---|
| Local build cache | **Opt-in for polyglot tasks in #149.** `M3BuildFingerprintService` still does not restore Maven mojo outputs. | `core-execution/build-cache-local` | High. Next step: mojo outputs. |
| Incremental / up-to-date checks | Partial: polyglot `InputStamps` (`-Dm3.polyglot.incremental`); `M3ExternalBuildService` only advises | `core-execution/execution`, `snapshots`, `normalization` | High: extend the stamps to mojo inputs and outputs |
| Dependency locking | Missing (`git grep` finds only npm lock detection) | `dependency-management/.../internal/locking/LockFileReaderWriter.java` | High: `DependencyInsightService` could write the lock file |
| Dependency verification | Partial: `--strict-checksums`, resolver trusted-checksum sources; no PGP metadata | `dependency-management/.../artifacts/verification` | High |
| Remote build cache | Missing | `build-cache-http`, `build-cache-spi` | Medium, after the local cache |
| Configuration cache | Partial: session-scoped `DefaultRequestCache` only | `core-configuration/configuration-cache` | Medium |
| File-system watching across builds | Partial: within one `-Dm3.watch` run (`M3ProjectWatch`, needs `com.m3.file`) | `core-execution/file-watching` | Medium |
| Daemon | Partial: `mvnsh`; mvnd lives outside this repository | `core-runtime/daemon-server` | Medium |
| Toolchain provisioning | Partial: `DefaultToolchainManager`, `selectToolchain`; no download | `jvm/toolchains-jvm-shared` | Medium: digest-pinned JDK provisioning |
| Composite builds | Partial: `ReactorReader` inside one aggregator | `subprojects/composite-builds` | Medium |
| Worker isolation | Partial: `-T`, `BuildPlanExecutor`, `MultiThreadedBuilder` | `core-execution/workers` | Medium |
| Build scans | Partial: `EventSpy`, smart-builder `BuildLogFacts` | hooks only; scans need the external Develocity plugin | Medium: JSONL events from `EventSpy` |
| Version catalogs | Partial: BOM import | `software/plugins-version-catalog` | Low |
| Continuous build | Present: `M3ProjectWatch` (`docs/M3-WATCH.md`) | `ContinuousBuildActionExecutor` | Low: a `WatchService` fallback |
| Test retry / distribution | Missing here; Surefire is external | external plugins | Low |
| Build init / migration | Present: polyglot `Mavenizer` (#118) | `software/build-init` | Both directions (see below) |

### This fork's features Gradle lacks

Gradle reaches these through its bridge tasks (`m3PolyglotPlan`, `m3PolyglotRun` and `m3EclipseModel` call the pinned Maven JAR):

- The polyglot lifecycle for npm/pnpm/yarn/bun, Cargo, Go, Make, CMake, Meson, Python, .NET and Bazel. Upstream Gradle has 0 paths for each.
- The code-clustering hash planes and compare gate. These are an open, local replacement for Gradle's *Compare Gradle Builds* plugin, which was removed in 6.0 (`8d7fa2f86`).
- `tools/code-recipes` OpenRewrite recipes.
- Ant `build.xml` and native Make/CMake/Meson builds. Gradle 10 plans to remove its Ant integration (`186db4f3e`) and the software-model native plugins (`46889bdc1`, `6541047bb`, `9f22c49b1`).

Gradle has these that this fork can borrow:

- the Problems API, as the schema for smart-builder `diagnose`;
- the build-operation and Tooling API progress event taxonomy, as the model for JSONL build events;
- `Maven2Gradle`, as a test oracle for the Mavenizer;
- toolchain discovery and the OS/arch variant model, for native builds;
- `swiftpm-export`, as a donor.

### Enhancements made in this round

| PR | What | Proof |
|---|---|---|
| hsoliwal/maven #149 | Opt-in content-addressed local build cache for polyglot tasks (`-Dm3.polyglot.buildCache`, CLI `--build-cache`). Adapted from Gradle's `DirectoryBuildCacheService` design (no code copied); the key is the existing `InputStamps` stamp plus OS and architecture. | `verify.sh` on JDK 21 and 17; `mvn install` on JDK 21. End to end: a fresh checkout restores `javac` output from the cache without starting `javac`, and the class files are byte-identical. |

The second candidate, **dependency locking**, was not started in this round. It needs resolver integration in `impl/maven-impl` and a full reactor build with its tests as proof. The full build did not fit next to the other jobs on the build machine's C: drive, which had about 2–3 GB free. It remains the next high-value row, with dependency verification metadata after it.

## NOT RUN

- The consumer search for the `BeanConfigurator` shim.
- A full reactor build of this repository. Only `tools/m3-polyglot-maven-plugin` was built.
- CI for the PRs above (see each PR's checks).
