#!/usr/bin/env sh
# SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
# Keep Maven responsible for reactor selection, lifecycle execution and failures.
set -eu

usage() {
  cat <<'USAGE'
Usage: sh m3-build.sh [quick|test|verify|full|it] [Maven arguments...]
       sh m3-build.sh resume <module> [Maven arguments...]
       sh m3-build.sh ai-check [Chrome AI agent arguments...]

Default: verify. Maven options alone also use verify.
  quick   Package and compile tests, without running tests.
  test    Run unit tests.
  verify  Run the configured verification lifecycle.
  full    Clean outputs, then verify.
  it      Verify with Maven's existing run-its integration-test profile.
  resume  Verify starting at a reactor module, using Maven -rf.
  ai-check Run the installed Java Chrome AI agent explicitly; no Maven lifecycle.

Examples:
  sh m3-build.sh
  sh m3-build.sh test -pl impl/maven-core -am
  sh m3-build.sh resume :maven-core
  sh m3-build.sh verify -T 2 -o

M3_MAVEN selects one Maven executable; the default is mvn on PATH.
Relative executable paths and Maven paths are resolved from this script's directory.
For ai-check, M3_JAVA selects one Java 21 executable (default java on PATH),
and M3_CHROME_AI_AGENT_JAR names the installed agent JAR. Agent arguments and
paths resolve from this script's directory. The agent reads its token from
CHROME_AI_API_TOKEN or the environment variable selected by --token-env.
USAGE
}

mode=verify
case "${1-}" in
  help|-h|--help) usage; exit 0 ;;
  quick|test|verify|full|it|resume|ai-check) mode=$1; shift ;;
  ''|-*) ;;
  *) usage >&2; exit 2 ;;
esac

case "$mode" in
  quick) set -- package -DskipTests "$@" ;;
  test) set -- test "$@" ;;
  verify) set -- verify "$@" ;;
  full) set -- clean verify "$@" ;;
  it) set -- verify -Prun-its "$@" ;;
  resume)
    case "${1-}" in
      ''|-*) echo 'resume requires a module selector, for example :maven-core.' >&2; exit 2 ;;
    esac
    case "$1" in
      *[![:space:]]*) ;;
      *) echo 'resume requires a nonblank module selector.' >&2; exit 2 ;;
    esac
    resume_from=$1
    shift
    set -- verify -rf "$resume_from" "$@"
    ;;
esac

build_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$build_root"
if [ "$mode" = ai-check ]; then
  agent_jar=${M3_CHROME_AI_AGENT_JAR-}
  if [ ! -f "$agent_jar" ] || [ ! -r "$agent_jar" ]; then
    echo 'Set M3_CHROME_AI_AGENT_JAR to a readable installed agent JAR.' >&2
    exit 2
  fi
  java_executable=${M3_JAVA:-java}
  command -v "$java_executable" >/dev/null 2>&1 || {
    echo 'Java executable not found. Install Java 21 or set M3_JAVA to one literal executable.' >&2
    exit 127
  }
  exec "$java_executable" -jar "$agent_jar" "$@"
fi
maven_executable=${M3_MAVEN:-mvn}
command -v "$maven_executable" >/dev/null 2>&1 || {
  echo "Maven executable not found: $maven_executable. Install bootstrap Maven or set M3_MAVEN." >&2
  exit 127
}
exec "$maven_executable" -B -ntp "$@"
