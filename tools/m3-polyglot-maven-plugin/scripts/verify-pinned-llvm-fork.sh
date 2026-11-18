#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
# Builds the *original* selected LLVM source and exercises the existing Maven
# native provider. This is not an LLVM compiler implementation or a cache gate.
set -euo pipefail

readonly PIN='591f3d1edf0a9ee718d17a0ee70dd013b3c01908'
if [[ $# -ne 3 ]]; then
  printf 'usage: %s <checked-out-llvm-fork> <build-directory> <evidence-directory>\n' "$0" >&2
  exit 64
fi
for tool in git cmake ninja java javac mvn sha256sum grep find realpath tee sed; do
  command -v "$tool" >/dev/null || { printf 'missing build tool: %s\n' "$tool" >&2; exit 69; }
done

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly MAVEN_ROOT="$(cd -- "$SCRIPT_DIR/../../.." && pwd -P)"
readonly SOURCE="$(realpath -- "$1")"
# Reject source/output overlap *before* mkdir, so an invalid caller never
# creates an untracked build directory inside its original LLVM checkout.
readonly BUILD="$(realpath -m -- "$2")"
readonly EVIDENCE="$(realpath -m -- "$3")"
if [[ "$BUILD" == "$SOURCE" || "$BUILD" == "$SOURCE/"* ||
      "$EVIDENCE" == "$SOURCE" || "$EVIDENCE" == "$SOURCE/"* ]]; then
  echo 'Build and evidence outputs must remain outside original LLVM source' >&2
  exit 65
fi
if [[ "$BUILD" == "$EVIDENCE" || "$BUILD" == "$EVIDENCE/"* ||
      "$EVIDENCE" == "$BUILD/"* ]]; then
  echo 'Build and evidence outputs must have disjoint ownership' >&2
  exit 65
fi
readonly ACTUAL="$(git -C "$SOURCE" rev-parse --verify HEAD)"
if [[ "$ACTUAL" != "$PIN" ]]; then
  printf 'LLVM fork source mismatch: expected %s, observed %s\n' "$PIN" "$ACTUAL" >&2
  exit 66
fi
if [[ -n "$(git -C "$SOURCE" status --porcelain --untracked-files=normal)" ]]; then
  echo 'LLVM fork checkout has uncommitted or untracked source changes' >&2
  exit 66
fi
for required in llvm/CMakeLists.txt clang/tools/driver/CMakeLists.txt cmake/Modules/LLVMVersion.cmake third-party libc; do
  test -e "$SOURCE/$required" || { printf 'Missing pinned source closure: %s\n' "$required" >&2; exit 66; }
done
# The source under proof is LLVM 24, but the commit identity is the actual gate.
grep -Eq 'set\(LLVM_VERSION_MAJOR 24\)' "$SOURCE/cmake/Modules/LLVMVersion.cmake" || {
  echo 'Unexpected LLVM version source closure (expected major 24)' >&2
  exit 66
}
# A CMakeCache.txt, stale compiled binary or prior success receipt could make
# a non-fresh build appear source-qualified. Do not reuse occupied directories.
# This runs only after the exact clean pinned source has passed validation and
# before touching either output. Pre-created empty output directories are valid.
for owned in "$BUILD" "$EVIDENCE"; do
  # A directory listing failure is not an empty directory. Keep the find exit
  # status instead of discarding it inside a [[ -n $(...) ]] expression.
  if [[ -e "$owned" || -L "$owned" ]]; then
    if [[ ! -d "$owned" || -L "$owned" ]]; then
      printf 'LLVM proof output must be absent or empty: %s\n' "$owned" >&2
      exit 65
    fi
    if ! entries="$(find "$owned" -mindepth 1 -maxdepth 1 -print -quit 2>&1)"; then
      printf 'LLVM proof cannot inspect output directory: %s\n' "$owned" >&2
      exit 65
    fi
    if [[ -n "$entries" ]]; then
      printf 'LLVM proof output must be absent or empty: %s\n' "$owned" >&2
      exit 65
    fi
  fi
done
# Publish no build/evidence directories until the source revision, clean state,
# source closure, source version and fresh-output checks have all passed admission.
mkdir -p -- "$BUILD" "$EVIDENCE"
{
  printf 'key\tvalue\n'
  printf 'source_repository\thsoliwal/llvm-project\n'
  printf 'source_revision\t%s\n' "$PIN"
  printf 'source_validation\tEXACT_COMMIT_CLEAN_WORKTREE\n'
  printf 'native_build\tMUST_COMPILE_ORIGINAL_CLANG_AND_LLC\n'
  printf 'fortran_source_build\tNOT_RUN_THIS_GATE\n'
} > "$EVIDENCE/source-receipt.tsv"
(
  cd "$SOURCE"
  sha256sum llvm/CMakeLists.txt clang/tools/driver/CMakeLists.txt cmake/Modules/LLVMVersion.cmake
) > "$EVIDENCE/source-file-sha256.txt"

# Restrict target backend and memory pressure; no original compiler flags or
# Maven task vectors are rewritten. GCC/host SDK remains an external input.
cmake -S "$SOURCE/llvm" -B "$BUILD" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DLLVM_ENABLE_PROJECTS=clang \
  -DLLVM_TARGETS_TO_BUILD=X86 \
  -DLLVM_INCLUDE_TESTS=OFF \
  -DLLVM_INCLUDE_EXAMPLES=OFF \
  -DLLVM_INCLUDE_BENCHMARKS=OFF \
  -DLLVM_PARALLEL_COMPILE_JOBS=2 \
  -DLLVM_PARALLEL_LINK_JOBS=1 \
  2>&1 | tee "$EVIDENCE/configure.log"
cmake --build "$BUILD" --target clang clang++ llc --parallel 2 \
  2>&1 | tee "$EVIDENCE/native-build.log"
for tool in clang clang++ llc; do
  test -x "$BUILD/bin/$tool" || { printf 'Expected source-built tool missing: %s\n' "$tool" >&2; exit 67; }
done
{
  printf 'source_revision\t%s\n' "$ACTUAL"
  for tool in clang clang++ llc; do
    printf 'tool\t%s\n' "$tool"
    sha256sum "$BUILD/bin/$tool"
    "$BUILD/bin/$tool" --version | sed -n '1p'
  done
} > "$EVIDENCE/binary-receipt.txt"

# No M3-LLVM_HOME or native test selector on Maven compilation. The pinned
# tools are opt-in for only the actual original TaskRunner regression below.
mvn -B -ntp -f "$MAVEN_ROOT/tools/m3-polyglot-maven-plugin/pom.xml" test-compile \
  2>&1 | tee "$EVIDENCE/maven-test-compile.log"
readonly CLASSES="$MAVEN_ROOT/tools/m3-polyglot-maven-plugin/target/classes"
readonly TEST_CLASSES="$MAVEN_ROOT/tools/m3-polyglot-maven-plugin/target/test-classes"
test -f "$TEST_CLASSES/io/synexia/maven/polyglot/LlvmCompilerIntegrationTestMain.class"
(
  cd "$MAVEN_ROOT"
  M3_POLYGLOT_LLVM_TEST_HOME="$BUILD" java -cp "$TEST_CLASSES:$CLASSES" \
    io.synexia.maven.polyglot.LlvmCompilerIntegrationTestMain --required
) 2>&1 | tee "$EVIDENCE/maven-native-run.log"
for requirement in \
  'PASS: LLVM C17/C++20 compile/link/run' \
  'PASS: LLVM IR llc object, clang link and native run' \
  'PASS: Objective-C and both Objective-C++ spellings compile' \
  'LLVM native integration assertions'; do
  grep -Fq "$requirement" "$EVIDENCE/maven-native-run.log" || {
    printf 'Missing source-built native execution proof: %s\n' "$requirement" >&2
    exit 68
  }
done
printf 'source_built_clang_llc_maven_native\tPASS\n' >> "$EVIDENCE/source-receipt.tsv"
echo 'PASS: pinned-fork clang/clang++/llc built from exact sources and executed through original Maven TaskRunner'
