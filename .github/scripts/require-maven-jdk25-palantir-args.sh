#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Validate only the exact formatter override token; Maven can append -ntp,
# -B, or other independently configured arguments to MAVEN_ARGS.
set -euo pipefail
expected='-Dversion.palantirJavaFormat=2.71.0'
seen=false
for token in ${MAVEN_ARGS:-}; do
  case "$token" in
    -Dversion.palantirJavaFormat=*)
      if [[ "$token" != "$expected" ]]; then
        echo "M3 JDK25 formatter: conflicting version token: $token" >&2
        exit 1
      fi
      seen=true
      ;;
    -Dspotless.skip|-Dspotless.skip=*)
      echo 'M3 JDK25 formatter: explicit Spotless bypass is forbidden' >&2
      exit 1
      ;;
  esac
done
if [[ "$seen" != true ]]; then
  echo 'M3 JDK25 formatter: missing exact 2.71.0 version override token' >&2
  exit 1
fi
printf '%s\n' 'PASS: exact Java 25 formatter override present without disabling Spotless'
