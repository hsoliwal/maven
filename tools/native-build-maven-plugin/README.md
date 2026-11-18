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
surefire reports, stages and attaches the installed files, consumes native zips of other modules, and publishes
`compile_commands.json` for clangd.

No command ever goes through a shell: every step is an executable plus an argument list. The polyglot runner owns
the task graph, the per-step timeout (it stops the step and its child processes) and the content stamps; each of its
tasks is a small `StepShim` (`java -cp <plugin> ...StepShim <step file> <log file>`) that starts the real tool with
what the runner lacks: on Windows every argument quoted by the C runtime's rules, step environment additions, and
both output streams written to `target/native/<key>/logs/<task>.log`, which is followed line by line into Maven's
log while the step runs. A failing step's error carries the last 40 lines of its output.

## Goals and phases

| Goal | Default phase | Runs |
| --- | --- | --- |
| `detect` | initialize | nothing (no tool, no download, no unpack): prints the build system, classifier, directories, each tool with its origin, every planned argument list, a refused install and a competing `m3-build.tsv` |
| `configure` | generate-sources | toolchain fingerprint, native dependencies unpacked, then `autoreconf -fi` (only when `configure` is missing), `cmake -S -B` / `cmake --preset`, `sh <rel>/configure --prefix=...` out of tree, or `meson setup` |
| `build` | compile | `cmake --build --parallel N --config T` (or `--preset` when a build preset exists), `make -f <file> -jN`, `make -C <build>`, `ninja -C`, `meson compile` |
| `compile-commands` | compile | produces and publishes `compile_commands.json` (see below) |
| `test` | test | `ctest --output-junit` (or `--preset`), `make check` / `test`, `meson test`, a Ninja test target; writes `TEST-native.<system>.<classifier>.xml` |
| `stage` | prepare-package | `cmake --install --prefix <prefix>` with `DESTDIR=<stage>`, `make install DESTDIR=<stage>`, `meson install --destdir`; declared `outputs` when there is no install step |
| `attach` | package | zips the stage (sorted entries, `project.build.outputTimestamp` as a UTC time clamped to the zip range 1980-01-01T00:00:02Z to 2107-12-31T23:59:58Z with a warning, Unix modes) and attaches it with the classifier |
| `clean` | clean | removes what the build left outside `target` (see Clean) |

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
        <goal>test</goal><goal>stage</goal><goal>attach</goal><goal>clean</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

