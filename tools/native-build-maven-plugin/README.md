<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Apache Maven Native Build Plugin

`org.apache.maven.plugins:maven-native-build-plugin` (goal prefix `native-build`) builds C and C++ projects with
their own build files - CMake (and CMake presets), Make, Autotools, Meson and hand-written Ninja files - inside
Maven's phases. The native tools stay the authority for compiling; the plugin plans their argument lists, runs them
through the polyglot task engine (`tools/m3-polyglot-maven-plugin`, unchanged), turns their test results into
surefire reports, stages and attaches the installed files, and publishes `compile_commands.json` for clangd.

No command ever goes through a shell: every step is an executable plus an argument list, written as a polyglot
manifest (`target/native/<classifier>/tasks.tsv`) and started by the engine's `ProcessBuilder`.

## Goals and phases

| Goal | Default phase | Runs |
| --- | --- | --- |
| `detect` | initialize | nothing; prints the build system, classifier, directories, each tool with its origin and every planned argument list |
| `configure` | generate-sources | toolchain fingerprint, then `autoreconf -fi` (only when `configure` is missing), `cmake -S -B` / `cmake --preset`, `sh <rel>/configure --prefix=...` out of tree, or `meson setup` |
| `build` | compile | `cmake --build --parallel N --config T`, `make -f <file> -jN`, `make -C <build>`, `ninja -C`, `meson compile` |
| `compile-commands` | compile | produces and publishes `compile_commands.json` (see below) |
| `test` | test | `ctest --output-junit`, `make check` / `test`, `meson test`, a Ninja test target; writes `TEST-native.<system>.<classifier>.xml` |
| `stage` | prepare-package | `cmake --install --prefix <stage>`, `make install DESTDIR=<stage>`, `meson install --destdir`; declared `outputs` when there is no install step |
| `attach` | package | zips the stage (sorted entries, `project.build.outputTimestamp`) and attaches it with the classifier |
| `polyglot-tasks` | none | writes the same steps as a chained `m3-build.tsv` for the polyglot lifecycle participant |

```xml
<packaging>pom</packaging>
...
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-native-build-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <configuration>
    <cmakeGenerator>Ninja</cmakeGenerator>
    <tools><cc>gcc</cc></tools>
  </configuration>
  <executions>
    <execution>
      <goals>
        <goal>configure</goal><goal>build</goal><goal>compile-commands</goal>
        <goal>test</goal><goal>stage</goal><goal>attach</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

`mvn package` then configures, builds, tests and attaches `<finalName>-<classifier>.zip`; `mvn install` and
`deploy` carry it like any attached artifact. Fixture projects are in `src/it` (`cmake-lib`, `make-proj`,
`autotools-proj`, `meson-proj`) with `src/it/run-native-it.sh`, which runs one twice and prints the evidence.

## Detection

Only the configured `sourceDirectory` (default the project directory) is examined, not its sub-folders. A file that
generates another wins over the generated one: `CMakePresets.json` + `CMakeLists.txt` (cmake-presets),
`CMakeLists.txt` (cmake), `meson.build`, `configure` (autotools), `configure.ac` / `configure.in` without `configure`
(autotools with bootstrap), `build.ninja`, then `GNUmakefile`, `makefile`, `Makefile`. `buildSystem` overrides it
(`cmake`, `cmake-presets`, `meson`, `autotools`, `ninja`, `make`). nmake runs only with `make.tool=nmake` inside a
Visual Studio developer prompt (`VCINSTALLDIR` set); otherwise it fails with a message pointing to CMake.

## Classifier and layout

The classifier is `os-arch` in the os-maven-plugin vocabulary (`windows-x86_64`, `linux-x86_64`, `linux-aarch_64`,
`osx-aarch_64`). On Windows `-msvc` (Visual Studio generator, `cl`, `clang-cl`) or `-mingw` (gcc, clang) is added,
because the runtimes and import libraries do not mix. `classifier` overrides it. Builds live in
`target/native/<classifier>`, the stage in `target/native/stage/<classifier>`, reports in `target/native-reports`.

## Tools and toolchains

Each logical tool (`cmake`, `ctest`, `make`, `ninja`, `meson`, `autoreconf`, `sh`, `cc`, `cxx`, `bear`) is resolved
in this order: the `tools` map (a path, or a name looked up on PATH), toolchains.xml entries of that type
(`provides` matched against `toolchainRequirements`, `configuration/executable` or `installationPath`), an optional
provisioned CMake (`cmake.provision=true` resolves `com.googlecode.cmake-maven-project:cmake-binaries:<version>:<platform>`
through Maven Resolver and unpacks it under `target/native/tools`), then PATH (with PATHEXT on Windows). Compilers
are never taken from PATH: an unconfigured compiler is the build system's own choice; a configured one is passed as
`-DCMAKE_C_COMPILER=`, `CC=` or a Meson native file. Maven's toolchain manager answers only types with a factory, so
the plugin reads the raw toolchains.xml models from the request.

```xml
<toolchain>
  <type>cmake</type>
  <provides><version>3.28</version></provides>
  <configuration><executable>/opt/cmake-3.28/bin/cmake</executable></configuration>
