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
require 'ref: c37aebffa4a7040f2bb5d1f783ce18237354fd90'
require 'M3-FUSED-MAVEN274-V1'
require '075444a7b403d890729c5924915a3bf6cae4f5bc'
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
printf '%s\n' 'PASS: immutable Maven #274 paired SHA/Gradle deleted-output TestKit on JDK17/21'


# Opt-in companion guard: the no-argument fused-cache contract remains unchanged.
if [ "$#" -ge 1 ] && [ "$1" = "--identity" ]; then
    require 'gradle-ide-delta-build-identity:'
    require 'timeout-minutes: 30'
    require 'max-parallel: 2'
    require 'ref: 507d1830b61bb6afaaf80268f0f6e815d7c9ee01'
    require '36a98dcba8578d34a418739afcbae53ab5de3e0f'
    require 'M3-GRADLE78-IDENTITY-V1'
    require 'M3_GRADLE78_SOURCE_INPUTS_V1'
    require 'M3IdeDeltaExportTaskTest'
    require 'M3IdeDeltaReviewTaskTest'
    require 'M3QualityPluginTest'
    require 'emitsFullFallbackAndThenNativeModifiedAndDeletedPathChanges()'
    require 'pluginApplicationAndHelpNeverExportOrStartAnyTool()'
    require 'refusesForeignSourceBeforePublishingEvenOnTheFirstFullBuild()'
    require 'nativeBuildTreeIdentitySurvivesConfigurationCacheReplay()'
    require 'reviewRefusesAnotherIncludedBuildEvenWhenItsDigestIsPinned()'
    require 'explicitCompositeIdentityOverridesRemainExact()'
    require 'acceptsExactPinnedBytesButNeverGrantsGradleBuildAuthority()'
    require 'failsOnCorruptBytesAndForeignOwnerEvenWhenHashMatches()'
    require 'registersLazyTasksWithoutDownloadingToolsOrApplyingRewrite()'
    require "assert {p.name for p in directory.glob('TEST-*.xml')} == expected"
    require "('tests', len(wanted)), ('skipped', 0), ('failures', 0), ('errors', 0)"
    require 'Gradle 9.9.1'
    require 'RUNTIME_SHA256SUMS'
    require 'm3-gradle78-identity-jdk-'
    printf '%s\n' 'PASS: additional exact Gradle #78 nine-method identity lane on JDK17/21'
fi
