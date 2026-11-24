#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Retry only a confirmed Maven Central HTTP 429 artifact/plugin resolution failure.
# A compile/test/checkstyle failure is NEVER retried as though it were transient.
set -euo pipefail
if (( $# == 0 )); then
  echo 'usage: retry-maven-central-429.sh <maven-command> [args...]' >&2
  exit 64
fi
log=$(mktemp)
trap 'rm -f "$log"' EXIT
for attempt in 1 2 3; do
  if "$@" >"$log" 2>&1; then
    cat "$log"
    exit 0
  else
    code=$?
    cat "$log"
  fi
  if (( attempt == 3 )) \
      || ! grep -Fq 'https://repo.maven.apache.org/maven2' "$log" \
      || ! grep -Eq 'HTTP Status: 429|status code 429|Too Many Requests' "$log" \
      || ! grep -Eq '\[ERROR\].*(could not be resolved|Could not transfer artifact)' "$log"; then
    exit "$code"
  fi
  echo "::warning:: Maven Central rate-limited artifact resolution (HTTP 429); bounded retry $attempt/2" >&2
  sleep "$((attempt * 10))"
done
