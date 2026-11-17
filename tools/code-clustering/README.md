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
family). The test then checks the join, the coverage and the command-line exit codes. `LexicalAtomsTest` checks
the lexical front end on JavaScript, Python, Go, C, Rust and Kotlin fixtures: atom symbols, the logic and
structure planes, and braces inside strings and comments.

## Report columns

- `ATOM_HASHES.tsv`: path, kind (TYPE or METHOD), symbol, tokens, exact, logic, structure, simhash
- `CLUSTERS.tsv`: plane, cluster, kind, members, tokens, atoms (`path#symbol|...`)
- `PLANE_SUMMARY.tsv`: plane, clusters, clustered_atoms
- `FILE_PAIRS.tsv`: left, right, shared, left_methods, right_methods, jaccard
- `FILE_CLUSTERS.tsv`: family, files, members
- `JOIN.tsv`: path, kind, symbol, plane, match_path, match_symbol
- `COVERAGE.tsv`: path, atoms, exact, logic, structure, near, absent, top_match, top_share, proposal
