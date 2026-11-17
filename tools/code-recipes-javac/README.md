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
# Apache Maven Code Recipes javac bridge

A javac plugin, `-Xplugin:MavenRecipes`, that builds the [OpenRewrite](https://docs.openrewrite.org) Java tree
(`J.CompilationUnit`) of every compiled source file from the trees the build's own compile made. Recipes can then run
without a second parse: today rewrite-maven-plugin forks the lifecycle up to `process-test-classes` and parses and
attributes every source again with its own javac (`MavenMojoProjectParser`, `JavaParser.fromJavaVersion()`).

This is leaf L01 of the plugin absorption design: the bridge and its proofs. It runs no recipe and writes no patch
(that is L02).

**What it guarantees.** With the plugin on, javac's exit code, its diagnostics (text and order, `-Xlint:all -Werror`
and `-verbose` included), its class files and its generated sources and resources are the same as without it, for
compiles that pass and compiles that fail. Where it cannot guarantee that (a javac other than 21, a heap below the
floor), it turns itself off and prints one `[MavenRecipes]` line. The tests compare all of this with and without the
plugin, in process and forked.

The module (`org.apache.maven.plugins:maven-code-recipes-javac`, JDK 21) calls OpenRewrite's own
`ReloadableJava21ParserVisitor` (rewrite-java-21 8.89.0, Apache-2.0), the class rewrite's `Java21Parser` runs after
its private javac. No rewrite code is copied. It builds two jars: the implementation, and
`maven-code-recipes-javac-<version>-boot.jar` (the boot entry). Build and test it on its own:

```
mvn -f tools/code-recipes-javac/pom.xml install
```

## How it works

* **Boot entry.** `org.apache.maven.recipes.javac.boot.MavenRecipesPlugin` (name `MavenRecipes`) and `ThinListener`
  are compiled for Java 8 (class file version 52) and are the only classes, with the `Plugin` registration, in the
  boot jar. `init` checks the running javac first: unless `java.specification.version` is `21` it prints
  `[MavenRecipes] off: javac N is not a supported javac (21)` and registers nothing (javac 17 and 25 are proved; 22-24
  and 27 are not tested, so they are off). Below a heap floor (`minHeap=`, 256 MB) it prints one line and registers
  nothing, because javac itself keeps comments and end positions for every file once any task listener exists
  (`JavaCompiler.java:644-648`), a cost no plugin can undo. Otherwise it loads the implementation from the `impl=`
  jars into a class loader of its own (or from its own class loader when `impl=` is absent) and registers
  `ThinListener`, the only listener javac holds. Anything that escapes the implementation drops it and closes that
  class loader.
* **When.** `LstCollector` builds a file's tree at the ANALYZE event of its last top-level class, attributed and
  flowed, not yet desugared, under javac's default compile policy; the plugin sets no policy (see below). A file whose
  class javac already desugared is `skipped: desugared ...`; `package-info.java` gets its own ANALYZE event; a file
  without any type is built at COMPILATION finished.
