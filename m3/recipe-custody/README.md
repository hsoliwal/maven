# M3 Maven compiler service admission

The source-built Maven 4.1 fork could not load Maven Compiler Plugin 4.0.0-beta-5: the plugin factory and the session default both registered an unqualified priority-zero ArtifactManager. InjectorImpl correctly rejected that tie.

The existing plugin manager now omits a default only when a discovered unqualified priority-zero binding would tie it and no higher-priority provider already resolves the choice. Service discovery stays lazy. Named providers, ranked choices, plugin-to-plugin ambiguity, exceptions and public interfaces retain their prior contracts. The injector itself is unchanged.

## Review the behavior

- `impl/maven-core/src/main/java/org/apache/maven/plugin/internal/DefaultMavenPluginManager.java`: one private admission atom and its existing call site.
- `impl/maven-core/src/test/java/org/apache/maven/plugin/internal/M3PluginSessionServicesTest.java`: ten independent lazy/default/named/ranked/ambiguity/exception tests.
- `evidence/`: actual verification receipts; the source-built distribution is not represented as a full Maven CI pass.

## Recipe-first replay

The four engine sources and the original Maven/OpenRewrite fixture are unchanged finite copies from the canonical Synexia recipe owner. The sealed recipe is `m3-maven-qualified-session-services-20261017`. No independent receiver transformation engine was introduced.

Run with Java 21 and Maven 3.9.9, from the repository root, using a fresh output directory:

```sh
mvn -f m3/recipe-custody/synexia-openrewrite-recipes/verification/m3-build-shared-ast-20261017/pom.xml \
  -Dm3.crate=m3-maven-qualified-session-services-20261017 \
  -Dm3.kind=java \
  -Dm3.input="$PWD/m3/recipe-custody/datasets/maven-session-services/before" \
  -Dm3.output="$PWD/target/m3-session-services-replay" verify
```

The fixture executes OpenRewrite `Recipe.run`, requires real Java syntax trees and byte-exact printing, rejects missing, changed and duplicate input, checks the fixed point, and writes only the two sealed postimages. Compare those emitted paths to the two product files. Correct the recipe when a change is needed; do not patch its output.

## Verification and limits

The original `-pl impl/maven-core -am verify` lifecycle passed for all thirty selected reactor modules. Maven core ran 758 tests, with zero failures, zero errors and one skip, including the new ten tests. Original Checkstyle, Spotless and API-compatibility gates remain enabled. Source and test bytes after that lifecycle exactly equal the recipe's output.

The resulting source-built Maven distribution passed the actual shared-AST compiler-plugin fixture with two consumers, original task/tree/input identity, byte-identical stock/M3 class files, identical generated source and unchanged incremental compilation. The final shared-AST artifact used 895 Java sources and source-built donor dependencies. That artifact and its optional tool integration belong to the M3JDK21 companion work, not to Maven core.

Earlier full Maven CI attempts remain failed in their retained receipts because the original Mimir CLI integration could not resolve its preseed artifacts through the environment network. The successful core lifecycle and separately packaged distribution do not erase or redefine that gate. Packaging after core qualification used the original skip-tests artifact target; it is not counted as a test run.

The algorithm catalogue selection is lazy DI default admission: metadata-only detection of an otherwise ambiguous default tie. General injector priority changes, eager provider instantiation and unconditional removal of core defaults were rejected because they change valid existing behavior. Donor implementation is the original Apache Maven InjectorImpl binding metadata and its existing priority rules (Apache-2.0).
