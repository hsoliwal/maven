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

Finds duplicated and similar code in a project's sources, measures how much of one source set another already
holds, and links Java native methods to the C, C++ and Objective-C functions behind them. It only writes reports. It never changes a source file and never fails a build because of what it
finds.

## How it works

1. **Atoms.** Every type and every function or method becomes an atom with a symbol, for example
   `Owner.method(int[])` in Java or `Owner.method(2)` (name and parameter count) in other languages.
   Two front ends produce atoms:

   | Front end | Languages (extensions) | How |
   |---|---|---|
   | javac | Java (`.java`) | `JavacTask.parse()`: no classpath needed, nothing is compiled |
   | lexical | C, C++, Objective-C (`.m`, `.mm`), C#, JavaScript, TypeScript (incl. JSX/TSX), Go, Rust, Kotlin, Scala, Swift, PHP, Dart, Groovy (brace blocks); Python (indentation) | A tokenizer that skips comments and keeps literals whole. A block becomes a TYPE atom if its header names a type (class, struct, interface, enum, trait, impl, object, record, union, Go `type X struct`). It becomes a METHOD atom if the header has a name followed by a parameter list, or ends in `=>`. Control statements are not atoms. In C, C++ and Objective-C, a function is named by the first name before a top-level parameter list that is not a macro call (see the JNI section), so a constructor with a member-initializer list is `Foo(1)`, a destructor `~Foo(0)`, an operator `operator+(1)`, `operator[](1)` or `operator bool(0)`. In Objective-C, a method definition (`-` or `+` that starts the header, or starts a line after macro names only such as `AWT_NS_WINDOW_IMPLEMENTATION`, an optional `(type)`, then the selector parts `name:(type)argument`, or one name that is not a keyword other than `class`, `self` and `new`) is a METHOD atom named by its full selector, without a parameter count (`View.drawRect:`, `View.initWithFrame:style:`, `View.dealloc`); a sign later in a header (`case -kLimit: {`, `operator-() const {`) is an operator. `@implementation Name` ... `@end` is a TYPE atom (`Name+Category` for a category); C and C++ functions in the same files keep the brace rules, `@"..."` is a string literal, and blocks after other `@` directives (`@synchronized`, `@autoreleasepool`, `@interface`) are not atoms. `.m` files are read as Objective-C. In Python, `def` and `class` lines open atoms that run to the next line indented as far or less, decorators included |

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

## Java and native code (JNI)

`cluster` also links the Java methods declared `native` to the C, C++ and Objective-C functions that implement them,
the way the JVM looks them up, and writes `JNI.tsv` when the sources have a native method or a `Java_` function. Scan
the classes and the native sources in one run, for example a JDK module directory that holds `share/classes`,
`share/native`, `unix/native` and `windows/native`.

