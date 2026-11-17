#!/usr/bin/env bash
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
# Build events for agents in real offline Maven builds of a three-module reactor:
#   a  fails to compile (cannot find symbol)    b  has a failing JUnit 4 test    c  compiles with a removal warning
#   off        without maven.agent nothing is written and nothing is printed
#   quiet      -q -Dmaven.agent=true -fae: summary on stdout, events in seq order with file/line/column, the test
#              event with its source line, failure.json/md with reproduce commands, byte-identical on a rerun
#   log        -l build.log -Dmaven.events.log=build.log: the warning of c reaches the events from the log
#   fail-fast  without -fae b and c are skipped and listed as skipped
#   pwsh       (Windows) the digest's reproduce command, run as is in Windows PowerShell, reruns the failed test
#   killed     a build killed while a test runs leaves none of the previous build's agent files behind
#   cli        a goal run from the command line (default-cli) is reproduced by that goal and its properties
#   pom        a dependency without a version: kind model, the POM problem with file, line and column
#   green      fixed sources, clean verify: BUILD SUCCESS summary, failure files removed, no locked file on clean
# The root directory's name holds a space and a non-ASCII letter, so the -l log of Maven on Java 17 (platform
# charset) must be read in that charset for its paths to become root-relative.
# Usage: test_events.sh <extension.jar> <java-home>... (each JDK runs every case). Needs mvn and, in the local
# repository, compiler 3.13.0, surefire 3.2.5 with JUnit 4.13.2, resources 3.3.1, jar 3.4.1, clean 3.2.0.
# WORK=<dir> keeps the fixture and the outputs there.
set -uo pipefail
EXT=${1:?extension jar}; shift
[ $# -gt 0 ] || { echo "usage: test_events.sh <extension.jar> <java-home>..."; exit 2; }
if [ -n "${WORK:-}" ]; then T=$WORK; rm -rf "$T"; mkdir -p "$T"; else T=$(mktemp -d); trap 'rm -rf "$T"' EXIT; fi
fail() { echo "AGENT_EVENTS_TEST FAIL $*"; exit 1; }
printf '<settings/>\n' > "$T/settings.xml"

pom() { # pom <dir> <artifactId> <extra xml>
    printf '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><parent><groupId>fix</groupId><artifactId>root</artifactId><version>1.0-SNAPSHOT</version></parent><artifactId>%s</artifactId>%s</project>\n' "$2" "$3" > "$1/pom.xml"
}
fixture() { # fixture <dir> broken|fixed
    local R=$1 plugins
    mkdir -p "$R/a/src/main/java/fix" "$R/b/src/main/java/fix" "$R/b/src/test/java/fix" "$R/c/src/main/java/fix"
    plugins='<plugin><artifactId>maven-clean-plugin</artifactId><version>3.2.0</version></plugin><plugin><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin><plugin><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version><configuration><showWarnings>true</showWarnings></configuration></plugin><plugin><artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version></plugin><plugin><artifactId>maven-jar-plugin</artifactId><version>3.4.1</version></plugin>'
    printf '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>fix</groupId><artifactId>root</artifactId><version>1.0-SNAPSHOT</version><packaging>pom</packaging><name>fixture</name><description>Three modules: a library, a calculator with tests, a client.</description><modules><module>a</module><module>b</module><module>c</module></modules><properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties><build><pluginManagement><plugins>%s</plugins></pluginManagement></build></project>\n' "$plugins" > "$R/pom.xml"
    pom "$R/a" a ''
    pom "$R/b" b '<dependencies><dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope></dependency></dependencies>'
    pom "$R/c" c ''
    local value=missing extra=' + 1'
    [ "$2" = fixed ] && { value=1; extra=''; }
    printf 'package fix;\n\npublic class Broken {\n    public int value() {\n        return %s;\n    }\n}\n' "$value" > "$R/a/src/main/java/fix/Broken.java"
    printf 'package fix;\n\npublic final class Calc {\n    private Calc() {}\n\n    public static int add(int a, int b) {\n        return a + b%s;\n    }\n}\n' "$extra" > "$R/b/src/main/java/fix/Calc.java"
    printf 'package fix;\n\nimport static org.junit.Assert.assertEquals;\n\nimport org.junit.Test;\n\npublic class BTest {\n    @Test\n    public void adds() {\n        assertEquals(3, Calc.add(1, 2));\n    }\n\n    @Test\n    public void zero() {\n        assertEquals(0, Calc.add(0, 0) - Calc.add(0, 0));\n    }\n}\n' > "$R/b/src/test/java/fix/BTest.java"
    printf 'package fix;\n\npublic class C {\n    public Integer boxed() {\n        return new Integer(5);\n    }\n}\n' > "$R/c/src/main/java/fix/C.java"
}
mvnx() { # mvnx <java home> <dir> <out> <args...>; exit status kept in $STATUS
    local jh=$1 dir=$2 out=$3; shift 3
    (cd "$dir" && JAVA_HOME="$jh" MAVEN_OPTS="-Dmaven.ext.class.path=$EXT" mvn -o -B -ntp -s "$T/settings.xml" "$@") > "$out" 2>&1
    STATUS=$?
}
slowtest() { # slowtest <root>: run in Maven's JVM (forkCount=0, working directory the root) while b/slow.flag exists, it writes the JVM pid to b/maven.pid and sleeps
    printf 'package fix;\n\nimport java.nio.file.Files;\nimport java.nio.file.Path;\nimport org.junit.Test;\n\npublic class SlowTest {\n    @Test\n    public void waits() throws Exception {\n        if (Files.exists(Path.of("b", "slow.flag"))) {\n            Files.writeString(Path.of("b", "maven.pid"), Long.toString(ProcessHandle.current().pid()));\n            Thread.sleep(120_000L);\n        }\n    }\n}\n' > "$1/b/src/test/java/fix/SlowTest.java"
}
killed() { # killed <java home> <root> <out>: starts a build, kills Maven's JVM while SlowTest sleeps
    local jh=$1 dir=$2 out=$3 n
    touch "$dir/b/slow.flag"; rm -f "$dir/b/maven.pid"
    mvnx "$jh" "$dir" "$out" -q -Dmaven.agent=true -DforkCount=0 -fae verify &
    for n in $(seq 1 120); do [ -s "$dir/b/maven.pid" ] && break; sleep 1; done
    [ -s "$dir/b/maven.pid" ] || fail "killed: the slow test never started: $(tail -5 "$out")"
    if command -v taskkill > /dev/null; then taskkill //F //T //PID "$(cat "$dir/b/maven.pid")" > /dev/null
    else kill -9 "$(cat "$dir/b/maven.pid")"; fi
    wait
    rm -f "$dir/b/slow.flag" "$dir/b/maven.pid"
}
pwsh_reproduce() { # pwsh_reproduce <java home> <root> <command> <out>: runs the command in Windows PowerShell
    local ps1="$T/reproduce.ps1"
    printf '\xef\xbb\xbfSet-Location -LiteralPath '"'"'%s'"'"'\r\n$env:JAVA_HOME = '"'"'%s'"'"'\r\n$env:MAVEN_OPTS = '"'"'-Dmaven.ext.class.path=%s'"'"'\r\n%s -o -B -ntp -s '"'"'%s'"'"'\r\nexit $LASTEXITCODE\r\n' \
        "$(cygpath -w "$2")" "$(cygpath -w "$1")" "$(cygpath -w "$EXT")" "$3" "$(cygpath -w "$T/settings.xml")" > "$ps1"
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ps1")" > "$4" 2>&1
    STATUS=$?
}
has() { grep -q -- "$2" "$1" || fail "$3: no '$2' in $1"; }

for JH in "$@"; do
    N=$(basename "$JH"); R="$T/$N/fx é dir"; O="$T/$N/out"; mkdir -p "$O"; fixture "$R" broken
    A="$R/target/agent"

    mvnx "$JH" "$R" "$O/off.txt" -q verify -fae
    [ "$STATUS" = 1 ] || fail "off: exit $STATUS"
    [ -e "$A" ] && fail "off: $A written without maven.agent"
    grep -q '^\[agent\]' "$O/off.txt" && fail "off: summary printed without maven.agent"

    mvnx "$JH" "$R" "$O/quiet.txt" -q -Dstyle.color=never -Dmaven.agent=true -fae verify
    [ "$STATUS" = 1 ] || fail "quiet: exit $STATUS"
    has "$O/quiet.txt" '^\[agent\] BUILD FAILURE' quiet
    has "$O/quiet.txt" '^\[agent\]   ERROR a/src/main/java/fix/Broken.java:5:16 cannot find symbol; symbol: variable missing' quiet
    has "$O/quiet.txt" '^\[agent\]   TEST fix.BTest#adds failed: java.lang.AssertionError: expected:<3> but was:<4> at b/src/test/java/fix/BTest.java:10' quiet
    has "$O/quiet.txt" '^\[agent\]   reproduce: mvn -pl b -am "-Dtest=fix.BTest#adds" "-Dsurefire.failIfNoSpecifiedTests=false" test' quiet
    E="$A/build-events.jsonl"
    [ -s "$E" ] && [ -s "$A/failure.json" ] && [ -s "$A/failure.md" ] && [ -s "$A/summary.txt" ] || fail "quiet: agent files missing"
    awk -F'"seq":' 'NR > 0 { split($2, s, ","); if (s[1] != NR) { print "seq " s[1] " at line " NR; exit 1 } }' "$E" || fail "quiet: seq order"
    head -1 "$E" | grep -q '"type":"session-start"' || fail "quiet: first event"
    tail -1 "$E" | grep -q '"type":"session-end","outcome":"failure"' || fail "quiet: last event"
    has "$E" '"type":"diagnostic","project":"fix:a","source":"compiler","severity":"ERROR","file":"a/src/main/java/fix/Broken.java","line":5,"column":16' quiet
    has "$E" '"type":"test","project":"fix:b","class":"fix.BTest","method":"adds","outcome":"failed"' quiet
    has "$E" '"location":{"file":"b/src/test/java/fix/BTest.java","line":10}' quiet
    has "$E" '"type":"test-suite","project":"fix:b","class":"fix.BTest","tests":2,"failures":1' quiet
    has "$E" '"type":"project-end","index":2,"project":"fix:a","path":"a","outcome":"failure"' quiet
    has "$A/failure.json" '"reproduce":"mvn -pl a -am compile"' quiet
    has "$A/failure.json" '"context":{"startLine":3,"markLine":5,"lines":\["public class Broken {","    public int value() {","        return missing;","    }","}"\]}' quiet
    grep -q 'durationMs' "$A/failure.json" && fail "quiet: duration in failure.json"
    cp "$A/failure.json" "$O/failure.first.json"; cp "$A/failure.md" "$O/failure.first.md"
    mvnx "$JH" "$R" "$O/quiet2.txt" -q -Dstyle.color=never -Dmaven.agent=true -fae verify
    cmp -s "$A/failure.json" "$O/failure.first.json" && cmp -s "$A/failure.md" "$O/failure.first.md" \
        || fail "quiet: digest differs on a rerun"
    cp -r "$A" "$O/agent-quiet"

    mvnx "$JH" "$R" "$O/log-console.txt" -l "$O/build.log" -Dstyle.color=never -Dmaven.agent=true \
        "-Dmaven.events.log=$O/build.log" -fae clean verify
    [ "$STATUS" = 1 ] || fail "log: exit $STATUS"
    has "$E" '"type":"diagnostic","project":"fix:c","source":"compiler","severity":"WARNING","file":"c/src/main/java/fix/C.java","line":5,"column":16,"rule":"removal"' log
    has "$O/build.log" '^\[agent\] BUILD FAILURE' log
    has "$O/build.log" '^\[agent\] BUILD FAILURE.* diagnostics 1 error(s)' log
    grep -q 'fx ' "$E" "$A/summary.txt" "$A/failure.json" && fail "log: the absolute root (not decoded or not removed) in the agent files"
    cp -r "$A" "$O/agent-log"

    mvnx "$JH" "$R" "$O/failfast.txt" -q -Dmaven.agent=true verify
    [ "$STATUS" = 1 ] || fail "fail-fast: exit $STATUS"
    has "$A/failure.json" '"skippedProjects":\["fix:b","fix:c"\]' fail-fast
    has "$O/failfast.txt" '^\[agent\] SKIPPED fix:c' fail-fast

    if command -v powershell.exe > /dev/null; then
        mvnx "$JH" "$R" "$O/quiet3.txt" -q -Dmaven.agent=true -fae verify
        RB=$(sed -n 's/.*"path":"b","kind":"test".*"reproduce":"\(mvn -pl b [^}]*\)"}.*/\1/p' "$A/failure.json" | sed 's/\\"/"/g')
        [ -n "$RB" ] || fail "pwsh: no reproduce command for b in failure.json"
        pwsh_reproduce "$JH" "$R" "$RB" "$O/pwsh.txt"
        [ "$STATUS" = 1 ] || fail "pwsh: exit $STATUS: $(tail -5 "$O/pwsh.txt")"
        has "$O/pwsh.txt" 'Tests run: 1, Failures: 1, Errors: 0, Skipped: 0' pwsh
        has "$O/pwsh.txt" 'BTest.adds' pwsh
    else
        echo "AGENT_EVENTS_TEST pwsh NOT RUN: no powershell.exe"
    fi

    slowtest "$R"
    mvnx "$JH" "$R" "$O/before-kill.txt" -q -Dmaven.agent=true -fae verify
    [ -s "$A/failure.json" ] && [ -s "$A/summary.txt" ] || fail "killed: no files from the build before"
    killed "$JH" "$R" "$O/killed.txt"
    [ -e "$A/failure.json" ] || [ -e "$A/failure.md" ] || [ -e "$A/summary.txt" ] || [ -e "$E" ] \
        && fail "killed: files of the previous build left: $(ls "$A")"
    rm -f "$R/b/src/test/java/fix/SlowTest.java"

    mvnx "$JH" "$R" "$O/cli.txt" -q -Dmaven.agent=true -Dmaven.compiler.showWarnings=true -pl a compiler:compile
    [ "$STATUS" = 1 ] || fail "cli: exit $STATUS"
    has "$A/failure.json" '"reproduce":"mvn -pl a -am \\"-Dmaven.compiler.showWarnings=true\\" org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile"' cli

    P="$T/$N/pom r"; fixture "$P" broken
    pom "$P/b" b '<dependencies><dependency><groupId>junit</groupId><artifactId>junit</artifactId><scope>test</scope></dependency></dependencies>'
    mvnx "$JH" "$P" "$O/pom.txt" -q -Dmaven.agent=true verify
    [ "$STATUS" = 1 ] || fail "pom: exit $STATUS"
    has "$O/pom.txt" "^\[agent\] FAILED \. () model: " pom
    has "$O/pom.txt" "^\[agent\]   ERROR b/pom.xml:1:[0-9]* 'dependencies.dependency.version' for junit:junit:jar is missing" pom
    has "$O/pom.txt" '^\[agent\]   reproduce: mvn validate' pom
    has "$P/target/agent/failure.json" '"kind":"model"' pom
    has "$P/target/agent/failure.json" '"file":"b/pom.xml","line":1,"column":[0-9]*,"severity":"ERROR"' pom
    has "$P/target/agent/build-events.jsonl" '"type":"diagnostic","project":null,"source":"model","severity":"ERROR","file":"b/pom.xml","line":1' pom
    cp -r "$P/target/agent" "$O/agent-pom"

    fixture "$R" fixed
    mvnx "$JH" "$R" "$O/green.txt" -q -Dstyle.color=never -Dmaven.agent=true clean verify
    [ "$STATUS" = 0 ] || fail "green: exit $STATUS: $(head -5 "$O/green.txt")"
    has "$O/green.txt" '^\[agent\] BUILD SUCCESS' green
    has "$O/green.txt" 'tests 2 run, 0 failed, 0 errors, 0 skipped' green
    [ -e "$A/failure.json" ] || [ -e "$A/failure.md" ] && fail "green: failure files left from the failed build"
    tail -1 "$E" | grep -q '"type":"session-end","outcome":"success"' || fail "green: last event"
    mvnx "$JH" "$R" "$O/green-clean.txt" -q -Dmaven.agent=true clean verify
    [ "$STATUS" = 0 ] || fail "green: clean over the agent files failed: $(head -5 "$O/green-clean.txt")"
    cp -r "$A" "$O/agent-green"
    echo "AGENT_EVENTS_TEST $N PASS"
done
echo "AGENT_EVENTS_TEST PASS"
