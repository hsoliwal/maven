#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
# All-language builds through Maven's own phases, offline: a reactor with a Java module and a module built by
# Python through its m3-build.tsv.
#   bound        -Dm3.polyglot.lifecycle=true: the polyglot module's compile and test targets run in Maven's
#                compile and test phases, the Java module builds as usual, mvn verify is green
#   incremental  with -Dm3.polyglot.incremental=true a second build skips both unchanged tasks; a changed source
#                runs compile and test again
#   off          without the property no polyglot task runs
#   failure      a failing native test fails the Maven build
# Usage: test_polyglot.sh <extension.jar> <java-home> [mvn command]. Needs python on the path and
# m3-polyglot-maven-plugin installed. With the fork's mvn, whose lib/ext has the extension, pass any jar path.
set -uo pipefail
EXT=${1:?extension jar}; JH=${2:?java home}; MVN=${3:-mvn}
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail() { echo "POLYGLOT_LIFECYCLE_TEST FAIL $*"; exit 1; }
PY=""; for c in python3 python; do p=$(command -v "$c" 2>/dev/null) && "$p" -c "" > /dev/null 2>&1 && { PY=$p; break; }; done  # a store alias named python3 does not run
[ -n "$PY" ] || fail "no python"; command -v cygpath > /dev/null 2>&1 && PY=$(cygpath -m "$PY")
printf '<settings/>\n' > "$T/settings.xml"
R="$T/r"; mkdir -p "$R/lib/src/main/java/lib" "$R/native/src" "$R/native/tools"
printf '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>fix</groupId><artifactId>root</artifactId><version>1.0-SNAPSHOT</version><packaging>pom</packaging><modules><module>lib</module><module>native</module></modules><properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>\n' > "$R/pom.xml"
printf '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><parent><groupId>fix</groupId><artifactId>root</artifactId><version>1.0-SNAPSHOT</version></parent><artifactId>lib</artifactId></project>\n' > "$R/lib/pom.xml"
printf 'package lib;\npublic final class Lib { public static int one() { return 1; } }\n' > "$R/lib/src/main/java/lib/Lib.java"
printf '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><parent><groupId>fix</groupId><artifactId>root</artifactId><version>1.0-SNAPSHOT</version></parent><artifactId>native</artifactId><packaging>pom</packaging></project>\n' > "$R/native/pom.xml"
printf 'def greet(name):\n    return "hello " + name\n' > "$R/native/src/app.py"
cat > "$R/native/tools/build.py" <<'PY'
import pathlib, sys
source, out, log = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
compile(source.read_text(), str(source), "exec")
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(source.read_text())
with log.open("a") as f: f.write("compile\n")
PY
cat > "$R/native/tools/check.py" <<'PY'
import pathlib, sys
built, log = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
scope = {}
exec(built.read_text(), scope)
with log.open("a") as f: f.write("test\n")
sys.exit(0 if scope["greet"]("maven") == "hello maven" else 3)
PY
{ printf 'M3-POLYGLOT-TASKS-V1\n'
  printf 'task\tcompile\t%s\t.\narg\tcompile\ttools/build.py\narg\tcompile\tsrc/app.py\narg\tcompile\tbuild/app.py\narg\tcompile\truns.log\n' "$PY"
  printf 'input\tcompile\tsrc\ninput\tcompile\ttools/build.py\noutput\tcompile\tbuild/app.py\n'
  printf 'task\ttest\t%s\t.\narg\ttest\ttools/check.py\narg\ttest\tbuild/app.py\narg\ttest\truns.log\n' "$PY"
  printf 'dep\ttest\tcompile\ninput\ttest\tbuild/app.py\ninput\ttest\ttools/check.py\n'; } > "$R/native/m3-build.tsv"
mvnx() { local log=$1; shift; (cd "$R" && JAVA_HOME="$JH" MAVEN_OPTS="-Dmaven.ext.class.path=$EXT" "$MVN" -o -B -ntp -s "$T/settings.xml" "$@") > "$T/$log" 2>&1; }
runs() { [ -f "$R/native/runs.log" ] && tr -d '\r' < "$R/native/runs.log" | tr '\n' ' ' || true; }

mvnx off.log verify || fail "off: build not green: $(grep ERROR "$T/off.log" | head -3)"
[ -n "$(runs)" ] && fail "off: a polyglot task ran without the property"
mvnx bound.log -Dm3.polyglot.lifecycle=true -Dm3.polyglot.incremental=true verify || fail "bound: build not green: $(grep ERROR "$T/bound.log" | head -3)"
grep -q 'Polyglot lifecycle: native runs m3-build.tsv targets \[compile, test\]' "$T/bound.log" || fail "bound: no binding line"
[ "$(runs)" = "compile test " ] || fail "bound: expected compile then test, got '$(runs)'"
[ -f "$R/lib/target/classes/lib/Lib.class" ] || fail "bound: the Java module did not compile"
grep -q 'polyglot-compile' "$T/bound.log" && grep -q 'polyglot-test' "$T/bound.log" || fail "bound: execution ids"
mvnx again.log -Dm3.polyglot.lifecycle=true -Dm3.polyglot.incremental=true verify || fail "incremental: build not green"
[ "$(runs)" = "compile test " ] || fail "incremental: unchanged tasks ran again: '$(runs)'"
grep -q 'UP-TO-DATE compile' "$T/again.log" && grep -q 'UP-TO-DATE test' "$T/again.log" || fail "incremental: no UP-TO-DATE lines"
printf 'def greet(name):\n    return "hello " + name\n# changed\n' > "$R/native/src/app.py"
mvnx changed.log -Dm3.polyglot.lifecycle=true -Dm3.polyglot.incremental=true verify || fail "changed: build not green"
[ "$(runs)" = "compile test compile test " ] || fail "changed: expected compile and test again, got '$(runs)'"
printf 'def greet(name):\n    return "bye " + name\n' > "$R/native/src/app.py"
mvnx failing.log -Dm3.polyglot.lifecycle=true verify && fail "failure: a failing native test left the build green"
grep -q 'polyglot tool failed: test (exit 3)' "$T/failing.log" || fail "failure: not reported as the native test's exit"
echo "POLYGLOT_LIFECYCLE_TEST PASS"