- **Java side.** The javac front end records every native method with the binary name of its class (`p/Outer$Inner`)
  and its argument descriptor, from the syntax tree alone. Primitive and array types are exact, a type variable is the
  erasure of its first bound, and a reference type resolves as the compiler resolves a simple name: a member type of
  an enclosing class (declared, or inherited from its superclass and superinterfaces), a single-type import, a type of
  the same package, a type of an on-demand import, a type of `java.lang`. Types are known from the compilation units
  of the same source root and from the platform classes of the running JDK; an imported or qualified name that neither
  knows is taken as a top-level class when every part before the last starts in lower case. When a supertype of an
  enclosing class is not known, a simple name could be a member type it declares, so the long name is `UNRESOLVED`
  (a qualified name whose first part starts in lower case is then taken as a package). Any other type that is not known
  makes the long name `UNRESOLVED`; it is never guessed. Native methods of local and anonymous classes are left out
  (their binary names depend on the compiler's numbering), and so are those of Java files that a plugged-in front end
  takes over from javac; the `Java_` functions of an anonymous or local class of a scanned class
  (`Java_p_Outer_000241_run`: `_00024` and a digit after the escaped class) are therefore not orphans.
- **Names.** The short name is `Java_`, the escaped class, `_`, the escaped method; the long name adds `__` and the
  escaped argument descriptor. Escaping follows the JNI specification: ASCII letters and digits stay, `/` becomes `_`,
  `_` becomes `_1`, `;` becomes `_2`, `[` becomes `_3`, and any other UTF-16 code unit becomes `_0` and four
  lower-case hex digits (so `$` is `_00024`). The test compares these names with the headers `javac -h` writes.
- **Native side.** The METHOD atoms of `.c`, `.h`, `.cc`, `.cpp`, `.cxx`, `.hpp`, `.hh`, `.m` and `.mm` files, from
  any front end, by their own name (`JNIEXPORT` is not required: the JVM finds a function by its name). In these
  files the lexical front end ends a header at a preprocessor line (`#include`, `#if !defined(X)`), except inside an
  open parenthesis or square bracket (`#ifdef` among the parameters, whose every branch then counts), and joins a
  header that names nothing after a preprocessor line (`#if ... __attribute__((hot)) #endif` before the brace) to
  the header before it. It names a function by the first name before a top-level parameter list that is not a macro
  call: a macro call is followed by another declaration word (`DEFINE(Int, jint)` or `METHODDEF(void)` on the line
  above) while another candidate follows; candidates after a member-initializer list (`: base(a), m(0)`) do not count,
  and `noexcept(...)` or `__attribute__((...))` never names a function. Functions whose names a macro gives are
  expanded: an object-like alias, `#define FN Java_p_Lib_open` used as `JNIEXPORT jint JNICALL FN(...) {...}` (an
  alias defined once per `#if` branch gives every branch's name); a name macro, `#define JNI_NAME(n) Java_p_Lib_##n`
  used as `JNI_NAME(open)(JNIEnv *env, ...) {...}`; a macro that defines whole functions,
  `#define DEFINE(NAME, T) ... Java_p_Lib_sort##NAME##0(...) {...}` instantiated as `DEFINE(Int, jint)`; and a
  defining macro whose function name is a parameter, `#define HANDLE(function, m) ... function(JNIEnv *env, ...) {...}`
  instantiated with the full name, `HANDLE(Java_p_Lib_focusGained, fire)`. The macros of every header (`.h`, `.hh`,
  `.hpp`) under the root apply in every native file; a file's own macros hide a header's macro of the same name. They
  are shown as written (`path#FN`, `path#JNI_NAME(open)`, `path#DEFINE(Int,jint)`); nothing else of the preprocessor
  is run. RegisterNatives tables are arrays `JNINativeMethod name[] = {...}` or `name[] {...}` whose entries give the
  method name as a string literal: an entry fits a native method with that name and, when both are known, the same
  signature, of a class that the table's file writes as a string literal (`FindClass(env, "p/Peer")`, scanned or not)
  or defines `Java_` functions of; only when the file has neither a class-name literal nor a `Java_` function does any
  class fit.
- **OS.** The OS of a path is its first directory that is an OS directory (`share`, `unix`, `linux`, `macosx`,
  `macos`, `windows`, `win32`, `aix`, `bsd`, `solaris`, `posix`, `android`, `ios`). A Java file under an OS directory
  counts only native code under the same OS, under `share`, or in the same family: `unix` and `posix` with `linux`,
  `macosx`, `macos`, `aix`, `bsd`, `solaris`, `android` and `ios` (a `linux` class takes `unix` code, a `unix` class
  takes `linux` code, a `macosx` class does not take `linux` code); `windows` and `win32` are one OS. A Java file or
  native file without an OS directory, or under `share`, fits every OS.

| Status | Meaning |
|---|---|
| LINKED | a function of the Java file's OS has the method's short name |
| LINKED_OVERLOAD | no short name, but a function of the Java file's OS has the long name (with an `UNRESOLVED` long name: a name that agrees with every resolved argument and has one class name for each unresolved one) |
| REGISTERED | no function by name, but a RegisterNatives table entry of the Java file's OS fits |
| LINKED_OTHER_OS | none of these for the Java file's OS, but one of them under another OS only |
| VM_BOUND | none of these, and HotSpot implements the method itself (below) |
| MISSING_NATIVE | none of these: no native code for the method in the scanned sources |
| ORPHAN_NATIVE | a `Java_` function that no scanned native method names, in a scanned package |

VM_BOUND covers the native methods that HotSpot binds without library code, so they are not reported missing when
`src/hotspot` is not scanned: every native method of `java.lang.Object`, `jdk.internal.misc.Unsafe`,
`java.lang.invoke.MethodHandleNatives`, `jdk.internal.vm.Continuation`, `jdk.internal.perf.Perf`, the JFR class
`jdk.jfr.internal.JVM` and JVMCI's `jdk.vm.ci.hotspot.CompilerToVM`; of the classes whose `registerNatives` HotSpot
binds in its own table (`lookup_special_native_methods` in `nativeLookup.cpp`): `jdk.internal.foreign.abi.UpcallStubs`,
`UpcallLinker`, `NativeEntryPoint`, `jdk.internal.vm.vector.VectorSupport`, `jdk.internal.misc.ScopedMemoryAccess`,
`jdk.vm.ci.runtime.JVMCI`, `jdk.vm.ci.services.Services` and the WhiteBox test classes; and the signature-polymorphic
methods of `java.lang.invoke.MethodHandle` and `VarHandle` (native with one `Object...` parameter). A method with
native code in the scan keeps the status that code gives it.

An unused `Java_` function is ORPHAN_NATIVE only when its name starts with a package that the scan holds Java classes
of (`Java_p_q_` for package `p.q`), so a `Java_` prefix on a C helper (`Java_CTProvider`) or the functions of a
library whose Java side is not scanned are not reported.

`os_variants` lists the OS directories among a row's native paths, so a method implemented once per platform
shows `linux,macosx,windows` and a missing variant stands out; `java_os` is the Java file's OS directory. The summary
line gets a part such as `JNI 202 native methods: LINKED 200 LINKED_OVERLOAD 0 REGISTERED 0 LINKED_OTHER_OS 0
VM_BOUND 0 MISSING_NATIVE 2 ORPHAN_NATIVE 0`. Like every other table, the rows are proposals: the build that compiles
and loads the library is the proof.

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
lines. `ObjectiveCTest` checks instance and class methods by selector, `@implementation` and a category, C functions
around them, `@"{"`, `@synchronized` and a renamed argument. `CFamilyTest` checks C and C++ headers: a constructor
with a member-initializer list, destructors, operators, a macro call above a function, a macro-named function,
`#ifdef` among parameters, `#if ... __attribute__ ... #endif` before the brace, and that `case -kLimit: {` and C++
operators in `.mm` are not Objective-C methods. `JniNamesTest` checks the JNI specification's escaping
cases and compares the names of a fixture (overloads, arrays, a nested class, a type variable, imported, same-package
and on-demand types, varargs, a non-ASCII name) with the headers `javac -h` writes for it. `JniTypesTest` compares
the names of member types inherited from a superclass, a platform superclass and a platform interface with
`javac -h`, and checks that an unknown superclass makes a reference argument `UNRESOLVED`. `JniLinkerTest` links a
JDK-shaped tree with `share`, `unix`, `windows`, `linux` and `macosx` native code: every status, the OS variants, a
RegisterNatives table with and without `FindClass`, a macro-named function, and the removal of a stale `JNI.tsv`.
`JniCasesTest` checks an object-like alias in two `#if` branches, a name macro from a header, a table without `=`,
a defining macro given the full `Java_` name, a table whose file names an unscanned class, `java_os` and
LINKED_OTHER_OS, anonymous-class functions, VM_BOUND methods, and `Java_` helpers outside the scanned packages.

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
- `JNI.tsv`: java_path, java_symbol, short_name, long_name (`UNRESOLVED` when an argument type is not known), status,
  native_atoms (`path#symbol|...`; a table entry is `path#table[name]`, a macro-named function `path#MACRO(name)`),
  os_variants (comma-separated), java_os (the Java file's OS directory, empty when none). ORPHAN_NATIVE rows leave the
  Java columns empty.
