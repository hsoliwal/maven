---
title: Process Launcher
date: 2026-11-17
---

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

# Process Launcher

`org.apache.maven.api.services.ProcessLauncher` (experimental, since 4.2.0) starts external processes and forked
JVMs for Maven 4 plugins on `java.lang.ProcessBuilder`. It covers what plugins took from the `cli` packages of
plexus-utils and maven-shared-utils (`Commandline`, `CommandLineUtils`, the `Shell` classes, `JavaTool`):

- per-shell command lines and quoting (`cmd.exe`, PowerShell, `sh`, `bash`), chosen through `OsService`;
- environment: inherit or clear, then set or remove variables;
- working directory, checked with the messages of `Commandline.execute()`;
- standard input feeding and output and error line pumping, with a charset;
- timeouts: `destroy()` of the process and its descendants, then `destroyForcibly()` after a grace period;
- destroy on JVM shutdown through a shutdown hook, removed when the process ends;
- exit-code results (`ProcessLaunchResult`), and a handle for processes that run beside the build (`LaunchedProcess`);
- Java tool lookup: `Toolchain.findTool`, then `java.home`, `JDK_HOME`, `JAVA_HOME`, as maven-shared-utils
  `AbstractJavaTool` does.

The implementation is `org.apache.maven.impl.process.DefaultProcessLauncher`, a `@Named @Singleton` component in
`maven-impl`. plexus-utils and maven-shared-utils are not changed: plugins opt in.

## Usage

```java
ProcessLauncher launcher = session.getService(ProcessLauncher.class);
ProcessLaunchResult result = launcher.execute(ProcessLaunchRequest.builder()
        .session(session)
        .tool("javadoc")                      // or .executable("git")
        .toolchain(toolchain)                 // asked first for the tool
        .arguments("-d", target.toString())
        .maskedArgument(passphrase)           // shown as ***** by describe()
        .workingDirectory(basedir)
        .environment("LC_ALL", "C")           // a null value removes the variable
        .outputConsumer(log::info)
        .errorConsumer(log::warn)
        .timeout(Duration.ofMinutes(10))
        .build());
if (result.isTimedOut() || result.getExitCode() != 0) {
    throw new MojoException("javadoc failed: " + launcher.describe(result.getRequest()));
}
```

`start(request)` returns a `LaunchedProcess` instead of waiting: streams without a consumer, and standard input
when the request has none, are open to the caller through `process()`; `await()` waits with the request timeout and
`close()` destroys the process if it still runs. `commandLine(request)` returns the exact argument list handed to
`ProcessBuilder`, and `tokenize(String)` splits a command line such as surefire's `argLine`.

## Shells

| `Shell` | Command line | Quoting of each item |
|---|---|---|
| `NONE` (default) | the program and its arguments, as given | none: `ProcessBuilder` passes them on |
| `DEFAULT` | `CMD` when `OsService.isWindows()`, otherwise `SH`, as `Commandline` does | |
| `CMD` | `cmd.exe /X /C "<line>"` | `"..."` around an item with a space, tab or one of `& < > ( ) @ ^ \|`, unless it is already quoted |
| `SH`, `BASH` | `/bin/sh -c '<line>'`, `/bin/bash -c '<line>'`; `cd '<dir>' && ` first with a working directory | `'...'` around every item, `'` written as `'"'"'` |
| `POWERSHELL` | `powershell.exe` (`pwsh` off Windows) `-NoProfile -NonInteractive -Command & <line>` | `'...'` around every item, `'` doubled; on Windows `"` also gets a backslash for the `powershell.exe` command line |

Through a shell the executable gets the separators of the target OS, as `Shell.setExecutable` does. Without a shell
on Windows, Java does not escape a `"` inside an argument; such arguments need the shell, or escaping by the caller.

## Parity with the code it replaces

