#!/bin/sh
# Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements; see the NOTICE
# file distributed with this work. The ASF licenses this file to you under the Apache License, Version 2.0
# (http://www.apache.org/licenses/LICENSE-2.0); distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS.
#
# Runs one fixture through Maven twice and prints the evidence lines: detection, each native step with its argument
# list, UP-TO-DATE skips, reports, attached artifacts and the compile database. The second run must skip unchanged
# work. Usage: run-native-it.sh <fixture-dir> <work-dir> [maven arguments, default: package]
set -eu
FIXTURE=$1
WORK=$2
shift 2
[ $# -gt 0 ] || set -- package
rm -rf "$WORK"
mkdir -p "$WORK"
cp -r "$FIXTURE"/. "$WORK"/
cd "$WORK"
mvn -v 2>&1 | grep -E "Apache Maven|Java version|OS name" || true
for run in 1 2; do
  LOG="$WORK.run$run.log"
  if ! mvn -o -B "$@" > "$LOG" 2>&1; then
    tail -60 "$LOG"
    exit 1
  fi
  echo "== run $run: mvn $*"
  grep -E 'Java version|DETECTED|CLASSIFIER|FINGERPRINT|native-(bootstrap|configure|build|test|install): |UP-TO-DATE|^\[INFO\] TASK|REPORT|ATTACHED|COMPILE-COMMANDS|STAGE |Nothing to be done|is up to date|ninja: no work|BUILD|--- maven-native' "$LOG" || true
done
