#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
# Qualify original opt/llvm-as/llvm-link binaries from the same pinned source
# build as Maven's already-proven Clang/llc. Never creates/replaces native tools.
set -euo pipefail

readonly PIN='591f3d1edf0a9ee718d17a0ee70dd013b3c01908'
if [[ $# -ne 4 ]]; then
  echo 'usage: verify-pinned-llvm-ir-tools.sh <source> <build> <evidence> <jdk-17-or-21>' >&2
  exit 64
fi
readonly LANE="$4"
if [[ "$LANE" != 17 && "$LANE" != 21 ]]; then
  echo 'LLVM IR proof requires an explicit Java 17 or 21 lane' >&2
  exit 64
fi
for required in git javac java mvn sha256sum cmp grep mktemp realpath tee; do
  command -v "$required" >/dev/null || {
    printf 'Missing source-built LLVM proof tool: %s\n' "$required" >&2
    exit 69
  }
done
readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly ROOT="$(cd -- "$SCRIPT_DIR/../../.." && pwd -P)"
readonly PLUGIN="$ROOT/tools/m3-polyglot-maven-plugin"
readonly OPTIONAL="$ROOT/tools/m3-llvm-tools-provider"
readonly SOURCE="$(realpath -- "$1")"
readonly BUILD="$(realpath -- "$2")"
readonly EVIDENCE="$(realpath -- "$3")"
readonly RECEIPT="$EVIDENCE/source-receipt.tsv"
readonly SUCCESS="source_built_llvm_ir_tools_native_jdk${LANE}"
if [[ "$BUILD" == "$SOURCE" || "$BUILD" == "$SOURCE/"* ||
      "$EVIDENCE" == "$SOURCE" || "$EVIDENCE" == "$SOURCE/"* ]]; then
  echo 'LLVM IR proof source and output ownership overlap' >&2
  exit 65
fi
if [[ "$(git -C "$SOURCE" rev-parse --verify HEAD)" != "$PIN" ||
      -n "$(git -C "$SOURCE" status --porcelain --untracked-files=normal)" ]]; then
  echo 'LLVM IR proof requires the exact clean source revision' >&2
  exit 66
fi
if [[ ! -f "$RECEIPT" ]] ||
   ! grep -Fxq $'source_built_clang_llc_maven_native\tPASS' "$RECEIPT"; then
  echo 'Missing original source-built Clang/llc successful Maven proof' >&2
  exit 66
fi
if [[ "$LANE" == 17 ]] &&
   ! grep -Fxq $'source_built_clang_llc_maven_native_jdk17\tPASS' "$RECEIPT"; then
  echo 'Missing original Java 17 Clang/llc successful Maven proof' >&2
  exit 66
fi
# Each successful lane must bind to the original source-built bytes and the
# exact prior proof. A same-lane before/after match alone is insufficient.
require_one_line() {
  local file="$1" expected="$2" count
  if [[ ! -f "$file" || -L "$file" ]] ||
     ! count="$(grep -Fxc -- "$expected" "$file")" ||
     [[ "$count" != 1 ]]; then
    printf 'LLVM IR proof missing/ambiguous original identity: %s\n' "$expected" >&2
    exit 66
  fi
}
require_one_line "$RECEIPT" "$(printf 'source_revision\t%s' "$PIN")"
if [[ "$LANE" == 17 ]]; then
  require_one_line "$RECEIPT" $'source_built_llvm_ir_tools_native_jdk21\tPASS'
fi
if grep -Fxq "$(printf '%s\tPASS' "$SUCCESS")" "$RECEIPT"; then
  echo 'LLVM IR proof success receipt already exists; refuse duplicate run' >&2
  exit 65
fi
if [[ ! -d "${RUNNER_TEMP:-}" || ! -w "${RUNNER_TEMP:-}" ]]; then
  echo 'LLVM IR proof requires a writable runner-temporary namespace' >&2
  exit 65
fi
if [[ ! -d "$PLUGIN/target/classes" ||
      ! -f "$PLUGIN/target/classes/io/synexia/maven/polyglot/TaskRunner.class" ]]; then
  echo 'Original Maven TaskRunner classes are unavailable' >&2
  exit 66
fi
readonly SERVICE="$OPTIONAL/src/main/resources/META-INF/services/io.synexia.maven.polyglot.M3ToolProvider"
if [[ ! -f "$SERVICE" ]] ||
   ! grep -Fxq 'io.synexia.maven.llvm.LlvmNativeToolsProvider' "$SERVICE"; then
  echo 'Original optional LLVM tool provider ServiceLoader declaration missing' >&2
  exit 66
fi
java -version 2>&1 | tee "$EVIDENCE/llvm-ir-java${LANE}-version.log" | \
  grep -Eq "version \"${LANE}([.]|\")"
mvn --version | tee "$EVIDENCE/llvm-ir-maven${LANE}-version.log" | \
  grep -Eq "Java version: ${LANE}([.]|,|$)"
for tool in clang clang++ llc opt llvm-as llvm-link; do
  [[ -x "$BUILD/bin/$tool" ]] || {
    printf 'Source-built LLVM IR proof executable missing: %s\n' "$tool" >&2
    exit 67
  }
done
for tool in clang clang++ llc opt llvm-as llvm-link; do
  sha256sum "$BUILD/bin/$tool"
done > "$EVIDENCE/llvm-ir-jdk${LANE}-binaries-before.sha256"
# The original CMake/Ninja producer recorded these selected executable bytes.
# Require each driver name and exact SHA-256 line exactly once, not merely a
# current hash that stays unchanged during one Java process.
readonly SOURCE_BINARIES="$EVIDENCE/binary-receipt.txt"
require_one_line "$SOURCE_BINARIES" "$(printf 'source_revision\t%s' "$PIN")"
for tool in clang clang++ llc opt llvm-as llvm-link; do
  require_one_line "$SOURCE_BINARIES" "$(printf 'tool\t%s' "$tool")"
  require_one_line "$SOURCE_BINARIES" "$(sha256sum "$BUILD/bin/$tool")"
done
if [[ "$LANE" == 17 ]]; then
  previous="$EVIDENCE/llvm-ir-jdk21-binaries-after.sha256"
  if [[ ! -f "$previous" || -L "$previous" ]] ||
     ! cmp -s -- "$previous" "$EVIDENCE/llvm-ir-jdk17-binaries-before.sha256"; then
    echo 'JDK 17 LLVM IR binaries differ from the proven JDK 21 native toolchain' >&2
    exit 66
  fi
fi

# The classes must not contaminate the original Maven module's targets,
# native build, pinned source worktree or retained evidence artifact.
classes="$(mktemp -d "$RUNNER_TEMP/m3-llvm-ir-jdk${LANE}-XXXXXXXX")"
trap 'rm -rf -- "$classes"' EXIT
javac --release 17 -encoding UTF-8 -Xlint:all -Werror \
  -cp "$PLUGIN/target/classes" \
  -sourcepath "$OPTIONAL/src/main/java" \
  -d "$classes" \
  "$OPTIONAL/src/main/java/io/synexia/maven/llvm/LlvmNativeToolsProvider.java" \
  "$OPTIONAL/src/main/java/io/synexia/maven/llvm/LlvmToolchainEvidence.java" \
  "$OPTIONAL/src/test/java/io/synexia/maven/llvm/LlvmNativePipelineIntegrationTestMain.java" \
  2>&1 | tee "$EVIDENCE/llvm-ir-jdk${LANE}-compile.log"
test -f "$classes/io/synexia/maven/llvm/LlvmNativePipelineIntegrationTestMain.class"
(
  cd "$ROOT"
  M3_LLVM_PIPELINE_HOME="$BUILD" java \
    -cp "$classes:$OPTIONAL/src/main/resources:$PLUGIN/target/classes:$PLUGIN/src/main/resources" \
    io.synexia.maven.llvm.LlvmNativePipelineIntegrationTestMain --required
) 2>&1 | tee "$EVIDENCE/llvm-ir-jdk${LANE}-native.log"
grep -Fq 'LLVM opt -> llvm-as -> llvm-link -> opt host-native assertions' \
  "$EVIDENCE/llvm-ir-jdk${LANE}-native.log"
grep -Fq 'PASS: ' "$EVIDENCE/llvm-ir-jdk${LANE}-native.log"
if grep -Fq 'NOT_RUN:' "$EVIDENCE/llvm-ir-jdk${LANE}-native.log"; then
  echo 'Native LLVM IR pipeline did not execute' >&2
  exit 68
fi
for tool in clang clang++ llc opt llvm-as llvm-link; do
  sha256sum "$BUILD/bin/$tool"
done > "$EVIDENCE/llvm-ir-jdk${LANE}-binaries-after.sha256"
cmp "$EVIDENCE/llvm-ir-jdk${LANE}-binaries-before.sha256" \
  "$EVIDENCE/llvm-ir-jdk${LANE}-binaries-after.sha256"
printf '%s\tPASS\n' "$SUCCESS" >> "$RECEIPT"
printf 'PASS: source-built LLVM IR opt/llvm-as/llvm-link native Maven pipeline on JDK %s\n' "$LANE"
