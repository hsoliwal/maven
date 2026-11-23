#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Original Maven 274 paired SHA correctness and Gradle source owner: immutable CI guard.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/../../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/m3-gradle-maven274-fused-cache.yml"
test -s "$WORKFLOW" || { echo 'missing source-owned fused Maven 274 workflow' >&2; exit 1; }
require() {
    grep -Fq -- "$1" "$WORKFLOW" || {
        echo "missing fused Maven/Gradle acceptance gate: $1" >&2
        exit 1
    }
}
require "java: ['17', '21']"
require 'ref: 53d7bd782077968e596a54cb7948ea22810734c3'
require 'ref: 8a5daf6fd7ea87230dada80f50c55264e002e4e5'
require 'M3-FUSED-MAVEN274-V1'
require 'b7261f71d82603eae13d7924f8650f10192213d4'
require 'build-cache/module-state integration checks'
require 'M3_REQUIRE_FUSED_REAL_ENGINE:'
require 'M3_FUSED_MAVEN_ENGINE_JAR='
require 'M3FusedStateCacheRealEngineTest'
require 'originalMavenFusedKeysPreservePortableRestoreLocalReuseAndContentInvalidation()'
require 'missingDeclaredInputMustNotAuthorizeStaleCacheRestoration()'
require 'wrongEngineShaAndUnsafeCachePathsRefuseBeforeRunningMaven()'
require 'assert len(cases)==3'
require "'skipped',0"
require 'sha256sum -c SHA256SUMS'
require '--rerun-tasks'
require 'git -C consumer-gradle diff --exit-code --quiet'
require 'actions/upload-artifact@v4'
if grep -Eq 'continue-on-error:[[:space:]]*true|ref:[[:space:]]*(master|main)[[:space:]]*$' "$WORKFLOW"; then
    echo 'refuse moving source pin or masked failed test' >&2
    exit 1
fi
if grep -Fq -- '|| true' "$WORKFLOW"; then
    echo 'refuse unconditional error masking' >&2
    exit 1
fi
printf '%s\n' 'PASS: immutable Maven #274 paired SHA/Gradle #75 TestKit on JDK17/21'
