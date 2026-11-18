<!--
SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# M3 LLVM Tools Provider — CI Qualification Contract

Status: **additive verification only**. Receiving Maven baseline at proposal time:
`77f3b7fbbf847c28d591973bb153b00b5237712e`.

## One ownership graph

- `tools/m3-polyglot-maven-plugin`: immutable task DAG, `M3ToolProvider` v1,
  direct command execution, lifecycle ownership and M3DB/cache exclusion.
- `tools/m3-llvm-tools-provider`: separate ServiceLoader JAR dispatching
  original `opt`, `mlir-opt`, `mlir-translate`, `llvm-as`, `llvm-link`,
  LLD drivers, `llvm-lit` and `FileCheck`; independently verifies
  `M3-LLVM-TOOLCHAIN-V1` identity receipts.
- `tools/code-clustering-clang`: existing compiler AST atomizer, not replaced.
- LLVM source donor: `hsoliwal/llvm-project@591f3d1edf0a9ee718d17a0ee70dd013b3c01908`;
  source commit is **not** an attestation of installed compiler binaries.

## Missing CI obligation

The root reactor includes `tools/m3-llvm-tools-provider`, but consolidated
`.github/workflows/checks-superset.yml` only triggers its polyglot job for
`tools/m3-polyglot-maven-plugin/**` or smart-builder extension paths.
A change that modifies only the optional LLVM tools provider can therefore
escape the dedicated JDK17/JDK21 provider checks.

## Required integration

1. Extend the consolidated workflow's pull-request and master-push path
   filters **and** its internal `polyglot-verify` gate with
   `tools/m3-llvm-tools-provider/**`. The two levels must agree.
2. After the existing Maven polyglot plugin `install`, compile the optional
   provider's dependency-free Java 17 contracts with
   `sh tools/m3-llvm-tools-provider/scripts/verify.sh`.
3. Run `mvn -B -ntp -f tools/m3-llvm-tools-provider/pom.xml verify` using
   the previously installed original plugin JAR. Do not use Maven Core.
4. From the *packaged* plugin and provider JARs, invoke
   `io.synexia.maven.polyglot.PolyglotCli providers`; require the exact
   `M3-POLYGLOT-PROVIDERS-V1` header and one `provider\tllvm-tools\t`
   entry. This proves runtime ServiceLoader visibility, not an LLVM binary build.
5. Keep the manually dispatched `m3-polyglot-verify.yml` equivalent: use
   `install` instead of `verify` on the polyglot prerequisite, then
   execute the optional provider's three checks.
6. Leave existing mandatory C/C++ native gates and packaged Maven consumer
   unchanged; preserve the JDK 17 and 21 job matrix.

## Admission and stop rules

- Do not add a second task runner, AST reader, compiler owner, cache or module
  state database. No POM default lifecycle bindings or public signatures change.
- Do not infer cache-hit eligibility from SHA-256 toolchain evidence, depfiles,
  SDK headers, or a positive native smoke test. LLVM-provider tasks execute.
- This change does **not** build or ship the pinned LLVM fork; CI exercises the
  installed JDK/Maven software and offline/provider JAR contracts.
- A queued, cancelled or absent GitHub workflow is **not** a passing build.
  Review exact commit SHA and GitHub logs before merge. Native toolchain and
  full Maven reactor release gates remain independent.

## Evidence checklist

- [ ] New branch and documentation from the reviewed Maven HEAD
- [ ] Reviewed before/after Git blob identities and exact file diff
- [ ] YAML parsing/structure and path gate comparison
- [ ] Java 17/21 offline and packaged provider contracts
- [ ] Packaged ServiceLoader `llvm-tools` detection
- [ ] Existing native LLVM and consumer tests unaffected
- [ ] GitHub CI check conclusions tied to the PR head
