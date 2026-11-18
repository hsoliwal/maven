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
# Maven MCP Server

A [Model Context Protocol](https://modelcontextprotocol.io) server on stdio that lets a coding agent build and test
one Maven project and read the results as compact JSON. It is a single runnable jar for Java 17 or newer, built on
the [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) 2.1.0.

## Tools

| Tool | What it does | Runs Maven |
|------|--------------|------------|
| `build` | Runs the given phases or goals (`goals`, optional `projects`, `alsoMake` default true, `properties`, `offline`, `timeoutSeconds`). | yes, numbered run |
| `test` | Runs one selection with Surefire (`-Dtest`) or, with `it: true`, Failsafe (`-Dit.test`), optionally in one `project` and the modules it depends on. | yes, numbered run |
| `effective_pom` | The effective POM of one module (or the root project alone): coordinates, parent, modules, plugins with executions (`id@phase:goals`), dependencies (`g:a:v scope`), file path and SHA-256. Uses maven-help-plugin 3.5.1. | yes, query |
| `dependency_tree` | The resolved tree of one module (or the root project) as depth-annotated rows, optional `scope`, capped at `maxNodes`. Uses maven-dependency-plugin 3.11.0 with JSON output. | yes, query |
| `describe_goal` | One goal's phase, thread safety and parameters (type, user property, default, required, first sentence of the doc). Read from `META-INF/maven/plugin.xml` in the local repository; `help:describe` when the plugin is not there. | only for the fallback |
| `list_modules` | The modules with index, `groupId:artifactId`, path, packaging, version and the reactor modules each depends on, read from the POMs (or from `target/agent/project.json` when the agent plugin wrote it). | no |
| `explain_failure` | The failure digest (`failure.json`) of a run, or why there is none plus the log's first `[ERROR]` lines. | no |
| `build_events` | A page of the build event stream (`build-events.jsonl`) of a run, filtered by `types` and `project`. | no |

Tool annotations: `build` and `test` are not read-only, destructive, not idempotent and open-world (they run any
goal the client names; see Security). `effective_pom`, `dependency_tree` and `describe_goal` are not read-only (they
write the server's query directory, replacing the previous query's output, and may download into the local
repository), not destructive, idempotent and open-world. `list_modules`, `explain_failure` and `build_events` are
read-only.

Every answer is one line of JSON in the tool result's text content. Strings are capped (messages at 300 characters),
lists are capped and say how many items were left out (`diagnosticsOmitted`, `nodesOmitted`, ...), and file paths
point at the full data. A refused or failed call is a tool result with `isError: true` and `{"error": "..."}`; the
session stays up. The tool descriptions and input schemas are in
[`tools.json`](src/main/resources/org/apache/maven/mcp/tools.json).

A failed `build` without the build-events extension answers like this (run against the test fixture):

```json
{"run":2,"outcome":"failure","exitCode":1,"timedOut":false,"durationMs":8478,
 "events":{"file":".../runs/2/build-events.jsonl","available":false,"reason":"build-events.jsonl was not written: no build-events extension ran in this build (start the server with --extension <jar>, or use a Maven distribution that has the extension in lib/ext)"},
 "digest":{"file":".../runs/2/failure.json","available":false,"reason":"..."},
 "logErrors":["COMPILATION ERROR :","/C:/.../fixture/c/src/main/java/fix/c/Broken.java:[23,16] cannot find symbol","..."],
 "files":{"dir":".../runs/2","log":".../runs/2/build.log","console":".../runs/2/console.log"},
 "launcher":{"source":"PATH","script":".../apache-maven-3.9.14/bin/mvn.cmd","javaHome":".../jdk-21"},
 "command":"mvn test-compile"}
```

With the extension, `events` carries `sessionEnd`, the first 20 `diagnostics` and `failedTests`, and `digest` says
whether `failure.json` exists.

## Build and run

```
mvn -f tools/maven-mcp-server/pom.xml verify
java -jar tools/maven-mcp-server/target/maven-mcp-server-0.1.0-SNAPSHOT.jar --root /path/to/project
```

The module builds on its own (it has no parent POM) with JDK 17 or newer, after the build events extension
(`tools/m3-smart-builder/extension`, for `MavenCommands`) is installed; in the root reactor it builds after it.
`verify` runs three test mains through exec-maven-plugin: `ArgumentGuardTest` (including the `.cmd` launcher rules
and the final argument check), `ResultReadersTest` and `McpProtocolTest` (including unreadable input lines, the tool
annotations and, on Windows, the probe `mvn.cmd` and a wrapper `mvnw.cmd`). The protocol test starts the
shaded jar, talks JSON-RPC to it over stdio and runs real Maven builds on a copy of `src/test/resources/fixture`
under `-Dmcp.test.scratch` (default `${java.io.tmpdir}/llm-mcp`). `-Dmcp.test.mavenHome=<dir>` makes it use a
given Maven distribution, and `-Dmcp.test.extension=<jar>` adds a build-events extension, which switches the
build checks from the log fallback to the event stream.

| Option | Default | Meaning |
|--------|---------|---------|
| `--root <dir>` | working directory | The project root (must hold `pom.xml`). Fixed for the life of the server: no tool can point Maven elsewhere. |
| `--maven-home <dir>` | | The Maven distribution to use, for example the fork's own distribution. |
| `--java-home <dir>` | `JAVA_HOME`, else the server's runtime | The JDK Maven runs on (set as `JAVA_HOME` of the child). |
| `--extension <jar>` | | A build-events extension jar for a Maven that does not have it in `lib/ext`; passed as `-Dmaven.ext.class.path`. |
| `--runs-dir <dir>` | `~/.m2/agent-runs/<first 12 hex of SHA-256 of the root path>` | Where run directories go. |
| `--local-repo <dir>` | `localRepository` of `~/.m2/settings.xml`, else `~/.m2/repository` | Where `describe_goal` looks for plugin jars. |
| `--keep-runs <n>` | 5 | How many numbered runs to keep. |
| `--timeout <seconds>` | 900 | Default time limit of one Maven run; `timeoutSeconds` overrides it per call. |

## How Maven runs

Builds run as a **child process of the Maven distribution's own launcher script** (`bin/mvn`, or `bin/mvn.cmd` on
Windows), not through the embedder:

