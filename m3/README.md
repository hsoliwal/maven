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

## Source and class deltas

The [M3JDK21 build reuse contract](BUILD_DELTAS.md) specifies source and class
observations, compiler dependency invalidation, output ownership, and bounded
cache reuse. It distinguishes the current same-task compiler integration from
subsequent cross-build reuse and selective-compilation qualification.
