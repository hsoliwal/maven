#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Exact-source/JDK/fail-closed contract for cross-fork Gradle draft acceptance.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
FLOW="$ROOT/.github/workflows/m3-gradle-draft-parity.yml"
test -s "$FLOW" || { echo 'missing Gradle draft acceptance workflow' >&2; exit 1; }
require() { grep -Fq -- "$1" "$FLOW" || { echo "missing Gradle draft qualification invariant: $1" >&2; exit 1; }; }
require 'ref: dc9c0fcdffe89c2b0388c13612ffc197efb37cbb'
require 'ref: a5b5b0a1b297f54d7782ae306e452aff59354220'
require 'ref: 62783f252977c7dfc7fcd4a568dc4f7db2fdc817'
require 'ref: 3510a6c277efe7b9b1c3a365325d42728959dee6'
require "java: ['17', '21']"
require "slice: ['llvm-compdb', 'derby-jdbc', 'project-db-nine']"
require 'm3-source-owners-jdk-'
require 'M3-GRADLE-DRAFT-SOURCES-V1'
require 'sha256sum -c SHA256SUMS'
require 'test -s qualified/polyglot.jar'
require '-Pm3-derby verify'
require 'M3_REQUIRE_DERBY_REAL_ENGINE:'
require 'M3_MAVEN_DERBY_ENGINE_JAR'
require 'M3_MAVEN_COMPILEDB_JAR'
require 'M3_PROJECT_DB_JAR'
require 'M3_PRUNED_PROJECT_DB_JAR'
require 'M3LlvmCompileDbTaskTest'
require 'M3LlvmCompileDbRealEngineTest'
require 'M3DerbyRealEngineTest'
require 'M3ProjectDbTaskTest'
require 'M3ProjectDbPrunedSymlinkRealEngineTest'
require '--rerun-tasks'
require 'current-nine'
require 'zero skips'
require 'actions/upload-artifact@v4'
require 'verify-gradle-testkit-results.sh'
require 'M3GradleTestKitReceiptMain.java'
require 'test "$(git -C consumer-gradle rev-parse HEAD)" = "$target"'
test -s "$ROOT/tools/project-db/scripts/M3GradleTestKitReceiptMain.java"
test -s "$ROOT/tools/project-db/scripts/verify-gradle-testkit-results.sh"
test -s "$ROOT/tools/project-db/scripts/verify-gradle-junit-modes.sh"
# No omitted "continue-on-error" success masks or implicit checkout of moving Gradle master.
if grep -Eq 'continue-on-error:[[:space:]]*true|ref:[[:space:]]*(master|main)[[:space:]]*$' "$FLOW"; then
    echo 'refuse best-effort test success or moving source pin' >&2
    exit 1
fi
printf '%s\n' 'PASS: original Maven and three exact Gradle draft source/JDK/evidence pins guarded'
