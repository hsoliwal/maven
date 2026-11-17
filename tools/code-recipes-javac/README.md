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
(that is L02). It changes no compiler option and no compiler output: the tests compare javac's result, diagnostics,
output and class files byte for byte with and without the plugin, and the plugin never fails a compile.

The module (`org.apache.maven.plugins:maven-code-recipes-javac`, a plain jar, JDK 21) calls OpenRewrite's own
`ReloadableJava21ParserVisitor` (rewrite-java-21 8.89.0, Apache-2.0), the class rewrite's `Java21Parser` runs after
its private javac. No rewrite code is copied. Build and test it on its own:

```
mvn -f tools/code-recipes-javac/pom.xml install
```

## How it works

* `MavenRecipesPlugin` (`com.sun.source.util.Plugin`, name `MavenRecipes`) registers `LstCollector` as a task listener.
  It changes no compiler option and no `JavaCompiler` field: javac turns on comment retention and end positions by
  itself as soon as any task listener is registered (`JavaCompiler.parse`, JDK 21: `keepComments = true;
  genEndPos = true;` under `!taskListener.isEmpty()`); the forked command-line javac test proves the trees are
  complete there too.
* javac keeps its default string folding: its parser folds a chain of string literals such as `"a" + "b"` into one
  literal (`JavacParser.foldStrings`). Turning folding off, as rewrite's private javac does, made javac itself overflow
  its stack on 2000 or more concatenated literals. The parser visitor turns a folded literal into one `J.Literal` whose
  source is the whole chain, which prints back exactly but is not the tree the classic parser builds. `FoldedStrings`
  therefore splits each folded literal back into the left-associative `J.Binary` nodes of the classic parser, and
  splices a folded literal that ends a `+` chain into that chain, as javac re-associated it. `StringChain` reads the
  literals and the whitespace and comments around each `+` from the source text (after translating unicode escapes,
  `UnicodeText`) and decodes each literal; a chain is split only when the decoded values join to javac's value. A
  chain longer than 128 literals, one with escaped surrogates, or one it cannot read stays folded, and the file is a
  `print-mismatch` with that reason, so no wrong tree reaches a recipe.
* The tree's charset is the one javac decoded the file with (`CompileCharset`): javac's file manager decodes with the
  charset maven-compiler-plugin gives it in process, else with `-encoding`. A javax.tools compile gives `-encoding` to
  the file manager only, not to the compiler's options, and with `--release` javac hides its file manager behind a
  delegating one. The charset is therefore read from the file object: javac's `PathFileObject.openReader` wraps the
  file in an `InputStreamReader` over the file manager's decoder, whose encoding is read without reading the file;
  other file objects fall back to the file manager, `-encoding` and the platform default.
* `LstCollector` counts the PARSE events of every file and builds a file's tree at the ANALYZE event of its last
  top-level class: attributed and flowed, not yet desugared. `package-info.java` gets its own ANALYZE event (javac
  gives the package an env for an annotation or a doc comment); a file without any type is built at COMPILATION
  finished. It never reports a diagnostic and never calls `JavaCompiler.errorCount()`, which with `-Werror` would
  itself log an error; it reads `Log.nerrors`.
* `CompilerTreeLst` runs the visitor on the javac unit with the character content javac parsed, so positions match
  by construction, then checks that the tree prints the exact text and, for a file on disk, its exact bytes. The
  static `CompilerTreeLst.toLst(JavacTask, CompilationUnitTree, Path, ExecutionContext)` serves any caller that holds
  a running task.
* A file whose class javac already desugared is never converted: when a class env reached TRANSTYPES, or javac
  dropped the env after generating the class, the row says `skipped: desugared ...`.
* The plugin never fails a compile. An option it does not understand, a census it cannot write, or any failure of its
  own is one line on javac's notice writer, starting with `[MavenRecipes]` (`Notices`), and the compile goes on
  without trees or without the census. The line is not a diagnostic, so `-Werror` and a DiagnosticListener see
  nothing, and plexus-compiler files a line starting with `[` as an informational message, not as a warning.

## Compile policy