- The server's stdout carries the MCP protocol. An embedded Maven writes to `System.out` and may call
  `System.exit`; a child process writes to `console.log` and `build.log` in its run directory instead.
- A child process can be given a time limit and stopped with its whole process tree; an embedded build cannot.
- The distribution keeps ownership of how Maven starts: its class path, `.mvn/jvm.config`, `MAVEN_OPTS` and the core
  extensions in `lib/ext` (the fork's distribution has the build-events extension there). The server stays a small
  Java 17 jar without maven-core, and the same server drives Maven 3.9 and Maven 4 (the protocol test passes with
  Apache Maven 3.9.14 and 4.0.0-rc-7).

The launcher is found in this order: `--maven-home`, the project's `mvnw` (`mvnw.cmd`), `MAVEN_HOME`, then `mvn`
(`mvn.cmd`) on `PATH`. Each result names the launcher it used.

The command is an argument vector given to `ProcessBuilder`; nothing is joined into a command string. Windows can
only start `mvn.cmd` through cmd.exe, so every client value must also be inert there: goals, projects, property
names and values, and test selections are matched against narrow character sets with no white space, quotes or
`% ! ^ & | < > ( ) $ ; \``, may not start with `-` (so a client cannot add options of its own), and are limited in
length and count. Paths the server adds are checked for the characters cmd.exe expands. The properties the server
sets itself (`maven.agent*`, `maven.events.*`, `style.color`, ...) and `maven.ext.class.path` are refused, and so
are the properties that redirect which configuration Maven reads, where it writes its log or where it connects:
the local repository (`maven.repo.*`), settings and toolchains (`maven.settings*`, `maven.user.*`,
`maven.installation.*`, `maven.project.*`, `settings.*`, `org.apache.maven.*`: the equivalents of `-s`, `-gs` and
`-t`), `maven.config`, `maven.mainClass`, `maven.boot*`, the log file and logger (`maven.logFile`, `maven.logger.*`,
`org.slf4j.*` such as `org.slf4j.simpleLogger.logFile`: the equivalents of `-l`, which truncate any file the server's
user can write or open a UNC path), the build cache (`maven.build.cache.*`), the transports (`maven.wagon.*`), the
console library (`jansi.*`, `library.jansi.*`), the resolver (`maven.resolver.*`, `aether.*`), `classworlds.*`, JVM
and network properties (`user.*` such as `user.home`, `java.*`, `javax.*` such as a trust store, `jdk.*`, `sun.*`,
`http.*`, `https.*`, `ftp.*`, `socks*`, the legacy `proxy*`) and the build hook's switches (`chromellm.*`,
`m3.smart.*`, which name programs it starts). A refused call starts no Maven process and creates no run directory.

