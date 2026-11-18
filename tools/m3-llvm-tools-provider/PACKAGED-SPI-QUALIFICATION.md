<!--
SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# LLVM tools provider: packaged ServiceLoader qualification

Scope: **verification only**, for the existing independent
`.github/workflows/m3-llvm-tools-verify.yml`.
Reviewed Maven base: `77f3b7fbbf847c28d591973bb153b00b5237712e`.
Donor source reference: `hsoliwal/llvm-project@591f3d1edf0a9ee718d17a0ee70dd013b3c01908`
(source identity, not binary provenance).

## Existing checks — preserve without duplication

The repository already has a standalone LLVM/MLIR/LLD tools workflow with
pull-request, master-push and manual triggers, a JDK17/JDK21 matrix, and:

1. `scripts/verify.sh`: strict Java 17 compilation, provider command contracts
   and immutable `M3-LLVM-TOOLCHAIN-V1` identity tests, without installing LLVM.
2. `m3-polyglot-maven-plugin:install`: original SPI dependency owner.
3. `m3-llvm-tools-provider:verify`: separately packaged optional provider,
   its test classpath and contract entrypoints.

Do not copy these jobs into the consolidated polyglot workflow. Keep the
original Maven, Clang AST, toolchain, native scheduler and M3DB owners unchanged.

## Missing executable artifact proof

Tests running against main/test classes do not independently demonstrate that
the **published plugin JAR** discovers the **published optional provider JAR**
through the same `ServiceLoader` path used by portable Maven/Gradle/Eclipse
consumers. Verify the two built files after Maven `verify`, outside Maven's
test classpath:

- `target/m3-polyglot-maven-plugin-0.1.0-SNAPSHOT.jar` in its owning module;
- `target/m3-llvm-tools-provider-0.1.0-SNAPSHOT.jar` in its owning module;
- `META-INF/services/io.synexia.maven.polyglot.M3ToolProvider` and
  `io/synexia/maven/llvm/LlvmNativeToolsProvider.class` inside the optional JAR;
- `java -cp "<plugin-jar>:<provider-jar>"
   io.synexia.maven.polyglot.PolyglotCli providers` exits successfully,
  emits the exact `M3-POLYGLOT-PROVIDERS-V1` header once, and yields exactly one
  four-column `provider<TAB>llvm-tools<TAB>capabilities<TAB>languages` row.

This is a packaging/registration proof, not an installed LLVM optimizer/linker
run and not an authorization to skip native compilation or restore cached data.

## Trigger and compatibility invariants

Retain existing `tools/m3-llvm-tools-provider/**` pull/push path filters.
Additionally schedule the same test when the owner changes the SPI registry
(`M3ToolProviders.java`), the CLI output/entrypoint (`PolyglotCli.java`),
or the SPI service declaration. Make owner source trigger lists symmetric
on PR and master push. Do not broaden this to unrelated repositories or
create another workflow.

No Maven public API, `M3ToolProvider.API_VERSION`, default lifecycle,
tool dispatch, LLVM AST parsing, M3DB/CAS or implicit native cache behavior
changes. Do not vend LLVM sources, binaries or third-party runtime libraries.

## Verification order and status

- Documentation -> minimal workflow patch -> whole-file YAML/Bash syntax
  -> hosted Java 17/21 build -> Maven plugin/provider verify -> packaged
  classpath runtime -> CI logs -> explicit review.
- A green offline source contract does not substitute for an independently
  successful packaged JAR runtime check.
- Merge remains on **HOLD** until GitHub reports the exact PR-head job
  conclusions. Queued, cancelled and missing jobs are not passing evidence.
- Exact source-built LLVM, LLD/MLIR ABI, Windows/macOS and full Maven reactor
  qualification remain independent release gates.