The tests in `impl/maven-impl/src/test/java/org/apache/maven/impl/process` compare the launcher with
maven-shared-utils 3.5.0 and plexus-utils 4.2.0 (test scope) on the same inputs and write the tables
`target/process-launcher-parity/*.tsv`.

| Reference | Compared | Equal |
|---|---|---|
| maven-shared-utils `Commandline` with `CmdShell` | 26 arguments x 2 shapes (bare; path with spaces, working directory) | 52/52 |
| maven-shared-utils `Commandline` with `BourneShell` | same | 52/52 |
| maven-shared-utils default `Commandline` (host OS) | same | 52/52 |
| maven-shared-utils masked `Commandline.toString()` | `describe` with a masked argument | equal |
| plexus-utils `Commandline` with `BourneShell` | same | 52/52 |
| plexus-utils `Commandline` with `CmdShell` | quoted line | 36/52: plexus adds `/D` and quotes only on a space, so `a&b`, `a\|b`, `a<b`, `a>b`, `(paren)`, `@file`, `a^b` and a tab reach `cmd.exe` unquoted |
| maven-shared-utils `CommandLineUtils.translateCommandline` | 21 command lines incl. unbalanced quotes | 21/21 |
| plexus-utils `CommandLineUtils.translateCommandline` | same | 17/21: plexus also splits on tab and newline and gives `\` no meaning |
| surefire 3.5 fork command line on maven-shared-utils `Commandline` | `argLine` with tabs and newlines, debug line, classpath; environment with a null value | arguments, shell line and environment equal |
| maven-shared-utils `AbstractJavaTool.findJavaToolExecutable` | `java`, `javac`, `javadoc`, `jar`, unknown tool, with and without toolchain | 11/11 |
| maven-shared-utils `CommandLineUtils.executeCommandLine` (Windows, run) | override of `Path` spelled `pATH` | same child environment |

Deliberate differences from `CommandLineUtils.executeCommandLine`:

- an expired timeout is `ProcessLaunchResult.isTimedOut()`, not a `CommandLineTimeOutException`;
- destroy also reaches the descendants of the process, so the program behind `cmd.exe` or `sh` dies with it, and
  falls back to `destroyForcibly()` after `destroyGracePeriod` (5 s by default);
- `execute` closes standard input when the request has no input, and discards output that has no consumer;
- a stream pump keeps draining after its consumer throws, and the failure is reported after the process exits.

## Migration

The recipe `org.apache.maven.recipes.CommandlineToProcessLauncher` in `tools/code-recipes` marks, in sources that
use the Maven 4 API, each `Commandline` constructor and each `Commandline` and `CommandLineUtils` call of both
libraries with the launcher call that replaces it. It does not rewrite code: a `Commandline` is built over several
statements and its checked exceptions differ.

| plexus-utils / maven-shared-utils | ProcessLauncher |
|---|---|
| `new Commandline()` | `ProcessLaunchRequest.builder().session(session).shell(Shell.DEFAULT)` |
| `setExecutable(exe)`, `addArguments(args)`, `createArg().setValue(v)` | `executable(exe)`, `arguments(args)`, `argument(v)` |
| `createArg().setLine(line)` | `arguments(launcher.tokenize(line))` |
| `createArg().setMask(true)` | `maskedArgument(v)` |
| `addEnvironment(k, v)`, `setShellEnvironmentInherited(false)` | `environment(k, v)`, `inheritEnvironment(false)` |
| `setWorkingDirectory(dir)` | `workingDirectory(path)` |
| `CommandLineUtils.executeCommandLine(cl, out, err, seconds)` | `launcher.execute(request)` with `outputConsumer(out::consumeLine)`, `errorConsumer(err::consumeLine)`, `timeout(Duration.ofSeconds(seconds))` |
| `CommandLineUtils.translateCommandline(line)` | `launcher.tokenize(line)` |
| `Commandline.toString()` | `launcher.describe(request)` |
| `JavaTool` with `setToolchain(toolchain)` | `tool(name).toolchain(toolchain)` |
