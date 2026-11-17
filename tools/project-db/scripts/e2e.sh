#!/bin/sh
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# End-to-end proof of the project database on a real multi-module Maven build (src/test/projects/e2e):
#   1. installs the polyglot, code-clustering and project-db plugins of this checkout;
#   2. build 1 (green): polyglot task state (embedded backend), Surefire with a rerun flaky test, dependency tree,
#      code-clustering tables; then project-db:ingest with the console log;
#   3. build 2 (red): Calc.add broken, the build fails; project-db:ingest again (incremental);
#   4. asks the five questions with project-db:query and checks every answer and its provenance;
#   5. when git is on PATH, checks that no .m3 or project-db file of the build would be committed.
# Environment: JAVA_HOME/bin and mvn on PATH. Optional: E2E_WORK (work folder), E2E_LOCAL_REPO (local
# repository), E2E_SETTINGS (settings.xml, e.g. a mirror for offline use).
set -eu
MODULE=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
REPO=$(CDPATH= cd -- "$MODULE/../.." && pwd)
WORK=${E2E_WORK:-$MODULE/target/e2e}
LOCAL_REPO=${E2E_LOCAL_REPO:-$WORK/repo}
MVN="mvn -B -ntp -Dmaven.repo.local=$LOCAL_REPO"
if [ -n "${E2E_SETTINGS:-}" ]; then MVN="$MVN -s $E2E_SETTINGS"; fi
PDB=org.apache.maven.plugins:maven-project-db-plugin:0.1.0-SNAPSHOT
JAVA_VERSION=$(java -version 2>&1 | head -1)
echo "E2E java: $JAVA_VERSION"

rm -rf "$WORK/project" "$WORK"/build*.log "$WORK"/ingest*.log "$WORK"/answer-*
mkdir -p "$WORK"
for plugin in tools/m3-polyglot-maven-plugin tools/code-clustering tools/project-db; do
  $MVN -q -f "$REPO/$plugin/pom.xml" install -Dexec.skip=true
done
cp -R "$MODULE/src/test/projects/e2e" "$WORK/project"
cd "$WORK/project"

fail() { echo "E2E FAIL: $*"; exit 1; }
ingest() {
  $MVN "$PDB:ingest" "-Dproject-db.buildLog=../$1.log" > "../ingest-$1.log" 2>&1 || { tail -40 "../ingest-$1.log"; fail "ingest $1"; }
  grep "project-db: build" "../ingest-$1.log" | sed 's/^\[INFO\] //'
}

$MVN verify > ../build1.log 2>&1 || { tail -60 ../build1.log; fail "build 1 must pass"; }
grep -q "Flakes: 1" ../build1.log || fail "build 1 must report one flaky test"
ingest build1 | tee ../ingest1.txt
grep -q " green, " ../ingest1.txt || fail "build 1 must be recorded green"

sed -i 's/return a + b;/return a - b;/' core/src/main/java/org/example/projectdb/Calc.java
if $MVN verify > ../build2.log 2>&1; then fail "build 2 must fail"; fi
ingest build2 | tee ../ingest2.txt
grep -q " red, " ../ingest2.txt || fail "build 2 must be recorded red"
grep -q " unchanged)" ../ingest2.txt || fail "ingest must report unchanged sources"
UNCHANGED=$(sed 's/.*parsed, \([0-9]*\) unchanged.*/\1/' ../ingest2.txt)
[ "$UNCHANGED" -gt 0 ] || fail "build 2 must reuse unchanged sources by content hash"

ask() {
  n=$1
  $MVN "$PDB:query" "-Dproject-db.question=$2" "-Dproject-db.output=../answer-$n.json" > "../answer-$n.log" 2>&1 \
    || { tail -40 "../answer-$n.log"; fail "query $n"; }
  sed -n 's/^\[INFO\] \(Q: .*\)$/\1/p; s/^\[INFO\] \([0-9][0-9]*\. .*\)$/\1/p' "../answer-$n.log"
  ranks=$(grep -o '"rank":' "../answer-$n.json" | wc -l)
  proven=$(grep -oE '"provenance":\{"file":"[^"]+","line":[1-9][0-9]*,"build":"b[0-9]{4}-[0-9a-f]{8}"\}' \
    "../answer-$n.json" | wc -l)
  [ "$ranks" -gt 0 ] || fail "question $n has no answer"
  [ "$ranks" -eq "$proven" ] || fail "question $n: $ranks answers but $proven with file, line and build id"
  shift 2
  for expected in "$@"; do grep -q -- "$expected" "../answer-$n.json" || fail "answer $n lacks $expected"; done
}

ask 1 "why did the build fail" '"kind":"test"' 'CalcTest#adds' \
  '"file":"core/src/test/java/org/example/projectdb/CalcTest.java"'
ask 2 "which code is duplicated" '"kind":"duplicate"' 'copyOf' 'app/src/main/java/org/example/projectdb/'
ask 3 "what changed since the last green build" '"kind":"change"' 'Calc.java#Calc.add'
ask 4 "which dependency brings hamcrest-core" '"kind":"dependency"' 'junit:junit:jar:4.13.2'
ask 5 "which tests are flaky" '"kind":"flaky"' 'FlakyTest#sometimes'

# Nothing the polyglot plugin or the project database wrote is committable, though this project has no .gitignore.
if command -v git >/dev/null 2>&1; then
  git init -q . && git add -A --dry-run > ../git-add.txt
  if grep -E '\.m3/|project-db/' ../git-add.txt; then fail "build state would be committed"; fi
  rm -rf .git
  echo "E2E: .m3 and project-db directories ignore themselves ($(wc -l < ../git-add.txt) other files addable)"
fi
echo "E2E PASS: 2 builds ingested, 5 questions answered with provenance ($JAVA_VERSION)"
