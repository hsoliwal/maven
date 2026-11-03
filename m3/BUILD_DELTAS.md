<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Maven build reuse through M3JDK21

## Ownership and delivery boundary

M3JDK21 owns the reusable file, compiler, dependency and build services. This
Maven fork consumes those services while retaining Maven's project model,
reactor, lifecycle, toolchains and plugin contracts. Shared services should also
support JDT and other build consumers without duplicating their indexes.

The existing `M3AstCompilerConfiguration` enables `-Xplugin:M3AST` through
`-Dm3.ast=true` for the standard compiler plugin's `compile` and `testCompile`
goals. M3AST observes the actual javac compilation task; this already avoids a
second parser for that task. The hook preserves processors and compiler
configuration. It establishes neither reuse of parsed or attributed state
across builds nor a persistent build cache. See [existing integration](README.md).

The initial M3File increment provides bounded content snapshots for observing
raw source and class changes. It is an observation primitive, not an ABI
classifier, dependency graph, compiler-skipping decision or complete incremental
Maven runtime. A source snapshot is compared with its previous source snapshot;
a class snapshot is compared with its previous class snapshot. Comparing source
and class digests with each other has no meaning.

## Delta contract

Maintain distinct evidence for each owned file/version:

| Evidence | Purpose |
| --- | --- |
| Raw-byte SHA-256 and length | Identify content changes independently of timestamps. |
| Provider/path identity and generation | Bind evidence to the correct resource and observation. |
| Source syntax and compiler-context identity | Qualify reuse of parsed source. |
| Compiler-observable declarations and lookup dependencies | Compute which compilation units require renewed analysis. |
| Class content and structural projection | Distinguish changed outputs from changes affecting compilation consumers. |
| Producer and output manifest | Associate a compilation action with every generated output. |

File events and metadata nominate candidates. Reads must preserve M3File's
provider, link and byte-limit contracts; concurrent modification or incomplete
observation cannot authorize reuse. Metadata checks alone cannot guarantee a
coherent snapshot under an adversarial concurrent writer: action inputs need
immutable versions, coordination, or conservative invalidation.

A source edit may produce identical class bytes. A method-body edit may change
class bytes while preserving compilation interfaces. A missing or externally
modified class can require repair without any source edit. Comment changes may
affect documentation, processors or debug positions; do not erase them from all
action keys.

## Build state and invalidation

Execute the dependency feedback loop:

1. Observe source, resource, class and build-configuration deltas against the
   last successful state.
2. Invalidate affected actions and compilation units; reuse only qualified state.
3. Compile the affected units using the existing compiler task owner. Capture
   dependencies during compiler attribution, before lowering loses information.
4. Compare complete generated class sets with prior outputs; remove obsolete
   owned outputs and propagate structural or lookup changes to consumers.
5. Repeat until no new compilation consumer is affected. Handle cycles as
   compilation groups; fall back conservatively when evidence is incomplete.
6. Publish validated outputs and dependency state as one successful generation.

Structural equivalence must include accessible declarations, constants,
annotations, generic signatures, hierarchy, record/sealed metadata and module
visibility. Binary compatibility alone does not establish clean-build
equivalence: added overloads and name shadowing can alter source resolution.
Record lookup dependencies as well as selected members. Inlined constants can
leave no declaring-field reference in the consumer classfile; collect those
source dependencies before lowering ([JLS 21, chapter 13][jls]).

Each source can own multiple nested, anonymous, secondary or generated classes.
Persist that mapping. Removed outputs must not survive as stale compilation
inputs. Compilation-interface reuse does not permit skipping tests or packaging
whose runtime inputs changed.

## Model and action keys

Reuse existing Maven `DefaultModelBuilder` and Resolver caches. A local POM hash
alone cannot identify its effective model: include parents, imported BOMs,
activated profiles, properties, settings and relevant environment inputs.
Preserve repository freshness policies for snapshots and metadata.

Action keys include schema version, action implementation and configuration,
toolchain/compiler identity, options, encoding, ordered classpath/module path
contents, processors, generated inputs and relevant resources. Store output
digests and ownership alongside the key. Unknown processor/plugin inputs or
effects require ordinary execution or broader invalidation; arbitrary plugins
cannot be declared cacheable solely because Java files are unchanged.

Failures and cancellation must not advance the successful baseline. Stage
outputs privately; reject stale generations and publish atomically where the
provider supports it. Otherwise use a recoverable commit protocol. Concurrent
actions must not share output ownership. Every rebuild decision should retain
a reason and the evidence that invalidated reuse.

## Richer functionality with bounded retained state

Separate caches for daemon/JIT state, POM models, syntax, compiler attribution,
dependency indexes and action outputs. Each needs its own key, invalidation
rules and memory budget. Reuse immutable M3 atoms; represent graph/manifest state
with dynamic arrays, integer IDs, offsets and masks. Materialize public facades
on demand. Evict derived state or spill indexed blocks without invalidating
live handles or successful output manifests.

Measure cold and warm no-change builds, parse/analysis counts, files compiled,
actions executed, bytes read, peak/retained heap and cache storage. Report actual
measurements; richer capability does not imply a proven memory or speed gain.

## Qualification matrix

| Change or event | Required evidence |
| --- | --- |
| Same content; timestamp changed | No false content delta. |
| Same-length source/class edit; timestamp preserved | Raw content delta detected. |
| Body-only edit | Owner output refreshed; consumers skipped only with complete compiler evidence; affected runtime actions invalidated. |
| Constant, signature, overload or hierarchy change | Consumer closure matches a clean compilation. |
| Delete/rename; nested/generated type removed | Obsolete owned outputs absent; consumers diagnosed/rebuilt. |
| Class missing, corrupt or externally changed | Repair required despite unchanged sources. |
| POM parent/profile, classpath order, toolchain or processor change | Appropriate model/actions invalidated. |
| Failed/cancelled/concurrent build | No false successful baseline or mixed output generation. |
| Processor with undeclared inputs; reflection-driven tests | Conservative execution retained. |
| Cache eviction, watcher loss, cold restart | Correctness preserved; explainable fallback. |

Raw snapshot tests qualify only observation. Semantic scheduling requires
compiler-backed differential tests against clean builds, followed by forked
Maven distribution and full M3JDK21 integration gates. Recipe replay must reach
a deterministic fixed point before promotion.

## Primary donor catalogue

- [Apache Maven Compiler Plugin][compiler]: existing incremental selection and
  documented structural-detection limits.
- [Maven Build Cache Extension][cache]: input calculation, output manifests and
  restoration; inspect `DefaultProjectInputCalculator` and `MavenProjectInput`.
- [Apache mvnd][mvnd]: daemon, plugin-classloader and JIT reuse.
- [Eclipse JDT][jdt]: `IncrementalImageBuilder`, `ReferenceCollection`, `State`
  and `ClassFileReader.hasStructuralChanges` for dependency-driven build loops.
- [JDK JavaCompiler][javac]: sequential file-manager reuse; this does not promise
  persistent parsed/attributed compiler-state reuse.

[jls]: https://docs.oracle.com/javase/specs/jls/se21/html/jls-13.html
[compiler]: https://maven.apache.org/plugins/maven-compiler-plugin-4.x/compile-mojo.html
[cache]: https://github.com/apache/maven-build-cache-extension
[mvnd]: https://github.com/apache/maven-mvnd
[jdt]: https://github.com/eclipse-jdt/eclipse.jdt.core
[javac]: https://docs.oracle.com/en/java/javase/21/docs/api/java.compiler/javax/tools/JavaCompiler.html
