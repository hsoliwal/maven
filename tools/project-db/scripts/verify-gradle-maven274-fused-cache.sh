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

# Opt-in compiler companion: preserve the original fused and identity contracts.
if [ "$#" -ge 1 ] && [ "$1" = "--provider" ]; then
    provider_job=$(awk '
        /^  gradle-javac-provider-reuse:$/ { active=1 }
        active && /^  [A-Za-z0-9_-]+:$/ && $0 != "  gradle-javac-provider-reuse:" { exit }
        active { print }
    ' "$WORKFLOW")
    provider_require() {
        printf '%s\n' "$provider_job" | grep -Fq -- "$1" || {
            echo "missing compiler provider acceptance gate: $1" >&2
            exit 1
        }
    }
    provider_require 'gradle-javac-provider-reuse:'
    provider_require 'timeout-minutes: 60'
    provider_require 'max-parallel: 2'
    provider_require "java: ['17', '21']"
    provider_require 'ref: 53d7bd782077968e596a54cb7948ea22810734c3'
    provider_require 'ref: 9926e62cc26a1f75c952aaf70af422331c37b888'
    provider_require '93e423cbfbc68d3ba4c4f3873421bd8b7b713366'
    provider_require '8a3ec02822bfe70562f8b4ab2d1d0ebb5cc654fb'
    provider_require 'M3-GRADLE-PROVIDER-V1'
    provider_require 'M3_GRADLE_PROVIDER_SOURCE_INPUTS_V1'
    provider_require 'M3_PROVIDER_REUSE_JAR:'
    provider_require "--tests 'io.synexia.gradle.quality.M3PolyglotProviderReuseRealEngineTest'"
    provider_require 'reusesQualifiedCompilerRestoresOutputsAndInvalidatesSameMetadataSourceChange()'
    provider_require 'invalidatesQualifiedCompilerReuseWhenClasspathBytesChangeWithoutMetadataChanges()'
    provider_require "assert {p.name for p in directory.glob('TEST-*.xml')} == {report.name}"
    provider_require "('tests', 2), ('skipped', 0), ('failures', 0), ('errors', 0)"
    provider_require "assert len(cases) == 2 and {case.get('name') for case in cases} == wanted"
    provider_require "case.get('classname') == class_name and not list(case)"
    provider_require "assert all(child.tag in ('properties', 'testcase', 'system-out', 'system-err') for child in node)"
    provider_require 'MAVEN_SOURCE_INPUTS.json'
    provider_require 'MAVEN_SOURCE_SHA256SUMS'
    provider_require 'M3_MAVEN_PROVIDER_SOURCE_INPUTS_V1'
    provider_require '3652731870e840170acca60acfeea298cc9c85fd'
    provider_require 'PY_MAVEN_SOURCE_CAPTURE'
    provider_require 'PY_MAVEN_SOURCE_CHECK'
    provider_require 'PY_MAVEN_SOURCE_FINAL'
    provider_require "assert actual_paths == {row['path'] for row in rows}"
    provider_require "assert not git('ls-files', '--others', '--exclude-standard', '-z', '--', module_path)"
    provider_require "assert all(path.startswith(module_path + '/target/') for path in ignored), ignored"
    provider_require "assert current == expected, 'original Maven engine module inputs changed'"
    provider_require 'sha256sum -c SHA256SUMS'
    provider_require '--rerun-tasks'
    provider_require 'Gradle 9.9.1'
    provider_require 'qualified-provider/RUNTIME_SHA256SUMS'
    provider_require 'm3-gradle-provider-reuse-jdk-'
    provider_require 'actions/upload-artifact@v4'
    printf '%s\n' 'PASS: additional exact Gradle compiler dependency TestKit lane on JDK17/21'
fi