When the launcher is a `.cmd` script (`mvn.cmd`, `mvnw.cmd`), cmd.exe also splits the script's parameters at `=`
and `,`, and `mvn.cmd` scans those pieces for `-f`/`--file` to find the project's `.mvn/jvm.config` and
`.mvn/maven.config`. A value such as `-f=C:/other` would otherwise point Maven at another directory's configuration.
So through a `.cmd` launcher property values may hold neither `=` nor `,` nor start with `-`, and test selections may
not hold `,` (name several methods as `Class#a+b`, several classes with a pattern such as `*Test`). Before the
launch, the server checks the final argument vector once more the way cmd.exe splits it: no piece after the first of
an argument may start with `-`. Through `bin/mvn` (a POSIX shell script that passes `"$@"`) commas and `=` stay
allowed. The protocol test runs a probe `mvn.cmd` with Maven 3.9's own scan and a `mvnw.cmd` that ends like the Maven
Wrapper's (`"<mvn.cmd>" %*`).

The test tool's arguments (`-Dtest=... -Dsurefire.failIfNoSpecifiedTests=false test`, or for `it: true`
`-Dit.test=... -Dfailsafe.failIfNoSpecifiedTests=false -Dtest=NoUnitTestSelected
-Dsurefire.failIfNoSpecifiedTests=false verify`) come from `MavenCommands` of the build events extension, the one
owner of that spelling, also used by the failure digest's reproduce commands and by `llms.txt`; only that class of
the extension jar is shaded into the server.

Each `build` and `test` call gets a numbered run directory under `--runs-dir`, outside the project's `target`, so
`mvn clean` never deletes it or trips over an open file. The server adds:

```
-B -V -ntp -Dstyle.color=never -l <run>/build.log [-Dmaven.ext.class.path=<extension>] [-o]
-Dmaven.agent=true -Dmaven.agent.dir=<run> -Dmaven.events.file=<run>/build-events.jsonl -Dmaven.events.log=<run>/build.log
```

`run.json` in the directory records the tool, outcome, exit code, duration, launcher and full argument list. The
queries (`effective_pom`, `dependency_tree`, `help:describe`) write into `<runs-dir>/query`, which is emptied before
each one. Only one Maven run is active per server: a second call while one runs is refused at once with "another
Maven run is still active".

## Files the server reads