javac's compile policy decides whether a file's trees are still undesugared when its last class is analyzed. Proved
by `CompilePolicyTest`:

| policy | effect | result |
|---|---|---|
| default (`byTodo`) | each class is attributed, flowed, desugared and generated before the next | a file with several top-level classes is skipped |
| `-XDcompilePolicy=byfile` | one file at a time, but a superclass declared in a later file is desugared early when an earlier file needs it | that later file is skipped when it holds more than one class |
| `-XDcompilePolicy=simple` | every file is analyzed before any is desugared | every file is built |

Use `-XDcompilePolicy=simple`. The policy option alone changes no class file (proved for every fixture).

## Use in a build

The plugin needs the jdk.compiler internals exported to the unnamed module, both for an in-process compile
(`.mvn/jvm.config`) and for a forked one (`-J` flags):

```
--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
```

```xml
<plugin>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-code-recipes-javac</artifactId>
        <version>0.1.0-SNAPSHOT</version>
      </path>
    </annotationProcessorPaths>
    <compilerArgs>
      <arg>-XDcompilePolicy=simple</arg>
      <arg>-Xplugin:MavenRecipes census=${project.build.directory}/recipes/LST_CENSUS.tsv root=${project.basedir} buildDir=${project.build.directory}</arg>
    </compilerArgs>
  </configuration>
</plugin>
```

L03 adds this wiring through a core extension instead, so no POM is edited. Options, after the name and separated by
spaces (`PluginOptions`):

| option | meaning | default |
|---|---|---|
| `census=<file>` | write `LST_CENSUS.tsv` there when the compilation finishes | no census |
| `root=<dir>` | the directory source paths are made relative to | the working directory |
| `buildDir=<dir>` | the build directory: files under it are generated sources and get no tree, as rewrite-maven-plugin skips every source root under the build directory | `<root>/target` |

javac splits the text after the plugin name on runs of whitespace and reads no quotes (`Arguments.getPluginOpts`). A
piece that starts no known `key=` continues the value before it and is joined back with one space, so a path with
single spaces arrives whole, as in the example above. A value may also be quoted (`"..."` or `'...'`) or a `file:`
URI (for example `root=${project.baseUri}`); only the URI form keeps runs of spaces or tabs. An unknown option, an
empty value or a value that is no path turns the plugin off for that compile with one notice line, instead of
silently dropping the census.

rewrite-java-21 depends on rewrite-java-lombok, which brings `org.projectlombok:lombok`; that jar registers an
annotation processor, and on a processor path it would run Lombok over the build's sources. The POM excludes it: the
parser visitor only compares the names `lombok.val`, `lombok.var` and `lombok.Generated` as strings. None of the 39
runtime jars registers an annotation processor or a javac plugin. The plugin's own registration
(`META-INF/services/com.sun.source.util.Plugin`) is copied after `compile` and removed before it, because javac lists
`Plugin` services on its class path even without `-Xplugin`, and a registration whose class does not exist yet fails
this module's own compile.

## LST_CENSUS.tsv

One row per parsed file, ordered by path, UTF-8, LF line ends:
`path, parse_events, print_equal, attributed_method_calls, unattributed_method_calls, status, built_at`.

| status | meaning |
|---|---|
| `ok` | tree built and it prints the exact source; the only status that hands a tree to recipes |
| `print-mismatch` | tree built but it does not print the exact source |
| `print-mismatch: <n> string chain(s) folded by javac left as one literal ...` | a folded string chain could not be split (longer than 128 literals, escaped surrogates, or unreadable); the tree would differ from the classic parser's |
| `skipped: compile errors` | the compilation reported errors, so no tree is kept |
| `skipped: generated source` | a file under the build directory (`buildDir`), where rewrite-maven-plugin skips every source root |
| `skipped: module-info has no OpenRewrite tree` | OpenRewrite has no tree for it; `JavaParser.accept` excludes it as well |
| `skipped: desugared before analysis finished; ...` | see Compile policy |
| `skipped: analysis incomplete` | the compilation finished before every class of the file was analyzed |
| `failed: <exception>` | the conversion threw or reported an error |

