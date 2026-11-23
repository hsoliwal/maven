#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Read-only admission: one immutable merged Maven plus one merged Gradle source.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
FLOW="$ROOT/.github/workflows/m3-gradle-merged-parity.yml"
test -s "$FLOW" || { echo 'missing merged cross-fork acceptance workflow' >&2; exit 1; }
require() {
    grep -Fq -- "$1" "$FLOW" || {
        echo "merged cross-fork source/JUnit gate missing: $1" >&2
        exit 1
    }
}
require 'name: M3 Maven to merged Gradle combined JDK17 and JDK21 acceptance'
require "java: ['17', '21']"
require 'ref: a589a99cc1b992cfa11e43db86cca35fcae5ef4c'
require 'ref: a8ab91be59f414e39245762445f1057a8677fba2'
require 'M3-MERGED-CROSSFORK-V1'
require 'verify-gradle-junit-modes.sh'
require 'M3GradleTestKitReceiptMain.java'
require '6fd1f257081b433b98b29eea3b5651bf2449d02b'
require '-Pm3-derby verify'
require 'actual Derby JDBC transaction/restart/recovery assertions'
require 'derby-10.16.1.1.jar'
require 'derbytools-10.16.1.1.jar'
require 'M3_REQUIRE_DERBY_REAL_ENGINE:'
require 'M3_MAVEN_COMPILEDB_JAR='
require 'M3_MAVEN_DERBY_ENGINE_JAR='
require 'M3_PROJECT_DB_JAR='
require 'M3_PRUNED_PROJECT_DB_JAR='
require 'sha256sum -c SHA256SUMS'
require 'M3LlvmCompileDbTaskTest'
require 'M3LlvmCompileDbRealEngineTest'
require 'M3DerbyRealEngineTest'
require 'M3ProjectDbRealEngineTest'
require 'M3ProjectDbGradleNativeRealEngineTest'
require 'M3ProjectDbTaskTest'
require 'M3ProjectDbPrunedSymlinkRealEngineTest'
require 'current-nine'
require '18 combined Gradle TestKit'
require '--rerun-tasks'
require 'actions/upload-artifact@v4'
require 'git -C consumer-gradle diff --exit-code --quiet'
if grep -Eq 'continue-on-error:[[:space:]]*true|ref:[[:space:]]*(master|main)[[:space:]]*$' "$FLOW"; then
    echo 'refuse unqualified success masking or floating source ref' >&2
    exit 1
fi
if grep -Fq -- '|| true' "$FLOW"; then
    echo 'refuse unconditional error masking' >&2
    exit 1
fi
test -s "$ROOT/tools/project-db/scripts/M3GradleTestKitReceiptMain.java"
test -s "$ROOT/tools/project-db/scripts/verify-gradle-testkit-results.sh"
test -s "$ROOT/tools/project-db/scripts/verify-gradle-junit-modes.sh"
printf '%s\n' 'PASS: exact merged Maven/Gradle source/JDK evidence and combined 18-case acceptance preserved'
