#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# One source-pinned current Gradle checkout runs nine approved project-DB
# cases (historical six + configuration-cache/binary-ABI cases + symlink).
# A single Gradle run replaces the two earlier checkouts and test invocations.
# One JDK 17/21 Maven owner job builds/verifies artifacts; matching Gradle
# TestKit jobs depend on its success and consume SHA-256-verified JAR bytes.
# Fail on lost source pins, test lanes or artifact receipt handoff.
# The workflow supersedes obsolete runs of the same PR; master and manual
# dispatch runs are unique and cannot cancel one another.
# A project DB source owner also changes whenever the shared M3IndexDB
# engine, TaskRunner or polyglot source contract changes.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/m3-projectdb-gradle-evidence.yml"
test -f "$WORKFLOW" || { echo "missing M3 project DB crossfork workflow" >&2; exit 1; }

expect_count() {
  found=$(grep -F -c -- "$1" "$WORKFLOW" || true)
  if [ "$found" -ne "$2" ]; then
    printf 'Expected %s occurrence(s) of %s, observed %s\n' "$2" "$1" "$found" >&2
    exit 1
  fi
}

expect_count "      - 'tools/project-db/**'" 2
expect_count "      - 'tools/m3-polyglot-maven-plugin/**'" 2
expect_count "      - '.github/workflows/m3-projectdb-gradle-evidence.yml'" 2
expect_count "    branches: [master]" 1
expect_count "  workflow_dispatch:" 1
expect_count "  group: \${{ github.workflow }}-\${{ github.event_name == 'pull_request' && github.event.pull_request.number || format('{0}-{1}', github.ref, github.run_id) }}" 1
expect_count "  cancel-in-progress: \${{ github.event_name == 'pull_request' }}" 1
expect_count "        java: ['17', '21']" 2
expect_count "run: sh tools/project-db/scripts/verify-gradle-junit-modes.sh" 1
expect_count "run: sh tools/project-db/scripts/verify-gradle-sources.sh" 1
expect_count "run: mvn -B -ntp -f tools/m3-polyglot-maven-plugin/pom.xml install" 1
expect_count "run: mvn -B -ntp -f tools/project-db/pom.xml verify" 1
expect_count "  gradle-consumer-real-testkit:" 1
expect_count "    needs: project-db-native-gradle" 1
expect_count "uses: actions/upload-artifact@v4" 2
expect_count "uses: actions/download-artifact@v4" 1
expect_count "name: qualified-maven-jars-jdk-" 2
expect_count "sha256sum -c SHA256SUMS" 2
expect_count "M3-PROJECTDB-JARS-V1" 2
expect_count "wrong Maven source SHA or JDK lane" 1
expect_count "          ref: c918c9b9dfc3b087987165c43b77dff63cc86fb1" 1
expect_count "--tests 'io.synexia.gradle.quality.M3ProjectDbRealEngineTest'" 1
expect_count "--tests 'io.synexia.gradle.quality.M3ProjectDbGradleNativeRealEngineTest'" 1
expect_count "--tests 'io.synexia.gradle.quality.M3ProjectDbTaskTest'" 1
expect_count "          ref: 7c3d37575002b9c54ff8fa9c8648373aaffa1690" 0
expect_count "--tests 'io.synexia.gradle.quality.M3ProjectDbPrunedSymlinkRealEngineTest'" 1
expect_count "          path: consumer-gradle" 1
expect_count "          path: pruned-symlink-gradle" 0
expect_count "test --tests '*M3ProjectDb*'" 0
expect_count "pruned-symlink-gradle/" 0
expect_count "name: projectdb-gradle-pruned-symlink-jdk-" 0
expect_count "M3_PRUNED_PROJECT_DB_JAR:" 1
expect_count "M3_PRUNED_POLYGLOT_JAR:" 1
expect_count "              --rerun-tasks >" 1
expect_count "install -m 0644 tools/project-db/scripts/verify-gradle-testkit-results.sh" 1
expect_count "install -m 0644 tools/project-db/scripts/M3GradleTestKitReceiptMain.java" 1
expect_count "SOURCE_RECEIPT.tsv verify-gradle-testkit-results.sh M3GradleTestKitReceiptMain.java > SHA256SUMS" 1
expect_count "run: sh qualified-maven-jars/verify-gradle-testkit-results.sh consumer-gradle/m3/quality-bridge/build/test-results/test current-nine" 1
expect_count "name: projectdb-gradle-testkit-current-nine-jdk-" 1

echo "PASS: one Maven owner and one current nine-case Gradle TestKit consumer per JDK"
