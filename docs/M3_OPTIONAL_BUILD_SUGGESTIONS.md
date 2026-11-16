<!-- SPDX-License-Identifier: Apache-2.0 -->
# Optional suggestions after a Maven build

This adds a completion pass to the original Maven CLI. Maven still executes
the requested goals, reports its failures and determines its exit status. The pass
offers review suggestions after that work; it does not edit files, rerun Maven,
change dependency resolution, select modules or skip goals.

The implementation must be built into the Maven fork before these commands have
the described effect. A helper test or a standalone adviser component JAR is not
a qualified Maven distribution.

## Two explicit switches

Ordinary Maven invocations remain inert. Enable deterministic suggestions with:

```sh
/absolute/path/to/built-maven/bin/mvn -Dm3.smart.suggest=true verify
```

Add `-Doptionllm=true` to permit optional native-model commentary. `optionllm`
alone does not activate the pass. User properties take precedence over system
properties; both switches default to `false`.

The deterministic pass uses the actual completed result and Maven's existing
exception-handler categories. It can suggest reviewing a failed plugin goal,
project model, dependency resolution, selected Java runtime or requested phase.
It does not require any model, network connection or provider artifact.

A successful invocation, or one without completed failure diagnostics, prints
that no diagnostic suggestions are needed. It does not inspect model settings,
hash JARs, read model files or start a provider. The pass does not collect compiler
warnings from an otherwise successful build.

## Optional native ChromeLLM commentary

The optional provider reuses `io.synexia.maven.smart.McpAdviser` from the existing
`tools/m3-smart-builder` artifact. Maven starts that entry point in a selected
Java process, and the existing adviser uses ChromeLLM's MCP stdio owner with
the explicitly selected `cli` or `jni` backend. There is no browser prerequisite,
HTTP fallback, new MCP implementation or model downloader in this pass.

The adviser runs on Java 21 or later. Maven itself retains its Java 17 API
baseline. `m3.smart.llmJavaHome` defaults to the JVM running Maven; select a
Java 21 home explicitly when that JVM is Java 17. There is no automatic Java
discovery, upgrade, download or provisioning.

The following example assumes independently built artifacts and an explicitly
selected local model. Set the shell variables to their actual absolute paths
and actual lowercase SHA-256 digests before invoking it:

```sh
"$M3_MAVEN_HOME/bin/mvn" -Dm3.smart.suggest=true -Doptionllm=true \
  -Dm3.smart.adviserJar="$ADVISER_JAR" \
  -Dm3.smart.adviserJarSha256="$ADVISER_JAR_SHA256" \
  -Dm3.smart.chromeLlmJar="$CHROMELLM_JAR" \
  -Dm3.smart.chromeLlmJarSha256="$CHROMELLM_JAR_SHA256" \
  -Dm3.smart.llmJavaHome="$JAVA21_HOME" \
  -Dm3.smart.llmBackend=jni \
  -Dm3.smart.jniLib="$CHROMELLM_JNI_LIBRARY" \
  -Dm3.smart.modelPath="$MODEL_GGUF" \
  -Dm3.smart.llmTimeoutSeconds=60 verify
```

For `cli`, replace the backend with `cli` and provide `m3.smart.llamaBin`
instead of `m3.smart.jniLib`. The existing Synexia `mvnw` can select this built
Maven distribution through its existing `M3_MAVEN_HOME` support.

