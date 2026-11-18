#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# TestKit reports are an acceptance receipt: success with silently skipped
# real-engine cases must never qualify the Maven-to-Gradle bridge.
set -eu
ROOT=${1:?usage: verify-gradle-testkit-results.sh TEST_RESULT_DIRECTORY}

fail() { printf 'M3 Gradle TestKit receipt refused: %s\n' "$1" >&2; exit 1; }

suite() {
    NAME=$1
    COUNT=$2
    FILE="$ROOT/TEST-io.synexia.gradle.quality.$NAME.xml"
    test -s "$FILE" || fail "missing or empty $NAME"
    grep -Fq "<testsuite name=\"io.synexia.gradle.quality.$NAME\" tests=\"$COUNT\" skipped=\"0\" failures=\"0\" errors=\"0\"" "$FILE" \
        || fail "suite $NAME did not execute $COUNT cases without skips/errors/failures"
    FOUND=$(grep -c '<testcase name=' "$FILE" || true)
    test "$FOUND" -eq "$COUNT" || fail "suite $NAME recorded $FOUND test cases instead of $COUNT"
    if grep -Eq '<(skipped|failure|error)([[:space:]>]|/>)' "$FILE"; then
        fail "suite $NAME contains unexpected unsuccessful test elements"
    fi
}

case_in() {
    NAME=$1
    CASE=$2
    FILE="$ROOT/TEST-io.synexia.gradle.quality.$NAME.xml"
    grep -Fq "<testcase name=\"$CASE\" classname=\"io.synexia.gradle.quality.$NAME\"" "$FILE" \
        || fail "missing required $NAME.$CASE"
}

suite M3ProjectDbRealEngineTest 1
suite M3ProjectDbGradleNativeRealEngineTest 1
suite M3ProjectDbTaskTest 4
case_in M3ProjectDbRealEngineTest 'ingestsActualBuildLogAndRetrievesOriginalProvenance()'
case_in M3ProjectDbGradleNativeRealEngineTest 'nativeGradleTestFailureIsIndexedWithSourceProvenanceWithoutPom()'
case_in M3ProjectDbTaskTest 'refusesTamperedJarBeforeStartingExternalEngine()'
case_in M3ProjectDbTaskTest 'ingestsAndQueriesOnlyWhenExplicitlyExecuted()'
case_in M3ProjectDbTaskTest 'queryRequiresPriorIngestWithoutCreatingAnEmptyDatabase()'
case_in M3ProjectDbTaskTest 'refusesOutOfProjectStoreAndEmptyQuery()'

FILES=$(find "$ROOT" -maxdepth 1 -type f -name 'TEST-io.synexia.gradle.quality.M3ProjectDb*.xml' | wc -l)
test "$FILES" -eq 3 || fail "expected exactly three project-DB suite receipts, observed $FILES"
printf '%s\n' 'PASS: original Gradle TestKit reported six required project-DB cases; no skipped, failed, or errored cases'
