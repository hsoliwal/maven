<!-- SPDX-License-Identifier: Apache-2.0 -->
# Maven 4 consumes native M3JDK21 build tools

M3JDK21, not Maven, owns the **first-class JDK build-tool implementation**.
This Maven 4 API extension exposes a session-scoped `M3BuildToolsService`
to interested plugins through the original `Session.getService(Class)`
interface. It does not fork a JDK, add a second Java parser or copy
jdeps/SpotBugs/PMD scanners into the Maven reactor.

The original JDK `jdk.jdeps` module supplies the
`java.util.spi.ToolProvider` named `m3-class-deps`. Maven checks that
the selected JDK home identifies the current runtime before using this
in-process provider. A separate forked toolchain is **not** silently
substituted; it reports unavailable until separately qualified IPC support.
The SDK emits the bounded, SHA-256-sealed schema
`M3JDK21_CLASS_DEPS_V1`. Maven validates it and retains up to 256
session-local, sorted `String[]` keys plus immutable receipts, with
O(log n) index lookup. Repeated plugins can reuse admitted receipts only
when a fresh byte-exact SHA-256 confirms the original class file did not
change. Timestamp/length equality alone never authorizes reuse.
Missing SPI provider is an explicit miss; analyzer errors fail closed.

This is one **plugin capability absorption**: machine-readable
bytecode dependency discovery formerly reimplemented by multiple analyzers.
JDK jdeps remains the donor; Maven Dependency Plugin still owns its POM
mapping/reporting goals, and SpotBugs still owns its BCEL/dataflow rules.
M3AST/javac remains the only authoritative source AST; dependency
classfile facts are **not** a substitute for compiler attribution.

Local OpenJDK 21 `javac --release 17 -Xlint:all,-module -Werror`
compiled the two real Maven API/service classes against Maven API-shape
stubs. The local executable
`MAVEN_M3_BUILD_TOOLS_PASS checks=15` exercised session reuse,
separate API/full mode, restored timestamp with changed content,
cross-session isolation, selected JDK mismatch, source-symlink refusal
and absent/wrong provider. A real JUnit5 test is included.

**Not run:** native Maven 4 reactor, real dependency injection,
source-built M3JDK21 tool discovery, Maven plugin migrations, Maven/Gradle
task caching, canonical OpenRewrite SDK Recipe.run and fixed-point
receiving. This is a draft API, not comprehensive merge acceptance.
## Session budget admission (recipe-first, 2026-10-31)

`DefaultM3BuildToolsService.Store` retains at most **256** sorted path/scope keys and immutable receipts. Before this correction, the 257th new request could call the M3JDK21 `m3-class-deps` ToolProvider and populate stdout/stderr buffers before the full-session budget was checked. That failed the original request *after* unnecessary native work.

The canonical Synexia `com.synexia.m3.ProjectDelta20261021` recipe now places the exact existing capacity check **before** the original `ToolProvider.run` invocation for a new key. A previous key whose class bytes changed is removed and may still be renewed, with SHA-256 reauthentication and source identity preserved. The original native provider, sorted arrays, API, class and toolchain limits, checked `IOException`, no-global-cache rule and Maven plugin contracts are unchanged. This does not bypass the budget or enable incremental skipping.

Original JUnit now tests 256 distinct keys, rejection of the 257th with **no extra provider invocation**, unchanged-key reuse, and stale-key refresh at capacity. The canonical source seals and fork-side receipt are in `m3/recipe-custody/maven-buildtools-budget-20261031.json`; see [Synexia recipe PR #14711](https://github.com/hsoliwal/com.synexia/pull/14711). CI extends the existing Maven API/implementation gate to Java **17** (source compatibility) and **21** (M3JDK target) while preserving the upstream Maven lifecycle. No full-VM/JNI parity or speedup claim follows from this change.

**Release gate:** original Maven 4 reactor/DI/JUnit, source sealing, OpenRewrite SDK candidate emission + fixed point, and installed M3JDK21 provider tests must pass on exact heads. The source receipt is not the execution result.
