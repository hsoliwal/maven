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

An `AtomFrontEnd` for the [code clustering plugin](../code-clustering/README.md). It lets each language be split
into atoms by its own language core, through a language server and Eclipse LSP4J. Examples:

| Language | Server | Eclipse project that uses it |
|---|---|---|
| C, C++ | clangd | CDT (cdt-lsp) |
| Rust | rust-analyzer | Corrosion |
| Java | Eclipse JDT LS | JDT |
| XML, HTML, CSS, JSON, YAML | LemMinX and the Wild Web Developer servers | WTP / Wild Web Developer |
| Python | pyright, pylsp | (PyDev users can run either) |
| Go, Kotlin, C#, TypeScript | gopls, kotlin-language-server, csharp-ls, typescript-language-server | |

For each file, the server's `textDocument/documentSymbol` answer sets the atom boundaries. Class, interface,
struct, enum and object symbols become TYPE atoms; method, function and constructor symbols become METHOD atoms. The
plugin's shared rules (`LexicalAtoms.rangeAtom`) then compute the four planes from the tokens in each range. A
qualified name such as `Point::norm` is split into owner and name.

## Use

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

```
mvn code-clustering:cluster -Dclustering.sourceDirectories=src/main/c "-Dclustering.lsp.servers=c,h,cpp,hpp=clangd"
```

`clustering.lsp.servers` (or the environment variable `CLUSTERING_LSP_SERVERS`) maps extensions to a server
command: `c,h=clangd;rs=rust-analyzer;py=pyright-langserver --stdio`. The command is split on spaces and run
without a shell. On Windows, use `cmd /c <launcher>` for a `.cmd` launcher. One server process runs per command
and source root. Every request waits at most `clustering.lsp.timeoutSeconds` (default 60). If a server cannot
start or answer, its files go to the lexical front end, and a warning is logged.

## Build and test

`mvn verify` runs `LspFrontEndTest`, which:

- runs an in-process LSP4J server over a loopback socket: hierarchical document symbols, TYPE and METHOD atoms, a
  qualified method name, and a renamed copy that shares the logic plane;
- checks the fallback to the lexical front end when the server binary is missing;
- checks the configuration parser and service discovery;
- if clangd is on the PATH, atomizes a C++ file through it (a struct, a method and two functions, with the
  renamed copy sharing logic). Otherwise this part prints `SKIP`.

## Notices

This module depends on Eclipse LSP4J (`org.eclipse.lsp4j`, EPL-2.0) and Gson (Apache-2.0) as binary
dependencies. The language servers are separate programs under their own licenses. They are run, not linked.
