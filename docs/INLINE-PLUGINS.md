<!--
SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# Inline plugins

This fork carries the default-lifecycle plugins as source under `plugins/`. Each one is imported with its full
history. Under `-Pplugins` the fork builds them and binds the default lifecycle to them. The distribution then
ships them in a boot repository, so a fresh build needs no plugin download for the default lifecycle.

Status: batch 1 (clean, resources, compiler, jar, install, deploy, surefire) is imported, built and tested.
The offline acceptance passed on Windows 11 with JDK 21.0.9 and Maven 4.0.0-rc-7.
Batches 2a, 2b, 2c and 3 are planned, not started.
Anything not executed is marked NOT RUN.

## Plan

### Which tag

Rule: import the newest GA tag that the fork already runs on Maven 4.

- Every batch 1 tag is exactly the version `impl/maven-core/pom.xml` binds today: clean 3.5.0,
  resources 3.5.0, compiler 3.16.0, jar 3.5.1, install 3.2.0, deploy 3.2.0, surefire 3.6.1. Moving to the
  inline copies therefore changes no plugin code.
- Every tag meets the Maven 4 minimum in `mvnup` (`PluginUpgradeStrategy`).
- The Maven-4-native pre-release lines (`4.0.0-beta-*` on the `<plugin>-4.x` branches) build against
  `maven-api` rc-6 or rc-7, not 4.2.0. `mvnup` warns against them, so they are recorded and not imported.

### Layout and commits

- `plugins/<repository>` holds each plugin repository; `plugins/maven-surefire` holds surefire, failsafe, the
  report plugin and the surefire internals.
- `plugins/pom.xml` only aggregates them (packaging `pom`, parent = fork root).
- Each donor reactor keeps its upstream parent and build.
- One merge commit per donor imports the tag commit SHA:

  ```sh
  git fetch --no-tags https://github.com/<owner>/<repo>.git refs/tags/<tag>:refs/import/<name>
  git merge -s ours --no-commit --allow-unrelated-histories <sha>
  git read-tree --prefix=plugins/<name>/ -u <sha>
  git commit   # "Import <owner>/<repo> <tag> (<sha>) under plugins/<name>"
  ```

- Every later change is its own commit, never part of a merge:
  - V: the donor's version becomes the fork version;
  - P: parent changes;
  - A: Maven API alignment;
  - S (wave 2a): moving a consumer onto an in-tree shared library;
  - one wiring commit each for the rest.

### Bundling: a boot repository on the local-repository tail

The distribution ships `repository/` (Maven 2 layout, read-only). `conf/maven-boot-repository.properties`
chains it after the user's local repository:

```properties
maven.repo.local.tail = ${maven.home}/repository
maven.repo.local.tail.ignoreAvailability = true
```

`conf/maven-system.properties` includes that file first and optionally, so it is a no-op when the file is
absent. Later includes (user, project) override it.

Why not `lib/plugins` with a workspace reader:
- `component.xml` puts every runtime dependency of `apache-maven` into `lib/`, and `m2.conf` loads
  `lib/*.jar` into `plexus.core`;
- the chained local repository manager already exists in core (`maven.repo.local.tail`, since 3.9);
- a jar-serving workspace reader would need new core code and would have to serve every transitive
  dependency.

### Waves

| Wave | Content | Route |
|---|---|---|
| 1 | clean, resources, compiler, jar, install, deploy, surefire (7 repositories, 7,239 commits) | boot repository |
| 2a | the 14 shared libraries batch 1 loads (maven-shared-utils, plexus-utils, plexus-xml, maven-filtering, plexus-interpolation, plexus-build-api, maven-archiver, plexus-archiver, plexus-io, file-management, plexus-compiler, plexus-languages, maven-shared-incremental, maven-common-artifact-filters) | under `plugins/shared/`, one S commit per consumer |
| 2b | site, war, ear, ejb, rar, plugin-tools, doxia, doxia-sitetools, reporting api/impl/exec, plexus-i18n, plexus-velocity, plexus-interactivity, maven-mapping | boot repository, one seed per packaging |
| 2c | source, javadoc, dependency, assembly, shade, enforcer, release, gpg, invoker, antrun, help, toolchains, jlink, jmod, remote-resources, archetype and their libraries | reactor only until the boot size is reviewed |
| 3 | build-helper (MIT), exec, versions, flatten, pmd, checkstyle, spotbugs, rewrite-maven-plugin | reactor only, never bundled |

