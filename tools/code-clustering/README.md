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

## Build and test

`mvn verify` compiles the plugin with `-Xlint:all -Werror` and runs `CodeClusteringTest` against a fixture. The
fixture has an exact copy with CRLF line ends, a copy with renamed locals (logic plane), a copy with one changed
literal (structure plane), a copy with one extra statement (SimHash), and a renamed copy of a whole file (file
family). The test then checks the atoms' source lines, the join, the coverage, the command-line exit codes, the
atom table's line columns and reading an 8-column table. `LexicalAtomsTest` checks the lexical front end on
JavaScript, Python, Go, C, Rust and Kotlin fixtures: atom symbols, the logic and structure planes, braces inside
strings and comments, and source lines. `FrontEndTest` checks front-end discovery and `rangeAtom`, including its
lines.

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
