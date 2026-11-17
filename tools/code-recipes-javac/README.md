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
(that is L02). It changes no compiler output: the tests compare javac's result, diagnostics and class files byte for
byte with and without the plugin.

The module (`org.apache.maven.plugins:maven-code-recipes-javac`, a plain jar, JDK 21) calls OpenRewrite's own
`ReloadableJava21ParserVisitor` (rewrite-java-21 8.89.0, Apache-2.0), the class rewrite's `Java21Parser` runs after
its private javac. No rewrite code is copied. Build and test it on its own:

```
mvn -f tools/code-recipes-javac/pom.xml install
```

## How it works

* `MavenRecipesPlugin` (`com.sun.source.util.Plugin`, name `MavenRecipes`) registers `LstCollector` as a task listener
  and sets one compiler option, `allowStringFolding=false`. Without it javac's parser folds `"a" + "b"` into one
  literal and the tree no longer matches the source. Constant expressions are still folded during attribution, so
  the class files stay the same (proved on the `string-folding` fixture: constants in `case` labels, annotation
  values and mixed concatenations).
* javac turns on comment retention and end positions by itself as soon as any task listener is registered
  (`JavaCompiler.parse`, JDK 21: `keepComments = true; genEndPos = true;` under `!taskListener.isEmpty()`). The
  plugin therefore touches no `JavaCompiler` field; the forked command-line javac test proves the trees are complete
  there too.
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
      <arg>-Xplugin:MavenRecipes census=${project.build.directory}/recipes/LST_CENSUS.tsv root=${project.basedir}</arg>
    </compilerArgs>
  </configuration>
</plugin>
```

L03 adds this wiring through a core extension instead, so no POM is edited. Options, after the name and separated by
spaces: `census=<file>` writes the census when the compilation finishes; `root=<dir>` is the directory tree source
paths are made relative to (default: the working directory). javac splits plugin options on spaces, so neither path
may contain one. An unknown option fails the plugin's start instead of silently dropping the census.

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
| `skipped: compile errors` | the compilation reported errors, so no tree is kept |
| `skipped: generated source` | a file under `generated-sources` or `generated-test-sources`, which rewrite-maven-plugin skips too |
| `skipped: module-info has no OpenRewrite tree` | OpenRewrite has no tree for it; `JavaParser.accept` excludes it as well |
| `skipped: desugared before analysis finished; ...` | see Compile policy |
| `skipped: analysis incomplete` | the compilation finished before every class of the file was analyzed |
| `failed: <exception>` | the conversion threw or reported an error |

Calls are method invocations, constructor calls and method references; a call is attributed when it carries a
method type. `built_at` is ANALYZE, COMPILATION or `-`.

## Tests and oracles

Run on JDK 21 by `mvn verify`. Fixtures (`Fixtures`) cover a plain class, generics and lambdas, sealed types, records
and pattern `switch`, five top-level types in one file, annotations with Javadoc, several classes in one file,
`package-info.java` with and without an annotation, a file without types, `module-info.java`, CRLF line ends,
unicode escapes, non-ASCII text, text blocks, comments, string folding, generated sources, a cross-file superclass,
and three failing compiles (a UTF-8 BOM, which javac 21 rejects as `illegal character: '﻿'`; a type error;
`-Xlint:all -Werror` with a warning). Cases marked `rewrite-java-tck` are copied from OpenRewrite's parser test kit
(github.com/hsoliwal/rewrite at 1e6bdc4, Apache-2.0); each fixture names the test it comes from.

| test | oracle | what must hold |
|---|---|---|
| `CompilerTreeLstTest` | the source bytes | every fixture file: status `ok`, exactly 1 PARSE event, printed bytes equal the file, 0 unattributed calls |
| `ClassicParserParityTest` | rewrite-maven-plugin's parse: `JavaParser.fromJavaVersion()` (classic `Java21Parser`) | same printed text, same node structure (`TreeVisitingPrinter`), same types on every node, same charset |
| `JavacOutputParityTest` | maven-compiler-plugin's compile: plain javac, same options | same result, diagnostics and class file bytes with the plugin initialised directly and through `-processorpath` + `-Xplugin`; both modes write the same census; a failed compile keeps no tree |
| `ForkedJavacTest` | forked command-line javac of the running JDK without the plugin | same exit code, output and class files; census equal to the in-process run |
| `CompilePolicyTest` | javac's own compile states | the table above; the policy option changes no class file |

Two `CommentTest` cases (a comment between an annotation and the modifiers) cannot be printed back by rewrite 8.89.0
itself: a modifier is dropped from the tree. This was fixed upstream after 8.89.0 (openrewrite/rewrite#8564). The
classic parser returns a `ParseError` for them; the bridge reports `print-mismatch` and hands out no tree. The test
fixes that outcome, so moving to a fixed rewrite version shows up as a test to update.

## Migration recipe

None. L01 adds a module and changes no API that callers use. The caller-facing change comes with L07
(rewrite-maven-plugin `rewrite.lstSource=compiler`) and L03 (the extension that wires the plugin without POM edits).
