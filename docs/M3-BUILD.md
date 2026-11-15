<!--
SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# Build the M3 Maven fork

Run one command from the checkout to execute the configured verification lifecycle:

```sh
sh ./m3-build.sh
```

```powershell
./m3-build.ps1
```

Both commands default to `verify`, use Maven's existing reactor and preserve its exit status.
The scripts run from their own directory, including when invoked by an absolute path from
elsewhere. Relative Maven arguments such as settings, module and output paths are therefore
relative to the checkout root.

## Bootstrap once

Use a complete JDK 17 or newer and a pre-built Maven installation satisfying the project's
inherited build requirements. The entry-point lifecycle checks use Maven 3.9.11 with
JDK 21.0.13 and Java 17 compilation. The root README and POMs remain authoritative for
bootstrapping the full source tree. This checkout builds Maven `4.1.0-SNAPSHOT`; the
selected bootstrap executable builds that distribution.

By default the commands use `mvn` on `PATH`. Select an existing installation, including
a previously built fork, through `M3_MAVEN` or PowerShell's `-Maven` parameter:

```sh
M3_MAVEN='/opt/m3 maven/bin/mvn' sh ./m3-build.sh verify
```

```powershell
./m3-build.ps1 -Maven 'C:\tools\m3 maven\bin\mvn.cmd' -Mode verify
```

Supply one executable path, without embedded command-line options. Put options in the
normal argument list. Relative executable paths resolve from the checkout root. Maven
continues to own settings, repository credentials, mirrors, downloads and toolchains.
Executable selection is literal: an absent name or path fails instead of choosing a
similarly named installation through wildcard expansion.
The fork currently has no published release distribution to pin in a Maven Wrapper.

## Common tasks

| Task | POSIX shell | PowerShell | Maven lifecycle |
|---|---|---|---|
| Routine build | `sh ./m3-build.sh` | `./m3-build.ps1` | `verify` |
| Compile and package without running tests | `sh ./m3-build.sh quick` | `./m3-build.ps1 -Mode quick` | `package -DskipTests` |
| Unit tests | `sh ./m3-build.sh test` | `./m3-build.ps1 -Mode test` | `test` |
| Clean rebuild | `sh ./m3-build.sh full` | `./m3-build.ps1 -Mode full` | `clean verify` |
| Include integration tests | `sh ./m3-build.sh it` | `./m3-build.ps1 -Mode it` | `verify -Prun-its` |
| Resume at a failed module | `sh ./m3-build.sh resume :maven-core` | `./m3-build.ps1 -Mode resume -ResumeFrom :maven-core` | `verify -rf :maven-core` |
| Usage without Maven installed | `sh ./m3-build.sh --help` | `./m3-build.ps1 -Help` | None |

`quick` still compiles tests under Maven's normal `skipTests` semantics. It is for local
iteration; use `verify` for verification. `full` deliberately removes previous outputs.
Run `install` explicitly when a separate Maven build needs artifacts in the local repository.
These commands do not implicitly publish artifacts. Only the explicit `it` mode adds
the existing `run-its` profile. For a clean integration-test run, use `full -Prun-its`
on POSIX or `-Mode full -ExtraArgs @('-Prun-its')` on PowerShell.

## Continuous builds

The [M3 watch guide](M3-WATCH.md) describes continuous builds with the installed enhanced
Maven fork and the M3JDK21 `com.m3.file` module, including bounded batching of saved edits.
Watch mode currently runs `clean` before each selected lifecycle because compiler facts
do not yet establish complete dependency and generated-output coverage.

## Work on a module and its prerequisites

Select a concrete reactor module with `-pl` and include its prerequisites with `-am`:

```sh
sh ./m3-build.sh test -pl impl/maven-core -am
sh ./m3-build.sh -pl api/maven-api-core -am
```

The second command shows that shell options alone use the default `verify` mode.
PowerShell provides the same selection through `-Module`:

```powershell
./m3-build.ps1 -Mode test -Module impl/maven-core
./m3-build.ps1 -Module api/maven-api-core
```

`-am` selects prerequisite projects. It does not validate every downstream consumer of
a changed API. Use a full reactor `verify` or an explicitly reviewed dependent selection
when the change crosses module contracts. Selecting a container POM such as `api` or
`impl` is not a substitute for selecting its children when bootstrapping with Maven 3.

Resume uses Maven's existing `-rf` behavior. It assumes preceding artifacts remain
available; Maven 3 may need prerequisites in its local repository. If resume cannot
resolve them, rerun the module with `-pl ... -am` or run the full reactor. PowerShell
rejects `-Module` combined with resume because it would silently add `-am`.

## Pass through ordinary Maven options

```sh
sh ./m3-build.sh verify -o -Dstyle.color=never
sh ./m3-build.sh verify -T 2
sh ./m3-build.sh verify -Prun-its
sh ./m3-build.sh quick '-DdistributionTargetDir=/absolute/path/m3 maven'
```

```powershell
./m3-build.ps1 -Mode verify -ExtraArgs @('-o', '-Dstyle.color=never')
./m3-build.ps1 -Mode verify -Threads 2
./m3-build.ps1 -Mode verify -ExtraArgs @('-Prun-its')
./m3-build.ps1 -Mode quick -ExtraArgs @('-DdistributionTargetDir=C:\tools\m3 maven')
```

The scripts add batch mode and suppress transfer progress (`-B -ntp`). They do not add
`-T` unless requested; parallel safety remains a property of the selected plugins and
modules. `-o` uses already resolved artifacts and fails normally when they are absent.
PowerShell forwards `-ExtraArgs` as an argument array, without constructing or evaluating
a command string. Explicit extra options can change the lifecycle's behavior, including
test execution, so record them with build results.

## Verification scope

The entry points simplify how the existing build is invoked. Maven's POMs, configured
checks, compiler integration and dependency resolution retain their existing authority.
`verify` runs the configured lifecycle; upstream integration tests require the existing
`run-its` profile and its prerequisites. The `it` mode supplies that profile without
changing its configured checks. These commands do not establish M3AST-based
incremental invalidation or cache correctness.

The companion Synexia recipe binds exact inputs and generated script/document outputs.
Its receipts distinguish argument-forwarding checks, a small real Maven reactor test,
PowerShell platform verification, and the complete Maven source build. Consult the PR's
actual results before treating any of those gates as passed.

### Command regression checks

Run the standard-library product test from a POSIX host with Python 3, `sh` and `pwsh`:

```sh
python3 scripts/tests/test_m3_build.py
```

It uses a recording executable to verify literal arguments and executable selection,
root-relative paths, module/resume rules, lifecycle choices, working-directory restoration
and exit statuses. A missing runtime or unsupported fixture host is reported as skipped;
both shell lanes must execute for cross-runtime qualification. These command checks are
separate from the real Maven reactor and native Windows `mvn.cmd` acceptance.
