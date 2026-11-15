# M3 Maven fork: build entry points

These optional entry points simplify routine Maven reactor commands. They do not alter Maven core, the POM, dependency versions, plugin execution or upstream bootstrap requirements.

## Prerequisites

Install the Maven bootstrap version required by the root `pom.xml` (currently Maven 3.9.8 baseline) and the JDK version required by the project (currently Java 17). This fork is Maven 4.1.0-SNAPSHOT; the scripts invoke the installed Maven to build it, not the fork being built.

## Windows PowerShell

```powershell
./m3-build.ps1 -Mode quick
./m3-build.ps1 -Mode test -Module api
./m3-build.ps1 -Mode verify -Module impl
./m3-build.ps1 -Mode full
./m3-build.ps1 -Mode resume -ResumeFrom :maven-core
./m3-build.ps1 -Mode verify -Threads 2 -ExtraArgs @('-Dstyle.color=never')
```

The `-Module` selector adds `-pl <module> -am`, rebuilding selected modules with their reactor prerequisites. `resume` delegates to `-rf` and does not imply `-am`. Other Maven arguments can be forwarded with `-ExtraArgs`.

## Linux / macOS

```sh
sh ./m3-build.sh quick
sh ./m3-build.sh test -pl api -am
sh ./m3-build.sh verify -pl impl -am
sh ./m3-build.sh full
```

## Gates

| Command | Maven invocation | Intent |
|---|---|---|
| quick | `package -DskipTests` | Compile/package and compile tests, but skip running tests |
| test | `test` | Execute unit tests |
| verify | `verify` | Run the project's full configured verification lifecycle |
| full | `clean verify` | Remove prior build outputs and verify |
| resume (PowerShell) | `verify -rf <module>` | Resume a failed reactor build |

A quick build is not a release gate. `verify` or `full` must pass before merging. Parallel threads are opt-in on PowerShell because Maven modules/plugins may have threading restrictions.

**Scope:** This change only adds build shortcuts. Cross-repository parent POM/BOM convergence, toolchains, Maven Daemon, OpenRewrite recipes, JNI native build integration and M3JDK21 bootstrapping should be handled in separately validated changes. No build or test is claimed to have been run by this documentation change.
