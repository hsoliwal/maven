#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Two independent JDK 17/21 jobs now validate both Maven and the actual
# source-pinned Gradle TestKit consumer; fail on accidental lane loss.
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
expect_count "        java: ['17', '21']" 2
expect_count "run: sh tools/project-db/scripts/verify-gradle-sources.sh" 1
expect_count "run: mvn -B -ntp -f tools/m3-polyglot-maven-plugin/pom.xml install" 2
expect_count "run: mvn -B -ntp -f tools/project-db/pom.xml verify" 2
expect_count "  gradle-consumer-real-testkit:" 1
expect_count "          ref: e8dbf558fcc8a83c580914fbcc4cb10fe4281eb6" 1
expect_count "test --tests '*M3ProjectDb*' --rerun-tasks" 1
expect_count "name: projectdb-gradle-testkit-jdk-" 1

echo "PASS: source-qualified project DB and real Gradle TestKit consumers on JDK 17/21"