Deferred: rewrite and spotless (Gradle builds). Ideas only: jacoco (EPL-2.0) and license-maven-plugin
(LGPL-3.0). Only Apache-2.0 and MIT trees are imported; each LICENSE and NOTICE at a tag stays in place.

## Batch 1 as built

### Imports

| Donor | Tag | Tag commit | Merge commit | History commits |
|---|---|---|---|---|
| apache/maven-clean-plugin | maven-clean-plugin-3.5.0 | d18c59617e3a | 403b24d238 | 403 |
| apache/maven-resources-plugin | maven-resources-plugin-3.5.0 | ce485a0360dc | 5d4b7bf809 | 554 |
| apache/maven-compiler-plugin | maven-compiler-plugin-3.16.0 | e7bba6ed5f9c | 1897b893a6 | 973 |
| apache/maven-jar-plugin | maven-jar-plugin-3.5.1 | b0cd63d0ad54 | f81ce35c7d | 603 |
| apache/maven-install-plugin | maven-install-plugin-3.2.0 | bcf484d2ac82 | f5fc311f4a | 479 |
| apache/maven-deploy-plugin | maven-deploy-plugin-3.2.0 | 7aab9fb2b381 | 39b36421b4 | 534 |
| apache/maven-surefire | surefire-3.6.1 | 5bf0dcf03436 | d423e5a05a | 3,693 |

Gates, all PASS for all seven imports:
- `HEAD:plugins/<name>` equals `<sha>^{tree}`;
- `git rev-list --count HEAD^2` equals the donor's history count;
- the merge changes nothing outside `plugins/<name>`.

### Changes after the imports

- V: version set to `4.2.0-SNAPSHOT`, one commit per donor. Only the project `<version>` and in-reactor
  `<parent><version>` change (1 line each in six donors, 17 POMs in surefire).
- P: `<relativePath />` added to the parent of `maven-resources-plugin` and `maven-surefire`. Without it,
  Maven 4 probes `../pom.xml`, finds `plugins/pom.xml` and reports a model problem. The other five donors
  already had it. The upstream parents themselves are unchanged.
- A: none. The plugins compile against their upstream Maven 3 API and run on the fork through its compat
  layer (proven by the acceptance below). A compile probe against the fork's `compat` modules is NOT RUN.
- Wiring:
  - root `pom.xml`: profile `plugins` adds the module `plugins`; the root RAT scan excludes `plugins/**`.
    Without `-Pplugins` the root scan would read the imported test fixtures.
  - `impl/maven-core`: `plugin-versions.properties` now reads the seven batch 1 bindings from
    `lifecycle.binding.*`. By default these equal `lifecycle.*`; profile `plugins` sets them to
    `${project.version}`. The `lifecycle.*` properties are not overridden directly, because this POM's
    `pluginManagement` also builds maven-core itself with them.
  - `conf/maven-system.properties`: the optional include line.
  - `apache-maven`, profile `plugins`:
    - test-scoped, exclusion-only dependencies order the module after the plugins;
    - `invoker:install` puts the plugins, the surefire JUnit Platform provider, their runtime closures and
      parent POMs into `target/boot-repository`;
    - `invoker:run` builds the seeds in `src/boot-seed` (`clean install deploy`) with a dir distribution,
      a fresh user home and no user settings, which captures the rest;
    - `bin-boot.xml` adds `repository/` and the properties file to the zip and tar.gz.
  - CI: `maven.yml` passes `-Pplugins` to both distribution builds. `m3-build.sh` and `m3-build.ps1` get a
    `dist` mode.
  - `plugins/pom.xml` declares `inline.atomizer=false`. The inline atomizer is wired off: NOT RUN.

### Evidence

All runs used Windows 11, JDK 21.0.9 and Maven 4.0.0-rc-7. The local repository was a fresh head
repository, with the user's repository as a read-only tail.