</toolchain>
```

## Incremental builds

`-Dnative.incremental=true` turns on the polyglot engine's content stamps (`<project>/.m3/polyglot-stamps`, outside
`target`, as the engine fixes it). Configure declares the build descriptors and `toolchain.properties` - sorted tool
paths, each tool's first `--version` line, the platform and the allow-listed variables CC, CXX, CFLAGS, CXXFLAGS,
CPPFLAGS, LDFLAGS, PKG_CONFIG_PATH, without time stamps - so a new compiler re-runs configure. Test and install
declare the source set (or the declared `outputs`). The build step is stamped only with declared `outputs`;
otherwise it always runs and the native tool decides (`ninja: no work to do`, `Nothing to be done`). Without
incremental mode every step runs and the stage is emptied first.

## Tests and reports

CTest's JUnit is normalized (its suite is called "(empty)", passing cases carry `status="run"`, there is no errors
attribute; `notrun` - CTest could not start the test - becomes an error, `disabled` skipped). Meson's JUnit is read
as written. Automake's `.trs` files give one case per result: PASS and XFAIL pass, FAIL and XPASS fail, ERROR is an
error, SKIP skipped, the test's `.log` is its output. A step without readable results gives one case from its exit
status, and a failed step whose results show no failure gets an error case. Reports go to `target/native-reports`
and are copied to `target/surefire-reports`; `skipTests`, `maven.test.skip` and `maven.test.failure.ignore` apply.

## compile_commands.json

`compileCommands=auto` takes CMake's file (`CMAKE_EXPORT_COMPILE_COMMANDS=ON` is always passed; the Visual Studio
and Xcode generators write none), Meson's, `ninja -t compdb` filtered to sources, Bear when it is on PATH (an
external GPL tool, only run), or a `make -nwB` dry run. For Autotools the dry run holds the generated files old
(`-o Makefile -o config.status -o <rel>/configure ...`) so configure is not re-run; the output is split into commands
and words without a shell (`$depbase`, escaped quotes, `&&\` continuations) and dependency-file options are dropped.
`publish=copy` (default) copies it to the project directory, `clangd-config` writes `.clangd` with
`CompileFlags: CompilationDatabase: target/native/<classifier>`, `none` leaves it in the build tree. A file the
plugin did not write (its SHA-256 is recorded in `target/native/<name>.sha256`) is kept with a warning. Eclipse m2e
runs this goal on project configuration (`META-INF/m2e/lifecycle-mapping-metadata.xml`) and ignores the others.

## Windows

The Visual Studio generator and clang-cl with Ninja find MSVC without vcvars; the vcvarsall + cmd route of
maven-native is not used because it interpolates into a shell. Chocolatey GNU Make starts simple recipe lines
itself and others through `SHELL`; the plugin passes `SHELL=<sh>` (Git for Windows' `sh.exe`, found on PATH or in its
default folder) as one argument, so recipes with shell syntax work without sh on PATH. Paths given to native tools
use the mixed form `C:/a/b`.

## Root manifest route

`mvn native-build:polyglot-tasks` writes `m3-build.tsv` (kept when it exists, unless
`polyglotManifest.overwrite=true`) with the steps chained and the targets `compile` (configure and build),
`test` and `package` (install), because the polyglot lifecycle participant binds only validate, compile, test,
package and verify. A step working below `target` gets a `mkdir -p` step first. The fingerprint is not an input
there, since `mvn clean` removes it.

## Layout of the code

`org.apache.maven.plugins.nativebuild.engine` is plain JDK code (System.Logger, no Maven API): detection, planners
per build system, the manifest writer, the polyglot adapter (`PolyglotEngine`, the only class that imports the
engine), tool resolution, fingerprint, captured processes, reports, the compile database and the stage.
`org.apache.maven.plugins.nativebuild` holds the goals. Contract tests (`NativeBuildContracts`, run by
exec-maven-plugin in verify) check detection, classifiers, exact argument lists per build system and OS, manifest
round trips through the engine, incremental runs with real processes, report conversion from CTest output captured
on this machine, the make dry-run parser, publishing and the stage. Donor decisions are in
`FOSS_REUSE_DECISION.tsv`.
