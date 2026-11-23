<!-- SPDX-License-Identifier: Apache-2.0 -->
# M3 Gradle opt-in recipe acceptance — original OpenRewrite 8.90.4

This is a **CI-only cross-fork receiver**, not another recipe engine or Gradle plugin. The
canonical source-sealed OpenRewrite transformation, all eight whole-file preimages,
eight postimages, and deterministic fixed-point/refusal JUnit test are owned by
[com.synexia PR #18996](https://github.com/hsoliwal/com.synexia/pull/18996).

The source tree for this test is [Gradle #74](https://github.com/hsoliwal/gradle/pull/74)
at exact immutable commit `6ee4533f36eeea78894ef84b2a6f71abb2512d81`.
The Gradle task/engine behavior is unchanged. The only receiving changes are eight
declarative YAML recipes switching from nonexistent
`org.openrewrite.gradle.plugins.AddPlugin` to the original 8.90.4
`org.openrewrite.gradle.plugins.AddBuildPlugin` (source Git blob
`f07b667fc61c011b1a6c89c760c0f4547070a09f`).

Maven runs **actual** `org.openrewrite:rewrite-gradle:8.90.4`,
`YamlResourceLoader`, `Environment`, `GradleParser`, the original
`GradleProject` marker, and `AddBuildPlugin`. It admits exactly the eight
SHA-256-pinned public Gradle YAML postimages; validates all recipe names,
pluginId/version/class definitions, inserts the plugin into a parsed Gradle
build script, and requires a second pass of every recipe to be a fixed point.
No Maven engine, Gradle build graph or compiler is executed by this test.

The active Maven Actions lane
`.github/workflows/m3-gradle-recipe-sdk-20261123.yml` checks out the exact
Gradle source, executes the original SDK JUnit on JDK21, and retains actual
Surefire evidence. Run locally only when Java21, Maven and the original
dependency closure are available:

```sh
mvn -B -ntp -f tools/gradle-recipe-acceptance/pom.xml \
  -Dm3.gradle.root=/path/to/exact/gradle74 verify
```

This receiver is **not** a substitute for the canonical private
`M3HashPinnedTextSnapshotRecipe` JUnit's deterministic initial emission,
reverse/shuffle, wrong-source/duplicate/drift refusals, and two full replay
fixed points; its own test is narrower by design. Do not claim private SDK
success from the public receiver alone. Both original SDK evidence and
human DCO/license/source-history review remain required for public promotion.

No arbitrary plugin/version is downloaded or applied to the user's build
by this test. The actual plugin remains separately installed and opt-in.
