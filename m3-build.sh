#!/usr/bin/env sh
# SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
# Keep Maven responsible for reactor selection, lifecycle execution and failures.
set -eu

usage() {
  cat <<'USAGE'
Usage: sh m3-build.sh [quick|test|verify|full|it|dist] [Maven arguments...]
       sh m3-build.sh resume <module> [Maven arguments...]
       sh m3-build.sh canon <packet.tsv> <run-dir>

Default: verify. Maven options alone also use verify.
  quick   Package and compile tests, without running tests.
  test    Run unit tests.
  verify  Run the configured verification lifecycle.
  full    Clean outputs, then verify.
  it      Verify with Maven's existing run-its integration-test profile.
  dist    Verify with -Pplugins: build the inline plugins under plugins/ and the
          distribution with their boot repository.
  resume  Verify starting at a reactor module, using Maven -rf.
  canon   Run the hard canon gate: the packet's stages run in the fixed order
          diff, atom_pattern, lint, compile, test, runtime; any packet, scope,
          atomization, verification or output-contract violation fails.
          See tools/m3-smart-builder/docs/CANON_GATE.md.

Examples:
  sh m3-build.sh
  sh m3-build.sh test -pl impl/maven-core -am
  sh m3-build.sh resume :maven-core
  sh m3-build.sh verify -T 2 -o

M3_MAVEN selects one Maven executable; the default is mvn on PATH.
Relative executable paths and Maven paths are resolved from this script's directory.
For canon, M3_JAVA and M3_JAVAC select the Java 21 launcher and compiler (default java and
javac on PATH); its paths resolve from this script's directory.
USAGE
}

mode=verify
case "${1-}" in
  help|-h|--help) usage; exit 0 ;;
  quick|test|verify|full|it|dist|resume|canon) mode=$1; shift ;;
  ''|-*) ;;
  *) usage >&2; exit 2 ;;
esac

case "$mode" in
  quick) set -- package -DskipTests "$@" ;;
  test) set -- test "$@" ;;
  verify) set -- verify "$@" ;;
  full) set -- clean verify "$@" ;;
  it) set -- verify -Prun-its "$@" ;;
  dist) set -- verify -Pplugins "$@" ;;
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
  canon)
    [ $# -eq 2 ] || { echo 'canon requires <packet.tsv> <run-dir>.' >&2; exit 2; }
    ;;
esac

build_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$build_root"
if [ "$mode" = canon ]; then
  java_executable=${M3_JAVA:-java}
  javac_executable=${M3_JAVAC:-javac}
  for tool in "$java_executable" "$javac_executable"; do
    command -v "$tool" >/dev/null 2>&1 || {
      echo "Java tool not found: $tool. Install Java 21 or set M3_JAVA and M3_JAVAC." >&2
      exit 127
    }
  done
  classes=$(mktemp -d)
  trap 'rm -rf "$classes"' EXIT
  "$javac_executable" -encoding UTF-8 -d "$classes" tools/m3-smart-builder/src/main/java/io/synexia/maven/smart/canon/*.java
  set +e
  "$java_executable" -cp "$classes" io.synexia.maven.smart.canon.CanonGate run "$1" "$2" "$build_root"
  status=$?
  exit "$status"
fi
maven_executable=${M3_MAVEN:-mvn}
command -v "$maven_executable" >/dev/null 2>&1 || {
  echo "Maven executable not found: $maven_executable. Install bootstrap Maven or set M3_MAVEN." >&2
  exit 127
}
exec "$maven_executable" -B -ntp "$@"