The build-events extension (part C1/C2 of the LLM-friendly Maven design, in the smart builder's core extension)
writes these into `maven.agent.dir`. The server reads them and parses no compiler or test output itself.

- `build-events.jsonl`: one JSON object per line with `v`, `seq`, `tMs` and `type`. The server uses `type`
  (`session-start`, `project-start`, `project-end`, `mojo-start`, `mojo-end`, `diagnostic`, `test`, `test-suite`,
  `repeat`, `session-end`), `project` (`groupId:artifactId`) and `path` for filters, `outcome` of `test` events
  (`failed` and `error` count as failed tests), and drops `stack` from tests in the `build` summary. Lines that are
  not JSON are counted as `invalidLines`.
- `failure.json`: returned as `digest` by `explain_failure` (at most 1 MiB), with `failure.md` named when present.
- `summary.txt`: named in `files` when present.

Until the extension runs in a build, `build_events` and `explain_failure` answer `available: false` with the
reason, and `build`, `test` and `explain_failure` carry `logErrors`: the first 20 non-empty `[ERROR]` lines of
`build.log`, before Maven's help footer. The extension publishes its files when the build ends, so a run stopped at
its time limit has none either; its reason says that Maven was stopped after so many seconds (raise
`timeoutSeconds`), not that no extension ran. `list_modules` numbers modules from 1, like the events and
`project.json`.

## Protocol input

Stdin is read line by line on its own thread, and only lines the SDK can read as JSON-RPC messages reach it (the
SDK's reader stops for good at the first line it cannot read). A line that is not JSON is answered with error
`-32700` and `id: null`; JSON that is not a message, or a line longer than 16 Mi characters (the SDK's limit), with
`-32600` and its `id` when it has one. The session goes on. The end of stdin, a read error, or an SDK reader that went
away end the session: the server stops its Maven runs and exits 0. Responses go out one whole line at a time, so an
error answer never interleaves with an SDK message.

## Security

`build` and `test` run whatever phases and plugin goals the client names, with the client's properties, in the
project the server was started for. That is arbitrary process execution with the server's user rights: a plugin goal
such as `exec:exec` runs any program, `clean` deletes the build output, `deploy` publishes. The character checks
above keep a client from adding Maven options of its own, and the refused properties keep it from pointing Maven at
other settings, toolchains, a local repository, a log file, a trust store or a proxy by a property; neither limits
what the named goals do (a goal may itself read any file the server's user can read). Start the server only for
projects and clients you trust, let the MCP client ask before each `build` and `test` call (they are marked
destructive), and run it in a container or a throwaway checkout when the client is not supervised.

Every string in an answer, at any depth, and every error message pass the build-events extension's credential
redaction (`Redact.secrets`, shaded into the jar, the same code that cleans the agent files): the userinfo of a URL
becomes `***@` whatever precedes the scheme (`1https://`, `%22https://`) and also when the password holds `/` or
`@`; the value of a `password=`, `passwd=`, `pwd=`, `passphrase=`, `token=`, `secret=`, `api_key=`, `access_key=`,
`private_key=`, `login=`, `credentials=` or `signature=` pair (any case, as in a JDBC URL) becomes `***`; so does an
`Authorization:` header value and a bearer token. This holds also in the raw `[ERROR]` lines of the Maven log that
`build`, `explain_failure`, `effective_pom`, `dependency_tree` and `describe_goal` return, and a capped line is
redacted before it is cut.

## Client configuration

The server speaks MCP over stdio, so any MCP client that can start a local command can use it. Use absolute paths;
`--root` defaults to the directory the client starts the server in.

**Claude Code**, from the command line (`--scope project` writes `.mcp.json` for the team):

```
claude mcp add maven -- java -jar /opt/maven-mcp/maven-mcp-server-0.1.0-SNAPSHOT.jar --root /work/my-project
```

or in `.mcp.json` at the project root:

```json
{
  "mcpServers": {
    "maven": {
      "command": "java",
      "args": ["-jar", "/opt/maven-mcp/maven-mcp-server-0.1.0-SNAPSHOT.jar", "--root", "/work/my-project"]
    }
  }
}
```

**Claude Desktop**, in `claude_desktop_config.json` (`%APPDATA%\Claude\` on Windows, `~/Library/Application
Support/Claude/` on macOS); JSON needs doubled backslashes in Windows paths:

```json
{
  "mcpServers": {
    "maven": {
      "command": "C:\\Program Files\\Java\\jdk-21\\bin\\java.exe",
      "args": ["-jar", "C:\\tools\\maven-mcp-server-0.1.0-SNAPSHOT.jar", "--root", "C:\\work\\my-project",
               "--maven-home", "C:\\tools\\apache-maven-4.2.0-SNAPSHOT"]
    }
  }
}
```

**VS Code** (agent mode), in `.vscode/mcp.json`:

```json
{
  "servers": {
    "maven": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/opt/maven-mcp/maven-mcp-server-0.1.0-SNAPSHOT.jar", "--root", "${workspaceFolder}"]
    }
  }
}
```

**Eclipse**: LSP4E is a client for the Language Server Protocol, not for MCP, so it cannot use this server. The
fork's language-server front end for Eclipse and other LSP clients is
[code-clustering-lsp](../code-clustering-lsp/README.md); an Eclipse plug-in with its own MCP client could start this
server with the same command line.

## Third-party software

The runnable jar shades the MCP Java SDK (MIT), Jackson (Apache-2.0), Reactor Core (Apache-2.0), Reactive Streams
(MIT-0), networknt json-schema-validator (Apache-2.0), ethlo itu (Apache-2.0), SnakeYAML (Apache-2.0) and SLF4J
(MIT). The list with versions and the MIT texts are in
[`META-INF/THIRD-PARTY-NOTICES.txt`](src/main/resources/META-INF/THIRD-PARTY-NOTICES.txt); reuse decisions are in
[`FOSS_REUSE_DECISION.tsv`](FOSS_REUSE_DECISION.tsv).