Calls are method invocations, constructor calls and method references; a call is attributed when it carries a
method type. `built_at` is ANALYZE, COMPILATION or `-`.

## Tests and oracles

Run on JDK 21 by `mvn verify`. Fixtures (`Fixtures`, `FoldingFixtures`, `EdgeFixtures`) cover a plain class, generics
and lambdas, sealed types, records and pattern `switch`, five top-level types in one file, annotations with Javadoc,
several classes in one file, `package-info.java` with and without an annotation, a file without types,
`module-info.java`, CRLF line ends, unicode escapes, non-ASCII text, text blocks, comments, string chains (comments
around `+`, escapes, unicode escapes, text blocks, numeric operands, parentheses, annotation values, case labels,
compound assignment, a 128-literal chain), concatenations of 1500, 2000, 3000 and 10000 literals, ISO-8859-1 and
windows-1252 sources, paths with spaces, a project under a directory named `generated-sources`, generated sources,
a cross-file superclass, and three failing compiles (a UTF-8 BOM, which javac 21 rejects as
`illegal character: '\ufeff'`; a type error; `-Xlint:all -Werror` with a warning). Cases marked `rewrite-java-tck`
are copied from OpenRewrite's parser test kit (github.com/openrewrite/rewrite, `rewrite-java-tck`, Apache-2.0) at
tag v8.89.0, except the two CommentTest cases of the known defect below, which upstream added with its fix (commit
3c87c3ea6e); each fixture names the test it comes from.

| test | oracle | what must hold |
|---|---|---|
| `CompilerTreeLstTest` | the source bytes | every fixture file: status `ok`, exactly 1 PARSE event, printed bytes equal the file, 0 unattributed calls; files under the build directory are skipped, a directory named `generated-sources` is not |
| `ClassicParserParityTest` | rewrite-maven-plugin's parse: `JavaParser.fromJavaVersion()` (classic `Java21Parser`, string folding off) | same printed text, same node structure (`TreeVisitingPrinter`), same types on every node, same charset; this holds for the split string chains too |
| `JavacOutputParityTest` | maven-compiler-plugin's compile: plain javac, same options | same result, diagnostics, output and class file bytes with the plugin initialised directly and through `-processorpath` + `-Xplugin`, the concatenation stress fixtures included; both modes write the same census; a failed compile keeps no tree |
| `ForkedJavacTest` | forked command-line javac of the running JDK without the plugin | same exit code, output and class files; census equal to the in-process run, for the encoding and stress fixtures too |
| `CompilePolicyTest` | javac's own compile states | the table above; the policy option changes no class file |
| `StringFoldingTest` | the compile options and the census | the plugin sets no compiler option; a chain longer than 128 literals or with escaped surrogates is a `print-mismatch` without a tree |
| `EncodingTest` | the file bytes and the classic parser with the same charset | ISO-8859-1 and windows-1252 sources, with the charset on the file manager or only as `-encoding`, with and without `--release`, initialised directly and through `-Xplugin`: status `ok`, the compile's charset, the exact bytes |
| `PluginRobustnessTest`, `PluginOptionsTest` | plain javac | paths with spaces in plain, quoted and URI form are read whole; a census that is a directory or lies under a file, an empty value and an unknown option leave result, diagnostics and class files unchanged and print one notice line, in process and forked |
| `KnownRewriteDefectsTest` | the classic parser | the rewrite 8.89.0 defects below get no tree from either |

Known rewrite 8.89.0 defects, refused the same way by the bridge (`print-mismatch` or `failed`, no tree) and by the
classic parser (`ParseError`), so a rewrite upgrade that fixes one shows up as a test to update: two `CommentTest`
cases (a comment between an annotation and the modifiers drops a modifier; fixed upstream by
openrewrite/rewrite#8564), a receiver parameter (dropped from the printout), a multi-u unicode escape such as
`\uuuu0021` (NumberFormatException), lone CR line ends, and a unicode-escaped line feed inside a line comment.

## Migration recipe

None. L01 adds a module and changes no API that callers use. The caller-facing change comes with L07
(rewrite-maven-plugin `rewrite.lstSource=compiler`) and L03 (the extension that wires the plugin without POM edits).
