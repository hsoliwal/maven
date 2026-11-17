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
# Apache Maven Code Clustering Plugin

Finds duplicated and similar code in a project's Java sources, and measures how much of one source set another
already holds. It only writes reports. It never changes a source file and never fails a build because of what it
finds.

## How it works

1. **Atoms.** Every type and every function or method becomes an atom with a symbol, for example
   `Owner.method(int[])` in Java or `Owner.method(2)` (name and parameter count) in other languages.
   Two front ends produce atoms:

   | Front end | Languages (extensions) | How |
   |---|---|---|
   | javac | Java (`.java`) | `JavacTask.parse()`: no classpath needed, nothing is compiled |
   | lexical | C, C++, C#, JavaScript, TypeScript (incl. JSX/TSX), Go, Rust, Kotlin, Scala, Swift, PHP, Dart, Groovy (brace blocks); Python (indentation) | A tokenizer that skips comments and keeps literals whole. A block becomes a TYPE atom if its header names a type (class, struct, interface, enum, trait, impl, object, record, union, Go `type X struct`). It becomes a METHOD atom if the header has a name followed by a parameter list, or ends in `=>`. Control statements are not atoms. In Python, `def` and `class` lines open atoms that run to the next line indented as far or less, decorators included |

   More front ends plug in through the `AtomFrontEnd` service interface: add a jar that registers one in
   `META-INF/services/org.apache.maven.plugins.clustering.AtomFrontEnd` as a dependency of the plugin.
   Discovered front ends come before javac and lexical, and each file goes to the first front end that accepts it.
   An adapter over a language core (Eclipse JDT, CDT, PyDev, a language server through LSP4J) walks its own tree
   and feeds `AtomBuilder`, the single owner of the planes. It calls `declare(name)` for the names the atom binds,
   then `node(kind)`, `name(kind, name)` or `literal(kind, value)` per node in pre-order. An adapter that only
   knows symbol ranges (for example a language server's document symbols) can call `LexicalAtoms.rangeAtom` to
   get planes from the range's tokens.

   Every atom also records its first and last source lines, so that reports and editors can point at it: javac
   takes them from the compilation unit's line map, the lexical front end from its first and last tokens, and
   `rangeAtom` counts them in the whole file. A plugged-in front end sets them with `AtomBuilder.lines(line,
   endLine)`; without that call they are 0 (unknown). Lines are not part of any plane.

   ```xml
   <plugin>
     <groupId>org.apache.maven.plugins</groupId>
     <artifactId>maven-code-clustering-plugin</artifactId>
     <dependencies>
       <dependency><!-- a jar with an AtomFrontEnd service --></dependency>
     </dependencies>
   </plugin>
   ```

   The lexical front end binds a name for the LOGIC plane when the name is the atom's own name, a parameter, a
   name after a declaration keyword (`let`, `const`, `var`, `val`, `def`, `fn`, `func`, `function`, `fun`, `auto`),
   the target of `:=` or of a plain assignment, or a loop variable (`in`, `of`, `:`). Directories named
   `node_modules`, `target`, `build`, `dist`, `vendor`, `.git`, `__pycache__` and `.venv` are skipped.
2. **Planes.** Each atom gets four hashes:

   | Plane | What it hashes | Equal when |
   |---|---|---|
   | EXACT | the source text, with CRLF line ends read as LF | the text is the same |
   | LOGIC | the tree's node kinds, with names declared inside the atom replaced by their first-use index; literals and names declared outside are kept | the code differs only in local, parameter and type-parameter names |
   | STRUCTURE | node kinds only | the code has the same shape (literals and all names may differ) |
   | SIMHASH | 64-bit SimHash over 3-token shingles of the logic tokens | the code is close: Hamming distance within `radius` |

3. **Atom clusters.** Atoms of the same kind that share a hash form a cluster on that plane (`CLUSTERS.tsv`). Near
   clusters use four 16-bit SimHash bands to propose candidates. A member joins only if it is within the radius of
   the cluster's first atom.
4. **File families.** Each file is the set of logic hashes of its methods. Two files whose sets reach the Jaccard
   threshold are a pair (`FILE_PAIRS.tsv`), and pairs are joined transitively into families (`FILE_CLUSTERS.tsv`).
   An inverted index proposes the pairs, so files are not all compared with each other. A hash that more than 64
   files hold (accessors, trivial overrides) proposes no pairs.
5. **Join.** The atoms of source set A are matched against the atom table of source set B (for example, a library
   that may already own the code). Each atom of A gets the strongest plane on which B has a match: EXACT, LOGIC,
   STRUCTURE, NEAR or ABSENT (`JOIN.tsv`). `COVERAGE.tsv` adds the results up per file of A:
   `ALL_IN_ONE_MATCH` (one file of B holds every atom), `ALL_SPREAD`, `MOSTLY_MATCHED` (80% or more) or `OWN_CODE`.

6. **Compare.** `snapshot` writes the atom table as a baseline, for example before a refactoring. `compare`
   gives each current atom a state against that baseline. Atoms are identified by path, kind and symbol.

   | State | Meaning |
   |---|---|
   | UNCHANGED | same exact hash |
   | REFORMATTED | same logic hash: only layout, comments or the names the atom declares changed |
   | LITERALS_CHANGED | same structure hash: literals or outside names changed |
   | CHANGED | no plane matches |
   | MOVED | the identity is new, but a removed baseline atom has the same logic hash |
   | ADDED / REMOVED | new atoms, and baseline atoms that are gone and did not move |
   | ACCEPTED | a change whose `path#symbol` is listed in the receipts file (proven another way, for example by tests) |

   `failOn` chooses the states that fail the build. As a refactoring gate, use
   `-Dclustering.failOn=CHANGED,LITERALS_CHANGED,ADDED,REMOVED`: then only behaviour-keeping states and receipted
   changes pass.

Every cluster, family and coverage row is a proposal. Before you merge code, confirm the equivalence with a diff
or a test.

## Use

```
mvn org.apache.maven.plugins:maven-code-clustering-plugin:0.1.0-SNAPSHOT:cluster
mvn ...:cluster -Dclustering.against=../library/target/code-clustering/ATOM_HASHES.tsv
```

| Parameter | Property | Default |
|---|---|---|
| outputDirectory | `clustering.outputDirectory` | `${project.build.directory}/code-clustering` |
| minTokens (smallest clustered atom, in logic tokens) | `clustering.minTokens` | 12 |
| radius (SimHash Hamming radius) | `clustering.radius` | 3 |
| fileThreshold (Jaccard similarity of a file pair) | `clustering.fileThreshold` | 0.5 |
| against (ATOM_HASHES.tsv of another source set) | `clustering.against` | none |
| sourceDirectories (directories to atomize instead of the compile source roots) | `clustering.sourceDirectories` (comma-separated) | compile source roots |

For a project whose sources are not Java: `mvn ...:cluster -Dclustering.sourceDirectories=src/main/js,src/main/python`.

```
mvn ...:snapshot                       # writes ${project.basedir}/.code-clustering/ATOM_HASHES.tsv
mvn ...:compare -Dclustering.failOn=CHANGED,LITERALS_CHANGED,ADDED,REMOVED -Dclustering.receipts=receipts.txt
```

`snapshot` and `compare` take `baseline` (`clustering.baseline`) and `sourceDirectories`. `compare` also takes
`receipts` (`clustering.receipts`), `failOn` (`clustering.failOn`) and `outputDirectory`. Keep the baseline outside
`target` if it has to survive `mvn clean`.

Without Maven, from the plugin jar:

```
java -cp maven-code-clustering-plugin-0.1.0-SNAPSHOT.jar org.apache.maven.plugins.clustering.CodeClustering \
    cluster <out-dir> <source-root>...
java -cp ... org.apache.maven.plugins.clustering.CodeClustering join <A/ATOM_HASHES.tsv> <B/ATOM_HASHES.tsv> <out-dir>
```

## Inline: the javac plugin

The same jar is a javac plugin named `CodeClustering`. It atomizes the sources of the compilation that runs it
from the trees javac builds anyway, so a build gets its tables during compilation, without a second parse and
without a separate goal:

```
javac -processorpath maven-code-clustering-plugin-0.1.0-SNAPSHOT.jar \
      -Xplugin:"CodeClustering out=target/code-clustering minTokens=12 radius=3" ...
```

| Argument | Meaning | Default |
|---|---|---|
| `out` | directory of the tables (required) | none: the plugin stays off |
| `minTokens`, `radius`, `fileThreshold` | as for `cluster` | 12, 3, 0.5 (or the `clustering.*` system properties) |
| `roots` | source roots, separated by the platform path separator; may repeat | each file's package root |
| `generated` | the directory annotation processors write sources to (javac `-s`) | none |
| `full` | `true`: the compilation compiles every source of its roots, so the tables are replaced, not merged | `false` |

A path may be given as a `file:` URI (`Path.toUri()`). javac splits the plugin argument at white space; the plugin
joins a word that is not `key=...` to the previous value with one space, so a plain path keeps single spaces but a
run of spaces or a tab becomes one space. A URI has no white space, so the core extension passes `out`, `roots` and
`generated` as URIs. A `roots` value that is a URI names one root.

- **When.** After PARSE, each compilation unit goes through the atom collection of the `cluster` scan
  (`AtomPlanes.collect`, the same `Collector`, `PlaneTokens` and `AtomBuilder`). `PlaneTokens` walks each
  top-level type once, recording every node's LOGIC token and the node range of every type and method; the atoms
  nested in it (its methods, nested types) replay their range instead of walking their subtree again, with the same
  `AtomBuilder` calls, so the planes do not change. The tree is still the parser's at
  that point: javac adds default constructors and record members later, during ENTER, which would change the TYPE
  atoms. After ANALYZE, the methods declared `native` of each attributed class are recorded. When the compilation
  ends, the tables are written.
- **Paths.** A file's table path is relative to the longest `roots` entry that holds it. Without one, it is relative
  to the directory its package declaration names (`src/main/java` for `src/main/java/p/A.java` in package `p`).
  That is the path `cluster` gives when it scans that root. Sources under `generated` (the ones annotation
  processors write) get their own tables in `out/generated`, filed relative to that directory, so the main tables
  hold the hand-written sources only. Without `generated`, generated sources are filed under their package root
  in the main tables. Two files of one compilation that would share a table path (default-package files of the same
  name in two directories, without `roots`) are each filed under their absolute path, with a warning.
- **Tables.** The plugin writes the five tables of `cluster` (`ATOM_HASHES.tsv`, `CLUSTERS.tsv`, `PLANE_SUMMARY.tsv`,
  `FILE_PAIRS.tsv`, `FILE_CLUSTERS.tsv`). It also writes:
  - `JNI.tsv`: path, symbol (the atom symbol), class_name (binary name in internal form: `p/Outer$Inner`, also for
    local and anonymous classes), method, arguments (JVM descriptor of the erased parameter types, for example
    `Ljava/lang/String;[I`; a type javac could not resolve is `L?;`). Attribution makes these exact. They are the
    Java side a JNI linker matches against native functions; the build has no native atoms, so the plugin links
    nothing itself.
  - `ATOM_SOURCES.tsv`: path, source file.
- **Full and incremental compilations.** With `full=true` the compilation's units replace the tables (and a full
  compilation without generated sources removes the `generated` tables). Otherwise the compilation may compile only
  some files: its units replace the rows of their source files. The rows of other source files (from
  `ATOM_SOURCES.tsv`) are kept while the file exists, so the rows of a deleted file are dropped. Rows are kept by
  source file, not by table path: a file whose package declaration changed is filed once, under its new path. A kept
  file whose path a compiled file now takes is filed under its absolute path. Then the clusters and file families
  are computed again over the merged atoms. Merging cannot tell that an existing file is no longer compiled (a new
  `<excludes>`); only a full compilation drops it. A table written by `cluster` (it has no `ATOM_SOURCES.tsv`) is
  replaced, not merged. A module that javac does not compile because it is up to date keeps its tables as they are.
