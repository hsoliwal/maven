<!--
 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements. See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to You under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License. You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->
# M3JDK compiler integration in this Maven fork

This fork has an optional compiler consumer in its existing Maven plugin manager.
Run Maven on M3JDK, or select M3JDK through the compiler plugin's ordinary toolchain
or forked executable configuration, and enable it for an invocation:

```sh
mvn -Dm3.ast=true compile
```

The `m3.ast` property is disabled by default. User properties take precedence over
project properties, then system properties. An explicit `-Dm3.ast=false` disables
the automatic argument even when a project or system property enabled it.

The hook applies only to `org.apache.maven.plugins:maven-compiler-plugin` goals
`compile` and `testCompile`. It runs after Maven has merged and interpolated each
execution's configuration, in both the Maven 3 and Maven 4 mojo paths. It appends
`-Xplugin:M3AST` to `compilerArgs` without changing any existing argument or
processor configuration. A complete existing `-Xplugin:M3AST ...` argument takes
precedence. A different configured `compilerId` is rejected when this feature is
enabled; the shared AST plugin is a javac plugin.

The compiler plugin still creates and invokes its normal compiler task. It owns
annotation processing, toolchain selection, fork behavior, compiler execution and
plugin realms. M3AST receives javac's actual compilation task through javac's
plugin loader. Maven creates no secondary parser and no global AST cache.

## Optional compiler outputs

Configure the existing compiler plugin when a consumer needs outputs:

```xml
<compilerArgs combine.children="append">
  <arg>-Xplugin:M3AST lossless trace=/owned/compile-unique.tsv image=/owned/compile-unique.m3prim slots=/owned/compile-unique.slots.tsv fold-expressions</arg>
</compilerArgs>
```

This example is one compiler argument. `lossless` retains comments and end
positions and disables javac string folding before parsing. `trace` creates a new
TSV file and fails if that path exists. `image` writes the normal M3 primitive
image only after a successful compiler task. `slots` writes its slot TSV and
requires `image`; `fold-expressions` opts into attributed constant-expression
collection and also requires `image`. The zero-argument form creates no output
and does not request a projection eagerly.

Every compiler invocation must own its output paths. A reactor module, main
compile, test compile, retry, incremental invocation and forked invocation can
each execute the compiler separately. Do not put one trace path in a parent POM
or reuse one across a whole reactor. Configure per-execution paths with an
invocation-specific suffix, or use the zero-argument form. Maven does not assign
global paths or alter the annotation-processor path to load this plugin.

## Qualification

`M3AstCompilerConfigurationTest` covers the native XML configuration merge,
immutable input retention, exact goal scope, properties, duplicate arguments,
and preservation of processor and fork settings. Run it in this fork's reactor:

```sh
mvn -pl impl/maven-core -am -Dtest=M3AstCompilerConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Run a distribution built from this fork against the M3JDK image as a separate
integration gate. A stock Maven distribution packaging test does not establish
this native Maven 4 source integration, and a compiler-module image on stock
OpenJDK does not establish the complete M3JDK VM build.

## Source and class content deltas

M3JDK21 owns source and class content comparison in
`com.m3.file.M3File`, at
`m3/file/src/main/java/com/m3/file/M3File.java` in the product repository.
Build consumers should use that existing owner when admitting file generations.
A retained fingerprint can be compared without invoking a source or class-file
parser; capturing a fresh fingerprint can still require reading the content.

The `contentDelta` contract uses compact state flags:

| Flag | Value | Meaning |
| --- | --- | --- |
| `ADDED` | 1 | The previous content was known absent and current content is present. |
| `REMOVED` | 2 | Previous content was present and current content is known absent. |
| `CONTENT_CHANGED` | 4 | Both contents are present and their recorded fingerprints differ. |

A `null` fingerprint means known absence. It must not represent an unread file,
permission failure, unknown state, or failed capture. A zero delta describes
equal recorded content states; it does not establish that compilation can be
skipped. Modification time and size alone cannot establish content equality,
and fingerprint capture is not an atomic filesystem snapshot during concurrent
writes.

Keep three inputs distinct:

- Source fingerprints identify observed source bytes. Syntax and semantic facts
  belong to the original M3AST compiler task and its generation.
- Class fingerprints identify observed class bytes. A digest comparison does
  not classify ABI, constant, annotation, module, or dependency changes.
- Build context includes compiler identity, ordered arguments and classpaths,
  processors, plugin configuration, declared resources, and required outputs.
  Context changes can invalidate results while source bytes stay equal.

The existing receiving points remain
[`M3AstCompilerConfiguration`](../impl/maven-core/src/main/java/org/apache/maven/plugin/internal/M3AstCompilerConfiguration.java)
and its two calls in
[`DefaultMavenPluginManager`](../impl/maven-core/src/main/java/org/apache/maven/plugin/internal/DefaultMavenPluginManager.java).
They admit the builtin plugin to the compiler's original task.
[`M3AstCompilerConfigurationTest`](../impl/maven-core/src/test/java/org/apache/maven/plugin/internal/M3AstCompilerConfigurationTest.java)
qualifies that configuration behavior. Compilation planning still belongs to
the compiler plugin; lifecycle, plugin realms, and reactor execution retain
their existing owners.

This change documents the content-comparison contract. It does not install an
M3File delta receiver in Maven's planner or establish new build skipping.
A receiving change must handle dependency closure, generated and deleted
outputs, processor effects, and publication only after successful execution.
Its build-avoidance evidence must come from the actual Maven distribution.

The upstream
[compiler incremental policies](https://maven.apache.org/plugins/maven-compiler-plugin-4.x/compile-mojo.html#incrementalCompilation)
and [build-cache concepts](https://maven.apache.org/extensions/maven-build-cache-extension/concepts.html)
describe separate compiler and module-output decisions that a receiver must
respect.