| Proof | Result |
|---|---|
| `-Pplugins -pl <23 plugin modules> -am install -DskipTests` | BUILD SUCCESS, 20:23 min, no donor file changed by the donors' own `spotless:apply` |
| Plugin unit tests (`install` with tests) | clean 15 (6 skipped), resources 25, compiler 45, jar 3, install 28, deploy 28, surefire-logger-api 11, surefire-api 219 (6 skipped), surefire-extensions-api 1, surefire-booter 172 (2 skipped), common-java5 25, surefire-junit-platform 167, surefire-report-parser 34, surefire-report-plugin 14, maven-surefire-common 878 (4 skipped), maven-surefire-plugin 11, maven-failsafe-plugin 22: 0 failures |
| `E2ETest.endToEndTest` (maven-surefire-common) | FAILS on this host, a throughput test with a 30 s budget that took about 35 s at 70 % CPU load. The pristine upstream `surefire-3.6.1` tree fails the same way on the same host (35.80 s), so the import does not cause it. The other 878 tests of the module pass. |
| History: `git log -m --follow` on 3 files | PASS. `CleanMojo.java` reaches 47acbe0128 (2004-04-20), `CompilerMojo.java` reaches 3aed34c11e (2012-11-15) and `SurefireMojo.java` reaches 393879d9d2 (2006-12-20), the same oldest commits as in the donor histories. Plain `git log --follow` (without `-m`) stops at the subtree merge. |
| Distribution build with `-Pplugins` | BUILD SUCCESS; both seeds passed in the capture run |
| Boot repository | 369 files, 22,037,391 bytes uncompressed, 19,008,248 bytes compressed; bin zip 35,755,575 bytes |
| A1: unpacked zip, empty local repository, fresh user home, `-o`, jar seed `clean install` | BUILD SUCCESS; `clean`, `resources`, `compiler`, `surefire`, `jar` and `install` all ran at 4.2.0-SNAPSHOT; 0 download lines; the local repository holds only the seed's own install output (5 files) plus resolver lock files |
| A2: as A1 but online with Central pointed at an unreachable address, `clean deploy` | BUILD SUCCESS through `deploy:4.2.0-SNAPSHOT`; the only download line is the seed's own metadata from its `file:` deploy target. `-o` cannot be used here because the deploy plugin refuses to deploy offline. |
| A3: JUnit seed, online | BUILD SUCCESS, 1 test passed; downloads are only `org/junit/**` (EPL-2.0, excluded from the bundle by design) |
| Default build (no `-Pplugins`) | the root RAT scan finds no issues with `plugins/` present, and the filtered bindings stay at the stock versions |

Boot repository licences:
- jars are Apache-2.0, MIT, BSD (asm, zstd-jni) or 0BSD (xz);
- javaparser-core is dual LGPL-3.0/Apache-2.0 and is used under Apache-2.0;
- `org.eclipse.sisu.plexus` 0.9.0.M4 is EPL-2.0, the same licence as the Sisu jars already in `lib/`. It
  stays because, without it, the offline build fails resolving the maven-resources-plugin closure
  (measured);
- the JUnit BOM POMs (EPL-2.0) are kept as metadata for imported BOMs; JUnit jars are excluded.

### Known issues and NOT RUN

- A maven-core built with `-Pplugins` binds the default lifecycle to the inline plugin versions
  (`4.2.0-SNAPSHOT`), which resolve only from the distribution's boot repository. It is valid only inside that
  distribution and must never be deployed or embedded on its own: embedders and the embedded-invoker tests
  (`MavenInvokerTest#defaultFs`) fail with "maven-resources-plugin:4.2.0-SNAPSHOT ... could not be resolved".
  CI therefore runs core verify on the stock bindings and builds `-Pplugins` as a separate `-DskipTests`
  package step, plus `-f plugins/pom.xml verify` for the plugins' own tests.
- At the base commit, `tools/m3-polyglot-maven-plugin`'s `task-contract` exec check fails on Windows
  (`NoSuchFileException ...\bin\java`), with or without this change. The local distribution build used
  `-Dexec.skip=true` for the last three modules. master fixes it in a0d887fe8f.
- NOT RUN:
  - CI on this branch;
  - the plugins' integration tests (`-Prun-its`, including `surefire-its`);
  - Linux and macOS;
  - Maven 3.9 as the outer build;
  - the A compile probe;
  - the inline atomizer;
  - a release gate that checks Central for every inline GAV.
- Risks carried from the plan:
  - Version collision: a published `4.2.0-SNAPSHOT` or `4.2.0` of the same coordinates in the user's
    local repository wins over the tail.
  - A user `maven.repo.local.tail` replaces the boot repository unless it appends `${maven.home}/repository`.
  - Embedders (m2e, IDEs) do not read `conf/maven-system.properties`.
  - The upstream parents bring their own spotless, RAT, enforcer and Java 8 targets, which may break a
    `-Pplugins` build on newer JDKs.
  - The capture run needs network access.
