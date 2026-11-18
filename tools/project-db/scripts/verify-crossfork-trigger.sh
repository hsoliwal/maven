#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
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
expect_count "        java: ['17', '21']" 1
expect_count "run: sh tools/project-db/scripts/verify-gradle-sources.sh" 1
expect_count "run: mvn -B -ntp -f tools/m3-polyglot-maven-plugin/pom.xml install" 1
expect_count "run: mvn -B -ntp -f tools/project-db/pom.xml verify" 1

echo "PASS: project DB CI covers both storage owner and evidence consumer on JDK 17/21"
