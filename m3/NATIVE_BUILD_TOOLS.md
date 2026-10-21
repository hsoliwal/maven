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