- **Identity.** For the same sources and the same JDK, the tables equal those of `cluster` byte for byte (tested on
  fixtures and on this plugin's own sources). LOGIC and STRUCTURE depend on the tree shapes of the JDK's javac: for
  example, JDK 21 gives arrow `case` labels nodes that JDK 17 does not have. Compare tables made with the same JDK
  only. EXACT is taken from the text javac read, in the build's `-encoding`.
- **Never fails a compilation.** Bad arguments disable the plugin. Any other failure (an output path that is a file,
  for example), errors such as `StackOverflowError` and `AssertionError` from the tree walks included, is logged as
  one warning through `System.Logger` (java.util.logging unless configured otherwise), and the step's atoms are
  dropped. Only virtual machine errors other than `StackOverflowError` (out of memory, internal errors) propagate.
  The compilation's result is unchanged.
- **Registration.** `META-INF/services/com.sun.source.util.Plugin` is in the jar only, never in `target/classes`.
  javac reads the plugin registrations of its class path even without `-Xplugin`, and stops with
  `ServiceConfigurationError` when a registered class is missing. A class is missing while this module compiles, and
  after an incremental compile has deleted the old classes. The registration lives in `src/main/javac-plugin`, and the
  jar is made from `target/jar`, a copy of `target/classes` plus the registration.

In the fork, the core extension (`tools/m3-smart-builder/extension`, `InlineClusteringSpy`) adds the plugin to every
maven-compiler-plugin execution. The output is `target/code-clustering/inline` for main sources and
`target/code-clustering/inline/test` for test sources, apart from the `cluster` goal's `target/code-clustering`, so
the two producers never overwrite each other's tables. `-Dclustering.inline=false` turns it off; see that README.

`src/it/inline_timing.sh <java-home> <runs> <source-root> [javac argument...]` compares compile cost with and
without the plugin (`InlineTiming`, test scope). It runs in one JVM: a warm-up, then pairs in alternating order with
the same processor path, where only `-Xplugin` differs. It reports wall time and the compiling thread's CPU time.
Measured on 2026-11-17 (Windows, busy machine, 5 pairs, medians) with the JDK's own `java.util` sources as the
corpus (`--patch-module java.base=...`):

| JDK | Files | Atoms | CPU off | CPU on | Overhead | Wall off | Wall on |
|---|---|---|---|---|---|---|---|
| 17.0.19 | 340 | 12,157 | 4,453 ms | 5,625 ms | +1,172 ms (26.3%) | 4,762 ms | 6,050 ms |
| 21.0.9 | 355 | 13,015 | 4,515 ms | 5,703 ms | +1,188 ms (26.3%) | 4,809 ms | 6,051 ms |

These are warm compilations (JIT-compiled javac), so the relative overhead is the upper end. The cost is the shared
atom collection plus the clustering of the merged table. The collection now walks each top-level type once and
replays the nested atoms; LOGIC and SIMHASH hash the tokens' UTF-8 bytes, encoded once per atom, without joining
them, and SimHash counts the 64 bit positions at once in bit-sliced counters. The hashing itself stays per atom: a
type's planes cover its methods' tokens, renumbered for the type's declarations. The cluster tables of the old and
new code are byte-identical (JDK 17.0.19 and 21.0.9; `java.util`, 12,313 atoms; this module's and the extension's
sources; 36 Python files). Measured on 2026-11-17 on a heavily loaded machine (an idle-machine compile of the
corpus took 4.4 s of CPU, here 13.6 to 14.0 s), JDK 17.0.19, 354 `java.util` files, 5 pairs, medians:

| Code | CPU off | CPU on | Overhead | Wall off | Wall on |
|---|---|---|---|---|---|
| before (c761a2968) | 14,046 ms | 19,140 ms | +5,094 ms (36.3%) | 74,503 ms | 88,616 ms |
| single walk | 13,609 ms | 17,234 ms | +3,625 ms (26.6%) | 58,145 ms | 63,892 ms |

The collection alone (`AtomPlanes.collect` over the parsed units, 9 runs each, alternating) went from a median of
1,171 ms to 812 ms of CPU.

## Build and test

`mvn verify` compiles the plugin with `-Xlint:all -Werror` and runs `CodeClusteringTest` against a fixture. The
fixture has an exact copy with CRLF line ends, a copy with renamed locals (logic plane), a copy with one changed
literal (structure plane), a copy with one extra statement (SimHash), and a renamed copy of a whole file (file
family). The test then checks the atoms' source lines, the join, the coverage, the command-line exit codes, the
atom table's line columns and reading an 8-column table. `LexicalAtomsTest` checks the lexical front end on
JavaScript, Python, Go, C, Rust and Kotlin fixtures: atom symbols, the logic and structure planes, braces inside
strings and comments, and source lines. `FrontEndTest` checks front-end discovery and `rangeAtom`, including its
lines. `InlinePluginTest` compiles fixtures with `-Xplugin:CodeClustering` and checks the following:

- the five tables are byte-identical to `cluster` of the same root. The fixtures have classes without constructors
  and a record, so collecting later than PARSE gives other tables (a mutation that collected after ANALYZE failed
  three of these checks);
- the JNI rows: nested and anonymous classes, overloads, a type variable erased to its bound, varargs, an imported
  type;
- partial compilations that replace rows, keep rows, and drop the rows of a deleted file;
- a space in `out=`;
- a compilation stays green when the output is a file, a number is bad, or `out` is missing;
- the plugin's own sources against `cluster` of `src/main/java`.

`InlineRegressionTest` reproduces the review findings, each failing before its fix: a file whose package changed is
filed once; `full=true` drops a file no longer compiled (merging keeps it); default-package files of the same name
in two directories are kept apart, and equal the scan with `roots`; `out` and `roots` as URIs of a directory with two
consecutive spaces; generated sources in `out/generated`, both table sets equal to their scans, removed by a full
compilation without them; `StackOverflowError` and `AssertionError` logged, `OutOfMemoryError` propagated.

## Report columns

- `ATOM_HASHES.tsv`: path, kind (TYPE or METHOD), symbol, tokens, exact, logic, structure, simhash, line, end_line
  (the atom's first and last source lines, 1-based; 0 when the front end does not know them). Tables written
  before the two line columns existed (8 columns) are still read, with lines 0.
- `CLUSTERS.tsv`: plane, cluster, kind, members, tokens, atoms (`path#symbol|...`)
- `PLANE_SUMMARY.tsv`: plane, clusters, clustered_atoms
- `FILE_PAIRS.tsv`: left, right, shared, left_methods, right_methods, jaccard
- `FILE_CLUSTERS.tsv`: family, files, members
- `JOIN.tsv`: path, kind, symbol, plane, match_path, match_symbol
- `COVERAGE.tsv`: path, atoms, exact, logic, structure, near, absent, top_match, top_share, proposal
- `COMPARE.tsv`: state, path, kind, symbol, baseline_path, baseline_symbol
- `JNI.tsv` (javac plugin): path, symbol, class_name, method, arguments
- `ATOM_SOURCES.tsv` (javac plugin): path, source
