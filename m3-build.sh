#!/usr/bin/env sh
# SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
# Keep Maven responsible for reactor selection, lifecycle execution and failures.
set -eu

usage() {
  cat <<'USAGE'
Usage: sh m3-build.sh [quick|test|verify|full] [Maven arguments...]
       sh m3-build.sh resume <module> [Maven arguments...]

Default: verify. Maven options alone also use verify.
  quick   Package and compile tests, without running tests.
  test    Run unit tests.
  verify  Run the configured verification lifecycle.
  full    Clean outputs, then verify.
  resume  Verify starting at a reactor module, using Maven -rf.

Examples:
  sh m3-build.sh
  sh m3-build.sh test -pl impl/maven-core -am
  sh m3-build.sh resume :maven-core
  sh m3-build.sh verify -T 2 -o

M3_MAVEN selects one Maven executable; the default is mvn on PATH.
Relative executable paths and Maven paths are resolved from this script's directory.
USAGE
}

mode=verify
case "${1-}" in
  help|-h|--help) usage; exit 0 ;;
  quick|test|verify|full|resume) mode=$1; shift ;;
  ''|-*) ;;
  *) usage >&2; exit 2 ;;
esac

case "$mode" in
  quick) set -- package -DskipTests "$@" ;;
  test) set -- test "$@" ;;
  verify) set -- verify "$@" ;;
  full) set -- clean verify "$@" ;;
  resume)
    case "${1-}" in
      ''|-*) echo 'resume requires a module selector, for example :maven-core.' >&2; exit 2 ;;
    esac
    resume_from=$1
    shift
    set -- verify -rf "$resume_from" "$@"
    ;;
esac

build_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$build_root"
maven_executable=${M3_MAVEN:-mvn}
command -v "$maven_executable" >/dev/null 2>&1 || {
  echo "Maven executable not found: $maven_executable. Install bootstrap Maven or set M3_MAVEN." >&2
  exit 127
}
exec "$maven_executable" -B -ntp "$@"
