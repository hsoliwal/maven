#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Deterministic no-Maven, no-network test of the actual GitHub Actions argv gate.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
check="$here/require-maven-jdk25-palantir-args.sh"
positive=(
  '-Dversion.palantirJavaFormat=2.71.0'
  '-Dversion.palantirJavaFormat=2.71.0 -ntp'
  '-ntp -Dversion.palantirJavaFormat=2.71.0'
  '-B -X -Dversion.palantirJavaFormat=2.71.0 -ntp'
  $'\t-Dversion.palantirJavaFormat=2.71.0\t-ntp\t'
)
negative=(
  ''
  '-ntp'
  '-Dversion.palantirJavaFormat'
  '-Dversion.palantirJavaFormat=2.56.0 -ntp'
  '-Dversion.palantirJavaFormat=2.71.0other -ntp'
  '-Dversion.palantirJavaFormat=2.71.0 -Dversion.palantirJavaFormat=2.56.0'
  '-Dversion.palantirJavaFormat=2.56.0 -Dversion.palantirJavaFormat=2.71.0'
  '-Dversion.palantirJavaFormat=2.71.0 -Dspotless.skip=true'
  '-Dspotless.skip -Dversion.palantirJavaFormat=2.71.0'
)
for args in "${positive[@]}"; do
  MAVEN_ARGS="$args" bash "$check" >/dev/null || { echo "FAIL positive: $args" >&2; exit 1; }
done
for args in "${negative[@]}"; do
  if MAVEN_ARGS="$args" bash "$check" >/dev/null 2>&1; then
    echo "FAIL negative unexpectedly accepted: $args" >&2
    exit 1
  fi
done
printf 'PASS: %d positive and %d negative Java25 formatter argument cases\n' "${#positive[@]}" "${#negative[@]}"