`mvn package` then configures, builds, tests and attaches `<finalName>-<classifier>.zip`; `mvn install` and
`deploy` carry it like any attached artifact. Fixture projects are in `src/it`: `cmake-lib`, `cmake-presets`
(configure preset only, binaryDir outside target), `make-proj`, `ninja-proj`, `autotools-proj`, `meson-proj`, and
`cmake-app` with the aggregator `native-reactor` (an app consuming `cmake-lib`'s zip). `src/it/run-native-it.sh`
runs one twice and prints the evidence; `MVN=<command>` runs another Maven command.

## Detection

Only the configured `sourceDirectory` (default the project directory) is examined, not its sub-folders. A file that
generates another wins over the generated one: `CMakePresets.json` + `CMakeLists.txt` (cmake-presets),
`CMakeLists.txt` (cmake), `meson.build`, `configure` (autotools), `configure.ac` / `configure.in` without `configure`
(autotools with bootstrap), `build.ninja`, then `GNUmakefile`, `makefile`, `Makefile`. `buildSystem` overrides it
(`cmake`, `cmake-presets`, `meson`, `autotools`, `ninja`, `make`). nmake runs only with `make.tool=nmake` inside a
Visual Studio developer prompt (`VCINSTALLDIR` set); otherwise it fails with a message pointing to CMake.

## Classifier and layout

The classifier contract is the os-maven-plugin vocabulary: `os-arch` is `os.detected.classifier`
(`windows-x86_64`, `linux-x86_64`, `linux-aarch_64`, `osx-aarch_64`, ...). On Windows the C runtime is added, `-msvc`
or `-mingw`, because their libraries and import libraries do not mix. The runtime is read from the configured build,
not from the plugin's parameters: CMake's `CMakeFiles/<version>/CMakeCCompiler.cmake` (MSVC, clang-cl and clang
targeting MSVC are msvc; GNU and MinGW clang are mingw), Meson's `meson-info/intro-compilers.json`, the `CC` of an
Autotools build's Makefile, or the configured compiler of a Make or Ninja build; `detect` shows a guess before the
first configure. `classifier` overrides it; it may hold letters, digits, `_`, `.` and `-` only (no path segments).

Each build lives in `target/native/<key>`: `os-arch` (or the configured classifier), then the CMake generator and
the configured compiler when given (`windows-x86_64-ninja-gcc`, `windows-x86_64-visual-studio-17-2022`), so
switching either never reuses a tree configured for the other. The stage is `target/native/stage/<key>`, reports
`target/native-reports`, logs `target/native/<key>/logs`.

## Tools and toolchains

Each logical tool (`cmake`, `ctest`, `make`, `ninja`, `meson`, `autoreconf`, `sh`, `cc`, `cxx`, `bear`) is resolved
in this order: the `tools` map (a path, or a name looked up on PATH), toolchains.xml entries of that type
(`provides` matched against `toolchainRequirements`, `configuration/executable` or `installationPath`), an optional
provisioned CMake, then PATH (with PATHEXT on Windows). A tool named in `tools` or matched in toolchains.xml that
does not exist fails the build: a pinned tool is never silently replaced by one on PATH. Compilers are never taken
from PATH: an unconfigured compiler is the build system's own choice; a configured one is passed as
`-DCMAKE_C_COMPILER=`, `CC=` or a Meson native file. Maven's toolchain manager answers only types with a factory, so
the plugin reads the raw toolchains.xml models from the request.

```xml
<toolchain>
  <type>cmake</type>
  <provides><version>3.28</version></provides>
  <configuration><executable>/opt/cmake-3.28/bin/cmake</executable></configuration>
</toolchain>
```

`cmake.provision=true` resolves `com.googlecode.cmake-maven-project:cmake-binaries:<version>:<platform>` through
Maven Resolver with checksum policy `fail` (a checksum mismatch is an error, whatever the repository says), checks
the archive's SHA-256 while copying it into `target/native/tools`, and unpacks only that verified copy (the file in
the local repository is read once, so it cannot change between check and unpack). The plugin pins the SHA-256 of
the default version's five Maven Central archives (each streamed from Central and hashed; their SHA-1 matched
Central's `.sha1`); any other version needs `native.cmake.sha256`. The unpacked folder's marker holds the archive
hash and the SHA-256 of `bin/cmake` and `bin/ctest` (with or without `.exe`), checked on every use; a changed
executable is provisioned again. `native.buildDirectory` must lie inside the project and `native.cmake.version` and
`native.cmake.provisionGroupId` must be plain names (no `/`, no `..`) before anything is resolved, deleted or
unpacked. Only `configure` and the goals after it provision; `detect` never downloads.

## Incremental builds

