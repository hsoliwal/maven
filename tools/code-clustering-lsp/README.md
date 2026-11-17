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
# Code Clustering: Language Server Front End

An `AtomFrontEnd` for the [code clustering plugin](../code-clustering/README.md), and a
[language server](#language-server) that shows the plugin's duplicates in any LSP client. The front end lets each
language be split into atoms by its own language core, through a language server and Eclipse LSP4J. Each Eclipse
language core connects with one switch, `-Dclustering.lsp.profiles=jdt,cdt,pydev,wtp,rust` (or `all`).

For each file, the server's `textDocument/documentSymbol` answer sets the atom boundaries, and the plugin's shared
rules (`LexicalAtoms.rangeAtom`) compute the four planes from the tokens in each range.

## Use

Add the module as a dependency of the plugin:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-code-clustering-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <dependencies>
    <dependency>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-code-clustering-lsp</artifactId>
      <version>0.1.0-SNAPSHOT</version>
    </dependency>
  </dependencies>
</plugin>
```

Then pick profiles:

```
mvn code-clustering:cluster -Dclustering.sourceDirectories=src/main/cpp -Dclustering.lsp.profiles=cdt
```

## Profiles

`clustering.lsp.profiles` (or `CLUSTERING_LSP_PROFILES`) is a list of profile names separated by commas or spaces,
or `all`. An unknown name is ignored with a warning. Each profile is one Eclipse language core:

### JDT (Java)

| | |
|---|---|
| Profile | `jdt` (alias `java`); server id `jdt`; extensions `java` |
| Server | Eclipse JDT Language Server (`jdtls`) |
| Install | Unpack `jdt-language-server-<version>.tar.gz` from download.eclipse.org/jdtls. 1.63 needs Java 25; 1.61 is the last release that runs on Java 21. |
| Found by | `clustering.lsp.jdt.home` / `JDT_HOME` (the unpacked directory), else the install whose `bin/jdtls` (Windows: `bin/jdtls.bat`) is on the PATH |
| Example | `mvn code-clustering:cluster -Dclustering.lsp.profiles=jdt -Dclustering.lsp.jdt.home=/opt/jdtls -Dclustering.lsp.jdt.java=/opt/jdk-25 -Dclustering.lsp.jdt.data=/var/tmp/jdtls-data` |

The command is the one jdtls's own script builds: `java -Declipse.application=org.eclipse.jdt.ls.core.id1 ...
-jar plugins/org.eclipse.equinox.launcher_*.jar -configuration config_win|config_linux|config_mac -data <dir>`, with
the `-Djdk.xml.*=0` flags on Java 24 and later. The Java it needs is 21, or the `Bundle-RequiredExecutionEnvironment`
of the install's `org.eclipse.jdt.core` bundle when that is newer. The first Java that is new enough is used, from
`clustering.lsp.jdt.java` (`CLUSTERING_LSP_JDT_JAVA`: a JDK directory or its `java`), then `JAVA_HOME`, then the
Java that runs Maven; if none is, the profile is skipped with a warning that names the version. `-data` is
`clustering.lsp.jdt.data` (`CLUSTERING_LSP_JDT_DATA`), else `code-clustering-jdtls-data` in the temporary
directory. A running jdtls locks that directory, so give each concurrent build its own, and keep it between builds:
the first start indexes and takes longest. JDT LS requests wait up to 180 s by default. Without the profile, Java
files go to the plugin's built-in javac front end.

### CDT (C, C++)

| | |
|---|---|
| Profile | `cdt` (aliases `c`, `cpp`); server id `cdt`; extensions `c h cc cpp cxx hpp hh hxx` |
| Server | clangd (the server behind CDT's LSP support, cdt-lsp) |
| Install | An LLVM release or the system package (`clangd`) |
| Found by | `clustering.lsp.cdt.home` / `CDT_HOME` (the LLVM directory: `bin/clangd` or `clangd`), else `clangd` on the PATH |
| Example | `mvn code-clustering:cluster -Dclustering.sourceDirectories=src/main/cpp -Dclustering.lsp.profiles=cdt` |

No `compile_commands.json` is needed for document symbols.

### PyDev (Python)

| | |
|---|---|
| Profile | `pydev` (alias `python`); server id `pydev`; extensions `py pyi` |
| Server | pylsp (python-lsp-server), else pyright. PyDev has no language server of its own; Eclipse users run one of these through LSP4E. |
| Install | `python -m venv <dir>` then `<dir>/bin/pip install python-lsp-server` (Windows: `<dir>\Scripts\pip`); or `npm install --prefix <dir> pyright` |
| Found by | `clustering.lsp.pydev.home` / `PYDEV_HOME`: a virtual environment (`Scripts/pylsp.exe`, `bin/pylsp`) or an npm prefix (`node_modules/pyright/langserver.index.js`, run with `node` from the PATH); else `pylsp`, then `pyright-langserver`, on the PATH |
| Example | `mvn code-clustering:cluster -Dclustering.sourceDirectories=src/main/python -Dclustering.lsp.profiles=pydev -Dclustering.lsp.pydev.home=$HOME/venvs/pylsp` |

### WTP / Wild Web Developer (XML, HTML, CSS, JSON)

| | |
|---|---|
| Profile | `wtp` (aliases `xml`, `html`, `css`, `json`, `web`) |
| Servers | LemMinX (id `wtp.xml`: `xml xsd xsl`); the vscode-langservers-extracted HTML, CSS and JSON servers (ids `wtp.html`: `html htm`; `wtp.css`: `css scss less`; `wtp.json`: `json jsonc`) |
| Install | The LemMinX uber jar `org.eclipse.lemminx-<version>-uber.jar` from repo.eclipse.org (lemminx-releases), or a native `lemminx` binary; `npm install --prefix <dir> vscode-langservers-extracted` |
| Found by | LemMinX: the uber jar in `clustering.lsp.wtp.xml.home` / `WTP_XML_HOME` (run with the Java that runs Maven), else `lemminx` on the PATH. The others: an npm prefix in `clustering.lsp.wtp.<language>.home`, else `clustering.lsp.wtp.home` / `WTP_HOME` (run with `node` from the PATH), else `vscode-<language>-language-server` on the PATH (on Windows, the script next to its `.cmd` launcher, run with `node`) |
| Example | `mvn code-clustering:cluster -Dclustering.sourceDirectories=src/main/webapp -Dclustering.lsp.profiles=wtp -Dclustering.lsp.wtp.xml.home=/opt/lemminx -Dclustering.lsp.wtp.home=/opt/lsp/npm` |

Each server is found on its own; one that is missing is skipped with a warning while the others run.

### RustDT / Corrosion (Rust)

| | |
|---|---|
| Profile | `rust` (aliases `rustdt`, `corrosion`); server id `rust`; extensions `rs` |
| Server | rust-analyzer (Eclipse Corrosion, the successor of the discontinued RustDT) |
| Install | `rustup component add rust-analyzer`, or a release binary from GitHub |
| Found by | `clustering.lsp.rust.home` / `RUST_HOME` (`bin/rust-analyzer` or `rust-analyzer`), else `rust-analyzer` on the PATH |
| Example | `mvn code-clustering:cluster -Dclustering.sourceDirectories=src -Dclustering.lsp.profiles=rust` |

Document symbols need no `Cargo.toml`; without one, rust-analyzer only logs that it found no workspace.

### How a server's command is found

For each server id (`jdt`, `cdt`, `pydev`, `wtp.xml`, `wtp.html`, `wtp.css`, `wtp.json`, `rust`), in this order:

1. the system property `clustering.lsp.<id>.command`, for example
   `"-Dclustering.lsp.cdt.command='C:/Program Files/LLVM/bin/clangd.exe' --log=error"` (see [Quoting](#quoting));
2. the environment variable `CLUSTERING_LSP_<ID>_COMMAND` (`.` becomes `_`: `CLUSTERING_LSP_WTP_HTML_COMMAND`);
3. detection: the home directories (`clustering.lsp.<id>.home`, then `<ID>_HOME`; for a WTP server also
   `clustering.lsp.wtp.home`, then `WTP_HOME`), then the PATH, as in the tables above.

A server that is not found is skipped with one warning, and its files go to the built-in front ends. Requests wait
at most `clustering.lsp.<id>.timeoutSeconds`, else `clustering.lsp.timeoutSeconds`, else 60 s (180 s for `jdt`).
The language servers run as separate processes, without a shell; one process runs per server and source root.

### Explicit servers

`clustering.lsp.servers` (or `CLUSTERING_LSP_SERVERS`) maps extensions to any server command and wins over the
profiles for the extensions it names:

```
-Dclustering.lsp.servers=c,h=clangd;rs=rust-analyzer;go=gopls
```

Extensions come before `=`, the command after it. A profile server whose extensions are all named is not looked for.
Two entries with the same command share one process.

### Quoting

Commands are split into arguments without a shell. Double or single quotes keep spaces, so a path such as
`"C:/Program Files/LLVM/bin/clangd.exe"` works on every platform; a quoted part joins the text next to it
(`-data="J:/my workspace"` is one argument), `""` is an empty argument, and a backslash is an ordinary character.
A `;` inside quotes does not end a `clustering.lsp.servers` entry. An entry with an unclosed quote is skipped with a
warning. On the command line the quotes have to reach Java. Single quotes inside a double-quoted argument work in
every shell; on Windows, `mvn.cmd` rejects double quotes inside an argument ("The syntax of the command is
incorrect"), and the environment variable avoids command-line quoting altogether:

| Shell | Example |
|---|---|
| bash, zsh | `"-Dclustering.lsp.servers=cpp='/opt/my llvm/bin/clangd' --log=error"` |
| cmd.exe | `"-Dclustering.lsp.servers=cpp='C:/Program Files/LLVM/bin/clangd.exe' --log=error"`, or `set "CLUSTERING_LSP_SERVERS=cpp="C:/Program Files/LLVM/bin/clangd.exe" --log=error"` |
| Windows PowerShell | `"-Dclustering.lsp.servers=cpp='C:/Program Files/LLVM/bin/clangd.exe' --log=error"`, or `$env:CLUSTERING_LSP_SERVERS = 'cpp="C:/Program Files/LLVM/bin/clangd.exe" --log=error'`. Quote every `-D` argument: PowerShell splits an unquoted `-Dclustering.sourceDirectories=x` at the first dot. |

Shell features (`&&`, `|`, `$HOME`, `>`) are plain arguments. To run a Windows `.cmd` launcher, name `cmd /c` in
the command, or let the profile find the script next to the launcher.

## Which symbols become atoms

| Languages | TYPE atoms | METHOD atoms | Names |
|---|---|---|---|
| Java, C, C++, Python, Rust and every other language | Class, Interface, Struct, Enum and Object symbols | Method, Function and Constructor symbols | A qualified name (`Point::norm`, `sum(int[])`) is split into owner and name |
| XML, HTML | an element with child elements (LemMinX and the HTML server report elements as Field symbols) | functions in embedded scripts | kept as reported (`div#main.card`) |
| CSS, SCSS, Less | every rule (Class symbols), nested or not; `@media` and other at-rules are not atoms, the rules inside them are | functions and mixins (Function, Method) | kept as reported (`.card .title`) |
| JSON | an object with members (a Module or Object symbol with children); arrays and values are not atoms, objects inside arrays are | none | kept as reported |

Leaf elements, attributes, the XML declaration, fields, variables and enum members are not atoms; the children of a
symbol that is not an atom are still visited. A TYPE atom owns the atoms inside it: `project.dependencies.dependency`
for nested XML elements, `Calc.sum(1)` for a method. Servers that answer with the flat symbol form (pylsp and the
HTML server) give only the immediate container as the owner (`body.div#main.card`), and an element there has children
when another symbol's range lies inside its own. rust-analyzer reports `impl` blocks as Object symbols named
`impl Point`, so their methods are `impl Point.norm(1)`; clangd reports a method's in-class declaration and its
out-of-line definition separately, so both are METHOD atoms.

These results were checked against real servers (see [Build and test](#build-and-test)): Eclipse JDT LS 1.63.0 on
Java 25, clangd 22.1.0, pylsp 1.15.0, pyright 1.1.414, LemMinX 0.31.2, vscode-langservers-extracted 4.10.0 and
rust-analyzer 2026-11-15.

## Language server

The module also holds a code-clustering language server built on LSP4J (`ClusteringLanguageServer`). Any LSP client
shows the plugin's duplicates with it, next to the language's own server: Eclipse LSP4E in the JDT, CDT, PyDev,
WTP / Wild Web Developer and Rust (Corrosion) editors, VS Code, Neovim, Emacs and others.

- It scans the workspace folders (or the root URI) with every discovered front end (javac, the lexical front end,
  and this module's LSP front end when `clustering.lsp.profiles` or `clustering.lsp.servers` is set, as system
  properties of the server's JVM): after `initialized`, after each
  `textDocument/didSave`, and after a watched source file changes (it registers a file watcher when the client
  supports dynamic registration). Scans run one at a time. Files are read from disk, so unsaved edits count from
  the next save.
- Atoms of the same kind with at least `minTokens` logic tokens (default 12) that share the EXACT plane, or else the
  LOGIC plane, get one diagnostic each: severity Information, source `code-clustering`, code `EXACT` or `LOGIC`,
  on the atom's lines, with a message that names the other members, for example
  `Same logic as q/B.java#B.sum(int[]) and 2 more`. An atom in both kinds of group gets the EXACT diagnostic only.
  A file whose duplicates are gone gets an empty diagnostics list.
- It advertises document sync for open, close and save only (no content changes), and no other feature.
- `minTokens` comes from the initialization options (`{"minTokens": 20}`), else `-Dclustering.minTokens=20`.

### Running it

`ClusteringServerMain` speaks LSP on standard input and output (`--stdio` is accepted and ignored), or, with
`--port N`, on the loopback socket 127.0.0.1:N for one client. It needs a JDK (the javac front end uses the
system Java compiler) and this class path:

| Jar | Where |
|---|---|
| `maven-code-clustering-lsp-0.1.0-SNAPSHOT.jar` | this module's `target`, or the local repository after `mvn install` |
| `maven-code-clustering-plugin-0.1.0-SNAPSHOT.jar` | `../code-clustering/target`, or the local repository |
| `org.eclipse.lsp4j-0.23.1.jar`, `org.eclipse.lsp4j.jsonrpc-0.23.1.jar` | `org/eclipse/lsp4j/...` in the local repository |
| `gson-<version>.jar` (2.9.1 or later) | `com/google/code/gson/gson/...` in the local repository |

Maven prints the dependency jars:

```
cd tools/code-clustering-lsp
mvn dependency:build-classpath -Dmdep.includeScope=compile -Dmdep.outputFile=target/server.classpath
java -cp "target/maven-code-clustering-lsp-0.1.0-SNAPSHOT.jar:$(cat target/server.classpath)" \
    org.apache.maven.plugins.clustering.lsp.ClusteringServerMain
```

On Windows, separate the class path entries with `;` instead of `:`.

### Eclipse (LSP4E)

If Eclipse LSP4E is not installed yet, install it from the Eclipse release update site.

1. **Run > External Tools > External Tools Configurations...**: create a **Program** launch configuration.
   Location: the `java` executable of a JDK. Arguments: `-cp <class path> org.apache.maven.plugins.clustering.lsp.ClusteringServerMain`.
2. **Window > Preferences > Language Servers**: **Add...** a mapping from a content type to that launch
   configuration (mode `run`). Add one mapping per content type you edit, for example Java Source File (JDT), the
   C and C++ source and header types (CDT), Python (PyDev), JavaScript or TypeScript (Wild Web Developer / WTP),
   Rust (Corrosion).
3. Open a file of a mapped type. LSP4E starts the server with the project as its root, and shows the diagnostics
   as problem markers on the files. Saving a file rescans.

### Other clients

- **VS Code**: a small extension with `vscode-languageclient`, for example
  `new LanguageClient('code-clustering', 'Code clustering', {command: 'java', args: ['-cp', classPath,
  'org.apache.maven.plugins.clustering.lsp.ClusteringServerMain']}, {documentSelector: [{scheme: 'file'}],
  initializationOptions: {minTokens: 12}}).start()`.
- **Neovim**: `vim.lsp.start({name = 'code-clustering', cmd = {'java', '-cp', class_path,
  'org.apache.maven.plugins.clustering.lsp.ClusteringServerMain'}, root_dir = vim.fs.root(0, {'.git', 'pom.xml'})})`.
- **Any other client** that starts a server command over standard input and output runs the same command. A client
  that connects to a socket uses `--port N` and 127.0.0.1:N.

## Build and test

`mvn verify` runs six test classes, each a main class that prints `PASS`, `FAIL` or `SKIP` lines. `LspFrontEndTest`:

- runs an in-process LSP4J server over a loopback socket: hierarchical document symbols, TYPE and METHOD atoms, a
  qualified method name, a renamed copy that shares the logic plane, and atom lines taken from the symbol ranges;
- checks the fallback to the built-in front ends when the server binary is missing;
- checks the configuration parser and service discovery;
- if clangd is on the PATH, atomizes a C++ file through it (a struct, a method and two functions, with the
  renamed copy sharing logic). Otherwise this part prints `SKIP`.

`CommandLineTest` checks the quoting rules, and starts a real process (the running `java`, whose path may hold
spaces) from a quoted `clustering.lsp.servers` entry to check the exact arguments it receives, shell characters and
a quoted `;` included.

`ServerProfilesTest` checks, with fake installs in `target/profiles-test`: profile selection and aliases; the order
system property, environment variable, home directory, PATH; one warning for a missing server; explicit entries
winning; timeouts; and detection of clangd, rust-analyzer, pylsp, pyright, LemMinX, the npm servers (an npm prefix,
and the launcher on the PATH) and a JDT LS install, including the Java version it needs.

`SymbolMappingTest` feeds LSP4J `DocumentSymbol` and `SymbolInformation` objects, with the trees and ranges the real
servers returned for its fixture files, through each mapping: code (JDT LS, clangd, pylsp, rust-analyzer), markup
(LemMinX, the HTML server), style (CSS) and data (JSON).

`RealServersTest` runs every profile server end to end when it is installed: it finds the server the way the profile
does, atomizes a fixture file through a session with it, and checks that the session answered (not the fallback) and
that the expected atoms came out; for JDT LS it also checks that no notification was logged as unsupported, and it
runs clangd once more from a quoted `clustering.lsp.servers` entry. A server that is not found prints `SKIP`.
These checks are opt-in, so that whatever servers a machine happens to have on its PATH (for example a CI runner's
rust-analyzer proxy) cannot decide the build: without `-Dclustering.test.servers=<dir>` or
`-Dclustering.test.realServers=true` every server prints `SKIP` with that reason.
`-Dclustering.test.servers=<dir>` adds homes for a directory laid out as `jdtls/`, `py/` (a pylsp virtual
environment), `npm/` (pyright and vscode-langservers-extracted), `lemminx/` and `rust-analyzer/`; `clustering.lsp.*`
properties on the command line win, for example:

```
mvn verify -Dclustering.test.servers=/opt/lsp-servers -Dclustering.lsp.jdt.java=/opt/jdk-25
```

`ClusteringLanguageServerTest` connects a test client to the code-clustering language server over a loopback
socket. The workspace has two Java files (an exact copy, renamed copies) and a JavaScript file (a renamed copy).
It checks the advertised capabilities, the file-watcher registration, and the published codes, lines and messages.
Then it changes files and checks that `didSave` and `didChangeWatchedFiles` update the diagnostics, including an
empty list for a file without duplicates. Last, it checks that `shutdown` and `exit` give exit code 0. It also
checks `minTokens` from the options, the system property and the default, and the `--port` argument.

## Notices

This module depends on Eclipse LSP4J (`org.eclipse.lsp4j`, EPL-2.0) and Gson (Apache-2.0) as binary
dependencies. The language servers are separate programs under their own licenses. They are run, not linked.