| Property | Meaning and admission rule |
| --- | --- |
| `m3.smart.suggest` | Enables the deterministic completion pass; default `false`. |
| `optionllm` | Enables native commentary within the enabled pass; default `false`. |
| `m3.smart.adviserJar` | Absolute path to the built smart-builder JAR containing the adviser entry point. |
| `m3.smart.adviserJarSha256` | Required lowercase 64-hex SHA-256 of that exact JAR. |
| `m3.smart.chromeLlmJar` | Absolute path to the independently built ChromeLLM standalone module JAR. |
| `m3.smart.chromeLlmJarSha256` | Required lowercase 64-hex SHA-256 of that exact JAR. |
| `m3.smart.llmJavaHome` | Java home for the adviser child; defaults to the running JVM's `java.home`. |
| `m3.smart.llmBackend` | Required explicit `cli` or `jni`. |
| `m3.smart.llamaBin` | Absolute native CLI executable path when `cli` is selected. |
| `m3.smart.jniLib` | Absolute JNI library path when `jni` is selected. |
| `m3.smart.modelPath` | Absolute path to the explicitly selected local GGUF. |
| `m3.smart.llmTimeoutSeconds` | Whole adviser exchange deadline, integer 1–120; default 120 seconds. |

All selected file paths, including the selected Java executable, must identify
existing regular files. Leaf symlinks are refused. Both selected JARs are
stream-hashed and limited to 64 MiB each. Missing configuration, a different JAR
digest, an unsuitable Java runtime or a provider failure produces an advisory
unavailable message; it does not replace Maven's original result.

## Diagnostic and process boundaries

The pass takes at most 16 messages already produced by Maven's exception
handler. Each is limited to 1 KiB of UTF-8 without splitting supplementary
Unicode characters. Truncation is marked. Invalid UTF-16 is replaced with the
Unicode replacement character, and non-text terminal controls are replaced
with spaces. The native prompt labels these diagnostics as untrusted input.
No additional logs, source files, repository files or credentials are searched.
Category selection visits at most 256 of the already-produced exception summaries,
including nested project-model failures, and accepts Maven's URL and plain-key forms.
When Maven omits a help reference for a typed model-resolution failure, the pass
uses that existing exception type to retain the repository suggestion.
An existing exception message can itself contain a source path or diagnostic
excerpt; enabled native commentary sends that bounded message to the explicitly
selected local provider.

The adviser request is limited to 64 KiB; its response is limited to 32 KiB of
strict UTF-8. Blank responses, malformed Unicode, NUL and terminal control
characters are refused. Accepted text is prefixed on every rendered line with
`[M3][UNTRUSTED LLM]`. It has no source-mutation or build-success authority.

The deadline includes writing and flushing child stdin, reading stdout and
waiting for exit. Stdout is consumed concurrently with stdin writing. Arbitrary
provider stderr is not echoed. Cancellation preserves the caller's interrupt,
and cleanup attempts descendant and direct-child retirement within a separate
two-second budget. Pipe closing remains with daemon I/O workers so a stalled
pipe cannot add an unbounded wait to the invoking Maven thread.

The pass runs after the normal CLI invocation completes. It does not alter
`M3ProjectWatch`, process each intermediate watch iteration or replace embedded
Maven Core execution. Existing `--fail-never` behavior remains authoritative.
Ordinary reporting and provider exceptions are contained; a failed suggestion
sink does not replace the build exit code.

## Qualification

`M3BuildSuggestionsTest` exercises the Java 17 API closure and real child-process
pipes: disabled modes, bounded actual diagnostic messages, Unicode, exceptions,
throwing output sinks, deadlines, blocked stdin, concurrent output overflow,
descendant pipe retention and interruption. Its process fixture is deliberately
named as a fixture; it is not a Maven API, MCP implementation or native model.

`M3BuildSuggestionsInvokerTest` exercises the original Maven 4 parser and invoker,
including successful builds, failed phases and `--fail-never`. Run it through
the receiving Maven module, together with the helper suite:

```sh
mvn -B -ntp -pl impl/maven-cli -am \
  -Dtest=M3BuildSuggestionsTest,M3BuildSuggestionsInvokerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Actual receiving-module compilation, invoker execution, adviser plugin packaging,
native CLI/JNI/model execution and platform-specific qualification are separate
gates. Component tests and protocol fixtures do not satisfy those gates. Reusable
source-sealed recipes and qualification custody remain in `hsoliwal/com.synexia`;
the Maven fork receives admitted runtime/test/documentation results.