`-Dnative.incremental=true` turns on the polyglot engine's content stamps (`<project>/.m3/polyglot-stamps`, where
the engine keeps them). Configure declares the build descriptors, `toolchain.properties` (sorted tool paths, each
tool's first `--version` line, the platform and the allow-listed variables CC, CXX, CFLAGS, CXXFLAGS, CPPFLAGS,
LDFLAGS, PKG_CONFIG_PATH, without time stamps) and the native dependency markers, so a new compiler or dependency
re-runs configure. Test and install declare the source set (or the declared `outputs`), the configure result
(`CMakeCache.txt`, `config.status`, Meson's `coredata.dat`), the fingerprint and `build-products.digest`, which every
build step rewrites with the path, size and time of each object file, library and executable: a configure or
toolchain change that relinks anything re-runs test and install, while a native no-op (`ninja: no work to do`)
keeps them up to date. Each task's step file (tool, arguments, environment) is an input too. The build step is
stamped only with declared `outputs`; otherwise it always runs and the native tool decides. The install empties the
stage whenever it really runs, so the stage never keeps files of an earlier build. A run without incremental mode
deletes the stamps of the steps it runs, so a later incremental run never trusts a stamp that describes another
configuration. Whenever a stamped step really runs, its stamp and the stamps of every later step are deleted before
its tool starts, and the engine writes a stamp only after a success: a configure, test or install that fails, times
out or is killed runs again next time even when the inputs return to those of an earlier success, and the steps
after a re-run step run again too. A step that is never stamped (the build without `outputs`) leaves the later
stamps alone, since their inputs carry its result (`build-products.digest`); when it fails, they are deleted.
A stage changed by hand after an install is not detected (the engine checks only that the stage folder exists).

## Native dependencies

Every goal resolves the project's dependencies (scope compile). Each `zip` dependency whose classifier is this
platform's `os-arch` or starts with `os-arch-` (the zips `attach` produces) is unpacked by `configure` into
`target/native/deps/<groupId>/<artifactId>/<classifier>` (again only when the zip changed), one folder per zip so the
`-lib` and `-headers` archives of a split attach can be consumed together, and each passed on as an install prefix:
`-DCMAKE_PREFIX_PATH` for CMake (find_package, find_path and find_library see it), `-Dpkg_config_path` and
`-Dcmake_prefix_path` for Meson, `CPPFLAGS=-I.../include`, `LDFLAGS=-L.../lib` and `PKG_CONFIG_PATH` as configure
arguments for Autotools, and the same variables plus `CMAKE_PREFIX_PATH` added to the environment of Make steps
(the makefile's own assignments win). A consumer declares the producer's zip with its classifier:

```xml
<dependency>
  <groupId>org.example</groupId><artifactId>fxcore</artifactId><version>1.0</version>
  <type>zip</type><classifier>windows-x86_64-mingw</classifier>
</dependency>
```

In a reactor (also with `-T`) the zip comes from the producer module's `attach`; nothing reads a sibling's `target`.

## Tests and reports

CTest's JUnit is normalized (its suite is called "(empty)", passing cases carry `status="run"`, there is no errors
attribute; `notrun` - CTest could not start the test - becomes an error, `disabled` skipped). A CTest run that found
no tests is reported as no tests (`--no-tests=error` in `testArgs` makes it fail instead). Meson's JUnit is read as
written. Automake's `.trs` files give one case per result: PASS and XFAIL pass, FAIL and XPASS fail, ERROR is an
error, SKIP skipped, the test's `.log` is its output. A step without readable results gives one case from its exit
status; a tool that could not be started is an error, not a test failure; a failed step whose results show no
failure gets an error case. Reports go to `target/native-reports` and are copied to `target/surefire-reports`;
`skipTests`, `maven.test.skip` and `maven.test.failure.ignore` apply. A failure or error keeps the first line of its
message in the `message` attribute and the whole text, line breaks included, as element text. When tests fail,
Maven's error names up to 10 failed cases with the first lines of their message and output, then the test step's
failure with the last lines of its output (as surefire does).

## compile_commands.json

`compileCommands=auto` takes CMake's file (`CMAKE_EXPORT_COMPILE_COMMANDS=ON` is always passed; the Visual Studio
and Xcode generators write none), Meson's, `ninja -t compdb` filtered to sources, or a `make -nwB` dry run. For
Autotools the dry run holds the generated files old (`-o Makefile -o config.status -o <rel>/configure ...`) so
configure is not re-run; the output is split into commands and words without a shell (`$depbase`, escaped quotes,
`&&\` continuations) and dependency-file options are dropped. Bear (an external GPL tool, only run) is used only
with `compileCommands=bear`, around a forced make with the same files held old. `publish=copy` (default) copies the
database to the project directory, `clangd-config` writes `.clangd` with
`CompileFlags: CompilationDatabase: target/native/<key>`, `none` leaves it in the build tree. What the plugin
publishes is recorded as a SHA-256 in `<project>/.m3/native-build`, which `mvn clean` keeps: a later build updates
its own file, and a file the plugin did not write (or that was edited since) is kept with a warning. Eclipse m2e
runs this goal on project configuration (`META-INF/m2e/lifecycle-mapping-metadata.xml`) and ignores the others.

## CMake install

`stage` runs `cmake --install <build> --prefix <prefix>` (`native.autotools.prefix`, default `/usr/local`) with
`DESTDIR=<stage>` in its environment, the same staging as `make install DESTDIR=`: files land in
`<stage>/<prefix>`, which is what `attach` zips. An absolute `install(... DESTINATION /etc/...)`, which ignores the
prefix, lands in `<stage>/etc/...` instead of the real system; such files are named in a warning and not attached.
A destination that climbs out of the stage with `..` cannot be kept in by DESTDIR, so the install is refused before
it runs when the generated `cmake_install.cmake` scripts spell such a destination, and fails afterwards when
`install_manifest.txt` (which lists the installed files without DESTDIR) names a file outside the stage.

## Make, Ninja and Autotools

Hand-written makefiles and build.ninja files build in place, in the source tree. Targets (`make.targets`) and the
test target (`make.testTarget`) must be plain names (letters, digits, `_ . + / @ ~ -`, no leading `-`): GNU make
reads `NAME:=$(shell ...)` and `--eval=` arguments as code and Ninja reads `-t` as a tool. The same checks apply to
the generator, preset, build type, makefile and Autotools prefix; the prefix (`native.autotools.prefix`, also the
CMake install prefix) is an absolute path whose segments are neither `.` nor `..` nor end with a dot, and the stage
root `<stage>/<prefix>` is checked to stay inside the stage, also through links. A makefile's install target runs
only when the makefile, without its comments, uses `$(DESTDIR)` or `${DESTDIR}`: an install rule that ignores it
would write outside the project, so `stage` fails with the reason unless `outputs` are declared to stage instead.
This is a heuristic: an install rule must use DESTDIR for every file it installs. Make and Autotools installs are
refused (with the same fallback) when the stage path holds spaces or shell characters (also `[ ] { } ~ !`, which
Git's bash expands), which make passes unquoted to the shell. autoreconf
has no out-of-tree mode and writes into the source tree; what it created is recorded for the clean goal.

## Clean

`clean` (phase clean, after maven-clean-plugin removed `target`) runs `make clean` when the makefile declares it,
or `ninja -t clean`, and deletes `.ninja_log`/`.ninja_deps`, a CMake preset binaryDir outside `target` (only when
it holds a CMakeCache.txt), the files and folders autoreconf created, the published `compile_commands.json` or
`.clangd` (only while unchanged since the plugin wrote it), the plugin's stamps (`.m3/polyglot-stamps/native-*`)
and its records, then the `.m3` folders when empty. Other tools' stamps stay. `make clean` gets the same PATH
prefix as the make steps (Git's `usr/bin` on Windows, for `rm`). A recorded autoreconf path is deleted only when it
is relative, without `.` or `..` segments, inside the source directory, outside `target` and VCS folders, and no
link lies on its way; other lines are skipped with a warning (`.m3` survives `mvn clean`, so the record is not
trusted further). Every tree the plugin deletes (stage, preset binaryDir, dependency folders, provisioned tools) is
deleted without following symbolic links or Windows junctions: a link is removed as the link.

## Windows

The Visual Studio generator and clang-cl with Ninja find MSVC without vcvars; the vcvarsall + cmd route of
maven-native is not used because it interpolates into a shell. Windows hands a program one command line that the
program splits itself; `ProcessBuilder` does not escape inner quotes, so each argument is quoted here by the rules
of the MSVC runtime (`CommandLineToArgvW`): an argument with quotes, spaces, trailing backslashes or nothing at all
arrives unchanged. A `.cmd` or `.bat` tool runs inside cmd.exe, so an argument with `" % ! ^ & | < >` or a line break
is refused for it. A tool counts as a batch file by its name without trailing dots, spaces or a `:stream` suffix
(which Windows ignores, so `fake.cmd.` still starts cmd.exe) and by the real name of the file it opens (8.3 short
names, links); a configured tool path spelled with a trailing dot or space, or as an 8.3 name, is replaced by that
real name. `sh` is Git for Windows' sh (on PATH, in its default folders or next to `git.exe`) before any
bash, and never the WSL launcher in `System32`; make and configure steps get its folder (Git's `usr/bin`, with
`mkdir`, `cp`, `rm`) in front of PATH, because GNU make starts recipe lines without shell syntax itself. GNU make is
passed `SHELL=<sh>` as one argument. Paths given to native tools use the mixed form `C:/a/b`.

## Output, timeouts and jobs

Native output goes through Maven's logger at info level: it is in the `-l` log file, silenced by `-q`, and under
`-T` each line starts with `[<artifactId>]`, as do the plugin's step headlines and its REPORT, STAGE and ATTACHED
lines. What the step shim's JVM prints from Java code goes to the step log too; only what the JVM launcher prints
before `main` (such as the `Picked up JAVA_TOOL_OPTIONS` notice) still reaches the console, because the polyglot
runner starts the shim with inherited output (polyglot follow-up: capture task output in TaskRunner). Each step may run 4 hours by default (`native.timeoutSeconds`, 1 s to
12 h). `jobs=0` means the processors divided by the reactor's threads (`-T 4` on 32 processors gives 8 per module).

## One owner per build descriptor

The polyglot plugin's Mavenizer maps CMakeLists.txt, meson.build and makefiles into rows of `m3-build.tsv` that the
polyglot plugin runs. A module builds its native descriptors either through that route or through this plugin,
never both: a module with this plugin whose polyglot manifest has a task running cmake, ctest, meson, make, ninja,
autoreconf or configure fails with the rows to remove (`detect` lists them). The manifests are found the way the
polyglot `run` and `plan` goals find theirs: every `<manifest>` configured for the polyglot plugin or one of its
executions, the `m3.polyglot.manifest` property (user, system, then project properties), and always
`${project.basedir}/m3-build.tsv`. This plugin writes no
`m3-build.tsv` (the former `polyglot-tasks` goal is gone). Polyglot follow-up, outside this module: the Mavenizer
should leave such descriptors to this plugin (emit a pom that uses it) instead of mapping them itself.

`CapturedProcess` is the plugin's one process launcher (the step shim and the probes: `--version`, vswhere,
`ninja -t compdb`, the make dry run, the native clean). The polyglot runner starts tools with inherited output and
no environment, which is why the shim exists; when the core ProcessLauncher service (hsoliwal/maven branch
`core/process-launcher`, not yet published) lands, `CapturedProcess` adapts to it and the shim can go.

## Layout of the code

`org.apache.maven.plugins.nativebuild.engine` is plain JDK code (System.Logger, no Maven API): detection, planners
per build system, names and checks, the manifest and step files, the polyglot adapter (`PolyglotEngine`, the only
class that imports the engine), `StepExecutor`/`StepShim`/`LogTail` for running, Windows quoting, tool resolution,
fingerprint, runtime detection, native dependencies, CMake archive pins, reports, the compile database, the build
digest, the clean plan and the stage. `org.apache.maven.plugins.nativebuild` holds the goals. Contract tests
(`NativeBuildContracts`, run by exec-maven-plugin in verify) check detection, classifiers, exact argument lists per
build system and OS, manifest round trips, incremental runs and argument lists with real processes through the
engine and the shim, report conversion, the make dry-run parser, publishing, the stage and zip bytes, and a
regression check per review finding. Donor decisions are in `FOSS_REUSE_DECISION.tsv`.

## Not covered by runs on this machine

Meson, Bear, nmake inside a developer prompt, macOS and Linux aarch64 were not available; their planners and parsers
are covered by contracts only. Autotools needs autoreconf, which ran only in WSL in an earlier round; it is not
installed on Windows here.
