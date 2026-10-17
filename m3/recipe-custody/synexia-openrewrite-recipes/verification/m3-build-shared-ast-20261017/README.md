<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# M3 build and shared AST recipe qualification

This bounded Maven crate executes the existing `M3HashPinnedJavaSnapshotRecipe` and `M3HashPinnedTextSnapshotRecipe` implementations. It does not implement another source transformation engine. Exact target templates live in the canonical module resources. Frozen target-contract tests accompany their respective file passes.

The SDK test parses Java targets as Java, executes the real recipe, checks every emitted postimage against its sealed template, proves a no-change replay, and checks drift/missing/duplicate admission before writing the generated candidate image. An SDK receipt does not assert runtime behavior, full source-mirror qualification, or whole-reactor success.

Run with JDK 21 and an approved offline Maven cache:

```sh
mvn -o -B -ntp -f synexia-openrewrite-recipes/verification/m3-build-shared-ast-20261017/pom.xml \
  -Dm3.crate=CRATE -Dm3.kind=java -Dm3.input=/absolute/exact/preimage/repository \
  -Dm3.output=/absolute/fresh/generated/image verify
```

`m3.kind=text` selects the existing text recipe for admitted non-Java assets. Java targets never use that lane. `-Pm3-repository-source-mirror` selects the existing repository-source OpenRewrite coordinate; the default SDK 8.17.1 compatibility lane matches the already-existing focused verification crates. Keep those dependency-provider results distinct. Acquisition of missing bootstrap artifacts is preparation, not an offline production build pass.

The generated `application.tsv` records actual SDK result hashes. Preserve Maven logs and non-skipped Surefire reports with the run. Do not hand-edit generated files: change the recipe payload, select a fresh output directory, and rerun all affected gates.

Serial refinements of the same file may be qualified as intermediate candidates. The delivered combined file recipe must also replay unchanged on the final image; an earlier whole-file hash seal must not be broadened merely to accept a later implementation.
