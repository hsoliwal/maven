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
# Code Clustering: Clang AST Front End

An `AtomFrontEnd` for the [code clustering plugin](../code-clustering/README.md) that atomizes C, C++, Objective-C
and Objective-C++ on the compiler's own syntax tree. For each file it runs

```
clang <the file's flags from compile_commands.json> -fsyntax-only -Xclang -ast-dump=json -w -ferror-limit=0 \
      -fno-delayed-template-parsing <file>
```

and reads the dump as a stream while clang writes it (a file that includes `windows.h` and the C++ standard library
dumps about 450 MB; nothing of it is held except the main file's declarations). Atom boundaries and names come from
the tree, and the four planes are computed by the plugin's `AtomBuilder` from one pre-order walk of that tree, so
constructors, destructors, operators, functions made by macros and the branch of each `#if` that the build really
compiles are exact.

## Use

Add the module as a dependency of the plugin; clang must be installed. The root reactor builds and installs it
(`maven-code-clustering-clang`) with the other code-clustering modules.

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-code-clustering-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <dependencies>
    <dependency>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-code-clustering-clang</artifactId>
      <version>0.1.0-SNAPSHOT</version>
    </dependency>
  </dependencies>
</plugin>
```

```
mvn code-clustering:cluster -Dclustering.sourceDirectories=src/main/cpp
```

The flags come from `compile_commands.json`, which CMake, Meson, Ninja and Bear write, and which the native build
plugin publishes for every native build. Without clang the front end takes no file and the plugin's lexical front end
atomizes C-family files as before.

## Configuration

System properties (`-D`), or the environment variable in brackets:

| Property | Default | Meaning |
| --- | --- | --- |
| `clustering.clang.enabled` (`CLUSTERING_CLANG_ENABLED`) | `true` | `false` turns the front end off |
| `clustering.clang.executable` (`CLANG`) | `clang` on PATH, then the LLVM folders | the clang to run, a path or a name |
| `m3.polyglot.llvm.home`, `m3.llvm.home` (`M3_LLVM_HOME`, `LLVM_HOME`) | unset | when no explicit clang executable is set, select this LLVM build/install root's `bin/clang` in the listed precedence order; blank values are skipped and a missing selected toolchain does not silently fall back |
| `clustering.clang.compileCommands` | none | compile_commands.json files or folders, comma separated, asked first |
| `clustering.clang.flags` (`CLUSTERING_CLANG_FLAGS`) | none | flags for files no database covers; without them those files go to the lexical front end |
| `clustering.clang.stubIncludes` | off | a folder for empty stand-ins of headers clang cannot find |
| `clustering.clang.threads` | half the processors | clang processes at a time |
| `clustering.clang.timeoutSeconds` | 300 | the longest one file may take |

**Which flags a file gets.** The configured databases first, then the nearest `compile_commands.json` or
`build/compile_commands.json` in the file's folder or a parent folder, as clangd looks for it. A file with its own
entry uses it. A header without one borrows the entry of the closest source file, as clangd does: the same base name
first, then the longest common folder path. It is parsed as a C, C++, Objective-C or Objective-C++ header after that
file (`.hpp`, `.hh`, `.hxx` are always C++). The command is rewritten without a shell: the compiler becomes clang
(`--driver-mode=cl` for `cl` and `clang-cl` entries, `--driver-mode=g++` for C++ drivers, so `.c` files compiled as
C++ stay C++), include paths, macros, standard and target are kept, and outputs are dropped (`-o`, `-c`, `-MD`,
`-MF`, `-Wp,-MD,<file>`, `-MJ`, `/Fo`, `/Fd`, precompiled headers, the source file itself), so clang writes nothing
into the build folder. The value of an option such as `-include x.h` is
never taken for the source file. A compiler launcher (`ccache`, `sccache`, `distcc`, `icecc`) is dropped.

**When clang cannot read a file** (no command, a fatal error such as a missing header, a refused argument, a
timeout, a crash) the file goes to the lexical front end and a warning names the reason. Errors that are not fatal
do not: clang still dumps every declaration it could parse. With `clustering.clang.stubIncludes`, each header that
is not found gets an empty stub and the file is parsed again; this is meant for trees whose generated headers (JNI
headers from `javac -h`, `config.h`) do not exist yet.

## Atoms and planes

- **METHOD**: a definition, with a body, of a function, member function, constructor, destructor, conversion
  function or Objective-C method. The symbol is `Owner.name(parameters)` with clang's name (`Point.~Point(0)`,
  `Point.operator==(1)`, `get_7(0)` for `DEFINE_GET(7)`, `Root.add:to:(2)`). Variadic `...` and `(void)` are no
  parameters. Lambdas and local classes belong to the function they are in.
- **TYPE**: the definition of a named struct, class or union, an enum with its constants, or an Objective-C
  interface, implementation, protocol or category (`Root(Category)`).
- Owners are the enclosing records, as in the lexical front end; namespaces are not owners. A member defined outside
  its class (`int Point::norm() const {...}`) is owned by its class, through `parentDeclContextId`, also when the
  class is declared in a header.
- Implicit declarations, template instantiations and `= default` functions are left out; a template is one atom.
  (Clang gives a `= default` function that the file uses a body of generated nodes, so without this rule it would be
  an atom only in files that use it.) The bodies of templates
  that are never instantiated are parsed (`-fno-delayed-template-parsing`; clang's MSVC-target default skips them).
- An atom's text runs from the range's first token to the end of its last token, both in the main file. When the
  definition comes from a macro, the text is the macro call (`DEFINE_GET(7)`).
- Planes: the walk visits every node that is not implicit. The kind is clang's (`CompoundStmt`, `CallExpr`), with
  the operator (`BinaryOperator+`), `_post` for postfix `++`/`--`, and the cast kind
  (`ImplicitCastExpr<LValueToRValue>`). Declarations carry their name (numbered in LOGIC when the atom declares it)
  and a `type` node with the type as written; references carry the referenced name; member names, literals and
  Objective-C selectors are kept. Literal values are clang's, so `0x10` and `16` are the same. Copies that differ
  only in the names they declare share LOGIC and STRUCTURE; another literal changes LOGIC only; another operand
  changes STRUCTURE. Planes are comparable between files atomized by clang; EXACT is comparable with every front end.

## Comparing with the lexical front end

`FrontEndDiff` atomizes a tree with both front ends and puts every difference into a class:

```
java -cp maven-code-clustering-plugin.jar:maven-code-clustering-clang.jar:jackson-core.jar \
  -Dclustering.clang.flags="..." -Dclustering.clang.stubIncludes=target/stubs \
  org.apache.maven.plugins.clustering.clang.FrontEndDiff <root> <output folder> [file list]
```

It writes `differences.tsv` (one row per pair or lone atom), `classes.tsv` (count and three examples per class) and
`outcomes.tsv` (clang's status per file). Files clang could not read are left out of the comparison. The classes are
documented on `AtomDiff`; the ones seen on real trees are:

| Class | What it means |
| --- | --- |
| `SAME` | same symbol, lines and text |
| `TEXT_DIFFERS`, `TEXT_EXTERN_C_PREFIX` | same symbol and lines; the lexical atom starts at `extern "C"` or another column |
| `START_PREPROCESSOR_LINES` | the lexical atom starts earlier, at directive lines (`#include`, a `#define` with continuation lines) |
| `START_ACCESS_SPECIFIER`, `START_TEMPLATE_HEADER` | the lexical atom starts at `public:` or at `template <...>` |
| `OWNER_DIFFERS` | a member defined outside its class: clang qualifies it |
| `OWNER_EXPORT_MACRO`, `NAME_EXPORT_MACRO` | `class SK_API SkMatrix`: the lexical front end takes the export macro for the class name |
| `ARITY_VOID`, `ARITY_VARIADIC` | `(void)` and `...` counted as parameters by the lexical front end |
| `NAME_DESTRUCTOR` | `~X` read as `X` |
| `NAME_MACRO_PASTED`, `CLANG_ONLY_MACRO_GENERATED` | a function a macro made; its name is not in the text |
| `NAME_DIRECTIVE_IN_HEADER`, `KIND_DIFFERS_DIRECTIVE_IN_HEADER`, `LEXICAL_ONLY_DIRECTIVE_IN_HEADER` | the lexical header took in a preprocessor line (`#if defined(X)` names an atom `defined`) |
| `NAME_MACRO_WRAPPER`, `LEXICAL_ONLY_MACRO_CALL` | a block after a macro call gets the macro's name |
| `LEXICAL_ONLY_IN_CONDITIONAL` | the lexical atom is inside an `#if` branch this configuration does not compile |
| `CLANG_ONLY_SPANS_CONDITIONAL` | an `#if` inside the definition unbalanced the lexical front end's braces |
| `LEXICAL_ONLY_NESTED_IN_FUNCTION` | a local class or lambda that clang keeps inside its function |
| `CLANG_ONLY_OPERATOR`, `CLANG_ONLY_OBJC` | operators and Objective-C, which the lexical front end does not atomize |

Counts on JDK native trees, libpng, HarfBuzz and Skia are in the description of the pull request that added this
module.

## Tests

`ClangUnitTests` need no clang: command splitting and rewriting, the stream reader on a hand-written dump (file
tracking through `includedFrom` and macro locations, out-of-line owners, skipped instantiations, the planes of the
walk), database lookup and header borrowing, the lexical fallback, stub headers and the difference classes.
`ClangRealTests` run the installed clang on C++, C, Objective-C and header fixtures with a compile_commands.json, and
print SKIP without clang. Both run in `verify` through exec-maven-plugin. Donor decisions are in
`FOSS_REUSE_DECISION.tsv`.
