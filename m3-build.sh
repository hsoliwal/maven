#!/usr/bin/env sh
# SPDX-License-Identifier: Apache-2.0
# Opt-in reactor build shortcut; invoke from any directory.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MODE=${1:-verify}
if [ "$#" -gt 0 ]; then shift; fi
command -v mvn >/dev/null 2>&1 || { echo "Maven must be installed and on PATH" >&2; exit 127; }
cd "$ROOT"
case "$MODE" in
  quick)  exec mvn -B -ntp package -DskipTests "$@" ;;
  test)   exec mvn -B -ntp test "$@" ;;
  verify) exec mvn -B -ntp verify "$@" ;;
  full)   exec mvn -B -ntp clean verify "$@" ;;
  *) echo "Usage: $0 [quick|test|verify|full] [Maven arguments...]" >&2; exit 2 ;;
esac