* **Read only.** Each conversion runs inside an `IsolationScope`, which makes it a read-only observer of javac:
  * `LogSandbox`: a diagnostic handler of its own that drops everything, all five Log writers on a null writer, the
    deferred lint handler immediate, the Log's source and its four counters restored;
  * `CompletionRefusal`: every class javac has not completed stays uncompleted. rewrite's type mapping calls
    `complete()` on such classes, which would read class files javac never read, print new `-verbose` lines and report
    new classfile warnings (`-Werror` fails). The refusal snapshots the fields javac's lazy accessors fill in and puts
    them back;
  * `FreshDocs` and `ShadowContext`: rewrite's Javadoc visitor gets doc trees parsed new from javac's comment tokens,
    and a context whose `Attr` is `PureDocAttr`, which resolves `@link`/`@see` references by pure lookups (completed
    classes, the unit's imports, the same package, `java.lang`). javac's own Attr, which reports into javac's Log
    ("cannot find symbol" on a stale link) and completes classes, never runs;
  * `RecordAnnotationGuard`: puts back the record-component annotation types rewrite's visitor writes into javac's
    trees (`extractRecordComponentAnnotations`), so a listener after this one sees javac's trees.
  Opening sets everything up before the first write, and closing restores without allocating, so it also runs under
  an OutOfMemoryError. Tripwires (a captured diagnostic, a class entered into the symbol table, a completer replaced,
  and with `check=true` an identity digest of the trees) make the file `failed: isolation ...` and stop the plugin.
* **Memory.** Nothing of a file outlives its census row: the tree, its type cache and javac's unit are dropped at the
  event (an in-process caller can keep chosen trees, `LstCollector.register`). Before each conversion a heap gate
  (`headroom=`, 30% of the maximum heap free, from `Runtime`) can close for the rest of the compile with one notice.
  An OutOfMemoryError, StackOverflowError or InternalError inside a conversion releases a 1 MB reserve, restores
  javac's state and stops the plugin with one notice.
* **Honest census.** A row is `ok` or `print-mismatch` only if javac reported no error and generated every top-level
  class of the file; otherwise `skipped: compile did not finish (N/M classes generated)` or `skipped: compile errors`.
  `built_at` is `-` on every skipped row, and failure details are one line of at most 300 characters.
* **Fidelity.** `CompilerTreeLst` runs the visitor on the javac unit with the character content javac parsed, so
  positions match by construction, then checks that the tree prints the exact text and, for a file on disk, its exact
  bytes. The static `CompilerTreeLst.toLst(JavacTask, CompilationUnitTree, Path, ExecutionContext)` serves any caller
  that holds a running task, inside the same isolation scope.
* javac keeps its default string folding (`JavacParser.foldStrings`); `FoldedStrings` splits each folded literal back
  into the left-associative `J.Binary` nodes of the classic parser, from the source text (`StringChain`,
  `UnicodeText`). A chain longer than 128 literals, one with escaped surrogates, or one it cannot read stays folded,
  and the file is a `print-mismatch` with that reason.
* The tree's charset is the one javac decoded the file with (`CompileCharset`), read from javac's file object, which
  also works with `--release`, where javac hides its file manager behind a delegating one.
* The plugin never fails a compile. An option it does not understand, a census it cannot write, or any failure of its
  own is one line starting with `[MavenRecipes]` (`Notices`; on javac's notice writer once javac's Log is reachable,
  else on standard error), never a diagnostic, so `-Werror` and a DiagnosticListener see nothing, and plexus-compiler
  files it as an informational message.

### Fidelity costs of the isolation

* Library classes javac never completed are shallow in the tree's types (name and flags, no members or supertype).
  Node types are unchanged.
* Doc references into classes javac never completed stay unresolved (a stale link in the tree, as javac's Attr would
  report one).
* A file with several top-level classes is `skipped: desugared ...` under javac's default policy when javac generates
  its first class before it attributes the next.

## Compile policy

The plugin sets no compile policy and needs none. `-XDcompilePolicy=simple` gives the same class files for compiles
that pass, but other diagnostics and class files for compiles that fail (a flow error before a type error is dropped;
a good class before a failing one is not written), so it is not used. Proved by `CompilePolicyTest`:

| policy | effect | result |
|---|---|---|
| default (`byTodo`) | each class is attributed, flowed, desugared and generated before the next | a file with several top-level classes is skipped |
| `-XDcompilePolicy=byfile` | one file at a time, but a superclass declared in a later file is desugared early when an earlier file needs it | that later file is skipped when it holds more than one class |
| `-XDcompilePolicy=simple` | every file is analyzed before any is desugared | every file is built; failing compiles change |

## Use in a build

The implementation needs the jdk.compiler internals exported to the unnamed module, for an in-process compile
(`.mvn/jvm.config`) and for a forked one (`-J` flags):

```
--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
```

How the boot jar reaches javac decides whether javac still finds the processors the build relies on (Lombok as a
provided dependency, Maven's `DiIndexProcessor`): javac looks for processors on `-processorpath` when it is set and
on the classpath only when it is not. Never give `annotationProcessorPaths` with only the plugin. L03 wires it so:

| compile | wiring | proved |
|---|---|---|
| forked javac | `-J--class-path=<boot.jar>` and `-Xplugin:MavenRecipes impl=<jars> ...`: javac's processor class loader has the application class loader as parent, so it finds the plugin, and no javac option or path changes | `ForkedIsolationTest`, `ForkedJavacTest` (implicit processing, `-proc:full`, `-verbose`, both) |
| in process, with a processor path | append only the boot jar to it; the implementation comes through `impl=` | design probe P11 |
| in process, no processor path, explicit `-proc:` | processor path = the compile's classpath (same jars, same order) + boot jar | `IsolationParityTest` (`-proc:full`) |
| in process, no processor path, implicit processing, no `-verbose` | boot jar appended to `-classpath` (keeps javac's implicit-processing note) | `IsolationParityTest` |
| in process, implicit processing and `-verbose` | no wiring changes nothing: L03 does not add the plugin and logs why | design probe P11 |

Never add `-Xplugin:MavenRecipes` when the build already passes it: a duplicate is javac's own "plug-in not found"
error. A forked example:

```xml
<plugin>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <fork>true</fork>
    <compilerArgs>
      <arg>-J--class-path=${recipes.bootJar}</arg>
      <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED</arg>
      <!-- ... the other seven exports as -J flags ... -->
      <arg>-Xplugin:MavenRecipes impl=@${project.build.directory}/recipes/impl.txt census=${project.build.directory}/recipes/LST_CENSUS.tsv root=${project.basedir} buildDir=${project.build.directory}</arg>
    </compilerArgs>
  </configuration>
</plugin>
```

L03 adds this wiring through a core extension instead, so no POM is edited. Options, after the name and separated by
spaces:

| option | read by | meaning | default |
|---|---|---|---|
| `impl=<entries>` | boot entry | the implementation's class path: `file:` URIs or paths separated by `\|`, or `@<file>` with one per line | the boot entry's own class loader |
| `minHeap=<MB>` | boot entry | below this maximum heap the plugin registers nothing | 256 |
| `headroom=<percent>` | boot entry | free share of the maximum heap required before each conversion | 30 |
| `census=<file>` | implementation | write `LST_CENSUS.tsv` there when the compilation finishes | no census |
| `root=<dir>` | implementation | the directory source paths are made relative to | the working directory |
| `buildDir=<dir>` | implementation | the build directory: files under it are generated sources and get no tree, as rewrite-maven-plugin skips every source root under the build directory | `<root>/target` |
| `check=true` | implementation | also compare an identity digest of javac's trees before and after each conversion (the tests turn it on) | `false` |

javac splits the text after the plugin name on runs of whitespace and reads no quotes (`Arguments.getPluginOpts`). A
piece that starts no known `key=` continues the value before it and is joined back with one space, so a path with
single spaces arrives whole. A value may also be quoted (`"..."` or `'...'`) or a `file:` URI (for example
`root=${project.baseUri}`). Only the URI form keeps runs of spaces or tabs: a census path that was joined from pieces
is written only into an existing directory, else one notice line, so a collapsed run never creates a new directory.
An unknown option, an empty value or a value that is no path turns the plugin off for that compile with one notice
line, instead of silently dropping the census.

rewrite-java-21 depends on rewrite-java-lombok, which brings `org.projectlombok:lombok`; that jar registers an
annotation processor. The POM excludes it: the parser visitor only compares the names `lombok.val`, `lombok.var` and
`lombok.Generated` as strings. None of the runtime jars registers an annotation processor or a javac plugin; only the
boot jar registers the plugin. With Lombok on the build's classpath, the trees follow the compile: `val` has the
inferred type and a generated constructor exists, where rewrite-maven-plugin's classic parser (with its
`LombokSupport`) gives `lombok.val` and no such constructor; the files are `ok` and print exactly.

## LST_CENSUS.tsv

One row per parsed file, ordered by path, UTF-8, LF line ends:
`path, parse_events, print_equal, attributed_method_calls, unattributed_method_calls, status, built_at`.

| status | meaning |
|---|---|
| `ok` | tree built, it prints the exact source, javac reported no error and generated every top-level class of the file; the only status that hands a tree to recipes |
| `print-mismatch` | tree built but it does not print the exact source |
| `print-mismatch: <n> string chain(s) folded by javac left as one literal ...` | a folded string chain could not be split; the tree would differ from the classic parser's |
| `skipped: compile errors` | the compilation reported errors, so no tree is kept |
| `skipped: compile did not finish (N/M classes generated)` | javac reported no error but did not generate every top-level class (a crash, `-proc:only`, a `should-stop` option, an implicit source under `-implicit:none`) |
| `skipped: generated source` | a file under the build directory (`buildDir`) |
| `skipped: module-info has no OpenRewrite tree` | OpenRewrite has no tree for it; `JavaParser.accept` excludes it as well |
| `skipped: desugared before its last class was analyzed` | see Compile policy |
| `skipped: analysis incomplete` | the compilation finished before every class of the file was analyzed |
| `skipped: heap headroom` | the heap gate closed before the file |
| `skipped: plugin stopped` | the plugin stopped earlier in the compile (a contained failure of its own) |
| `failed: <exception>` | the conversion threw or reported an error; `failed: contained ...` for a contained VirtualMachineError, `failed: isolation: ...` for a tripwire |

Calls are method invocations, constructor calls and method references; a call is attributed when it carries a
method type. `built_at` is ANALYZE, COMPILATION or `-`.

## Tests and oracles

Run on JDK 21 by `mvn verify`; `-Drecipes.jdk17=<JDK 17 home>` and `-Drecipes.jdk25=<JDK 25 home>` add the forked
version-gate cases (skipped without them). Fixtures (`Fixtures`, `FoldingFixtures`, `EdgeFixtures`,
`IsolationFixtures`) cover a plain class, generics and lambdas, sealed types, records and pattern `switch`, five
top-level types in one file, annotations with Javadoc, several classes in one file, `package-info.java`, a file
without types, `module-info.java`, CRLF line ends, unicode escapes, non-ASCII text, text blocks, comments, string
chains, concatenations of 1500 to 10000 literals, ISO-8859-1 and windows-1252 sources, paths with spaces, generated
sources, a cross-file superclass, failing compiles, and the round-2 verifiers' reproducers: a library class with an
annotation whose classes are missing, stale, nested-member, parameter, package and deprecated Javadoc links, a link
to a class only `-sourcepath` holds, record components with mixed-target and repeatable annotations, the policy
fixtures, a classpath processor, and 150 generated classes of 60 methods. Cases marked `rewrite-java-tck` are copied
from OpenRewrite's parser test kit (github.com/openrewrite/rewrite, `rewrite-java-tck`, Apache-2.0) at tag v8.89.0,
except the two CommentTest cases of the known defect below, which upstream added with its fix (commit 3c87c3ea6e).

| test | oracle | what must hold |
|---|---|---|
| `IsolationParityTest` | plain javac in process, default policy | for every reproducer and `-Xlint:all`, `-Xlint:all -Werror`, `-Xlint:none`, `-Xdoclint:all`, `-verbose`: same result, diagnostics (text and order), output text and class files, registered directly and through `-Xplugin`; every converted reproducer file is `ok` with `check=true`; no `ok` row when no class was generated; `built_at` `-` on skipped rows |
| `ForkedIsolationTest` | plain command-line javac, default policy | the same reproducers forked; the classpath processor under four option sets; javac 17 and 25: one notice and the same compile; `-J-Xmx64m`: one notice and the same compile; `-J-Xmx320m`: the same compile with trees |
| `TreeIsolationTest` | a listener after the plugin | sees every node's type and symbol and the record-component annotation types exactly as without the plugin |
| `CompletionRefusalTest` | the symbol table | refused, touched and restored classes keep the very field objects and completers they had; nothing is entered; the rest of the compile is unchanged |
| `ContainmentTest` | plain javac | an OutOfMemoryError or StackOverflowError in a conversion is contained (one notice, the file failed, the rest skipped, the compile unchanged); a closed heap gate and the heap floor give one notice and the same compile; without keeping, no tree outlives its row |
| `CompilerTreeLstTest` | the source bytes | every fixture file: status `ok`, exactly 1 PARSE event, printed bytes equal the file, 0 unattributed calls |
| `ClassicParserParityTest` | rewrite-maven-plugin's parse: `JavaParser.fromJavaVersion()` | same printed text, same node structure, same types on every node, same charset |
| `JavacOutputParityTest` | maven-compiler-plugin's compile: plain javac, same options, default policy | same result, diagnostics, output and class file bytes, registered directly and through `-processorpath` + `-Xplugin`; both modes write the same census; a failed compile keeps no tree |
| `ForkedJavacTest` | forked command-line javac without the plugin | same exit code, output and class files with the forked wiring; census equal to the in-process run |
| `CompilePolicyTest` | javac's own compile states | the table above; the policy option changes no class file of a passing compile and changes failing ones |
| `StringFoldingTest`, `EncodingTest`, `PluginRobustnessTest`, `PluginOptionsTest`, `KnownRewriteDefectsTest` | as named | folding bound, charsets, notices and option forms, known rewrite defects |

`ClassicParserParityTest`'s type equality holds when `--release` equals the running JDK, without Lombok, and with the
same `--add-exports`; its charset comparison holds for UTF-8 sources (for ISO-8859-1 the classic parser reports
windows-1252, `EncodingTest` pins that).

Known rewrite 8.89.0 defects, refused the same way by the bridge (`print-mismatch` or `failed`, no tree) and by the
classic parser (`ParseError`), so a rewrite upgrade that fixes one shows up as a test to update: two `CommentTest`
cases (fixed upstream by openrewrite/rewrite#8564), a receiver parameter, a multi-u unicode escape such as
`\uuuu0021`, lone CR line ends, and a unicode-escaped line feed inside a line comment. To report upstream:
`extractRecordComponentAnnotations` indexes the original annotations by `getAnnotationMirrors()`, and the Javadoc
visitor attributes references with javac's live Attr outside a deferred diagnostic handler.

## Migration recipe

None. L01 adds a module and changes no API that callers use. The caller-facing change comes with L07
(rewrite-maven-plugin `rewrite.lstSource=compiler`) and L03 (the extension that wires the plugin without POM edits).
