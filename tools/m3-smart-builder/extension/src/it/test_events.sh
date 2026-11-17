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
#   pom        a dependency without a version: kind model, the POM problem with file, line and column, session-start
#   wrapper    with mvnw/mvnw.cmd stubs: failure.json spells the launcher per shell, and the reproduce command reruns
#              the failed test in cmd.exe and Windows PowerShell (.\mvnw.cmd) and bash (./mvnw)
#   elsewhere  a build started from the parent directory with -f: the reproduce command carries -f and reruns there
#   typo       a mistyped phase is reproduced by the build's goals
#   squat      java.io.tmpdir/maven-build-events taken by a file: the events are still published
# A second reactor ("more r") holds one failure per module:
#   dep        a missing dependency: the digest keeps the whole message, with the reason (offline, not downloaded)
#   enforcer   a failed rule: the digest and mojo-end keep the rule's message line
#   profile    -Pstrict (failOnWarning) and -Dmaven.compiler.failOnWarning=true: the reproduce command carries the
#              profile or the property and, run as is, fails again
#   secret     dependency:get with a password in remoteRepositories: the password reaches no agent file and no output
#   longline   a test prints a 100 KB line with U+2028 into the -l log: the build ends in bounded time
#   green      fixed sources, clean verify: BUILD SUCCESS summary, failure files removed, no locked file on clean
# The root directory's name holds a space and a non-ASCII letter, so the -l log of Maven on Java 17 (platform
# charset) must be read in that charset for its paths to become root-relative.
# Usage: test_events.sh <extension.jar> <java-home>... (each JDK runs every case). Needs mvn and, in the local
# repository, compiler 3.13.0, surefire 3.2.5 with JUnit 4.13.2, resources 3.3.1, jar 3.4.1, clean 3.2.0,
# enforcer 3.4.1 and dependency 3.6.1.
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
mvnx() { # mvnx <java home> <dir> <out> <args...>; exit status kept in $STATUS; $EXTRA_OPTS goes into MAVEN_OPTS
    local jh=$1 dir=$2 out=$3; shift 3
    (cd "$dir" && JAVA_HOME="$jh" MAVEN_OPTS="-Dmaven.ext.class.path=$EXT ${EXTRA_OPTS:-}" mvn -o -B -ntp -s "$T/settings.xml" "$@") > "$out" 2>&1
    STATUS=$?
}
reproduce_of() { # reproduce_of <failure.json> <path>: the reproduce command of the failure at <path>, unescaped
    sed 's/.*"path":"'"$2"'","kind"//' "$1" | sed 's/"reproduce":"/\n/' | sed -n 2p | sed 's/"}.*//; s/\\"/"/g'
}
bash_reproduce() { # bash_reproduce <java home> <dir> <command> <out>: runs the command as given, in bash, in <dir>
    (cd "$2" && export JAVA_HOME="$1" MAVEN_OPTS="-Dmaven.ext.class.path=$EXT" && eval "$3 -o -B -ntp -s '$T/settings.xml'") > "$4" 2>&1
    STATUS=$?
}
cmd_reproduce() { # cmd_reproduce <java home> <root> <command> <out>: runs the command in cmd.exe
    local bat="$T/reproduce.cmd"
    printf '@echo off\r\nchcp 65001 > nul\r\ncd /d "%s"\r\nset "JAVA_HOME=%s"\r\nset "MAVEN_OPTS=-Dmaven.ext.class.path=%s"\r\n%s -o -B -ntp -s "%s"\r\nexit /b %%ERRORLEVEL%%\r\n' \
        "$(cygpath -w "$2")" "$(cygpath -w "$1")" "$(cygpath -w "$EXT")" "$3" "$(cygpath -w "$T/settings.xml")" > "$bat"
    cmd.exe //d //c "$(cygpath -w "$bat")" > "$4" 2>&1
    STATUS=$?
}
fixture2() { # fixture2 <dir>: dep (missing dependency), plug (enforcer rule), prof (profile strict), long (a long line)
    local R=$1 m
    printf '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>fix</groupId><artifactId>root</artifactId><version>1.0-SNAPSHOT</version><packaging>pom</packaging><modules><module>dep</module><module>plug</module><module>prof</module><module>long</module></modules><properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties><build><pluginManagement><plugins><plugin><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin><plugin><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version><configuration><showWarnings>true</showWarnings></configuration></plugin><plugin><artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version></plugin><plugin><artifactId>maven-jar-plugin</artifactId><version>3.4.1</version></plugin><plugin><artifactId>maven-enforcer-plugin</artifactId><version>3.4.1</version></plugin></plugins></pluginManagement></build></project>\n' > "$R/pom.xml"
    for m in dep plug prof long; do mkdir -p "$R/$m/src/main/java/fix"; printf 'package fix;\n\npublic class M%s {\n}\n' "$m" > "$R/$m/src/main/java/fix/M$m.java"; done
    pom "$R/dep" dep '<dependencies><dependency><groupId>fix</groupId><artifactId>missing-lib</artifactId><version>9.9</version></dependency></dependencies>'
    pom "$R/plug" plug '<build><plugins><plugin><artifactId>maven-enforcer-plugin</artifactId><executions><execution><id>need-prop</id><goals><goal>enforce</goal></goals><configuration><rules><requireProperty><property>must.be.set</property><message>must.be.set is required</message></requireProperty></rules></configuration></execution></executions></plugin></plugins></build>'
    pom "$R/prof" prof '<profiles><profile><id>strict</id><properties><maven.compiler.failOnWarning>true</maven.compiler.failOnWarning></properties></profile></profiles>'
    printf 'package fix;\n\npublic class Old {\n    public Integer boxed() {\n        return new Integer(5);\n    }\n}\n' > "$R/prof/src/main/java/fix/Old.java"
    pom "$R/long" long '<dependencies><dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope></dependency></dependencies>'
    mkdir -p "$R/long/src/test/java/fix"
    printf 'package fix;\n\nimport org.junit.Test;\n\npublic class LongLineTest {\n    @Test\n    public void prints() {\n        System.out.println("a.b:[1,2] x ".repeat(9000) + "\\u2028x");\n    }\n}\n' > "$R/long/src/test/java/fix/LongLineTest.java"
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
    has "$A/failure.json" '"reproduce":"mvn \\"-Dmaven.compiler.showWarnings=true\\" -pl a -am org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile"' cli

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
    head -1 "$P/target/agent/build-events.jsonl" | grep -q '"seq":1,.*"type":"session-start"' || fail "pom: no session-start first"
    cp -r "$P/target/agent" "$O/agent-pom"

    printf '#!/bin/sh\nexec mvn "$@"\n' > "$R/mvnw"; chmod +x "$R/mvnw"; printf '@call mvn %%*\r\n' > "$R/mvnw.cmd"
    mvnx "$JH" "$R" "$O/wrapper.txt" -q -Dmaven.agent=true -fae verify
    [ "$STATUS" = 1 ] || fail "wrapper: exit $STATUS"
    has "$A/failure.json" '"launcher":{"sh":"./mvnw","powershell":".\\\\mvnw.cmd","cmd":".\\\\mvnw.cmd"}' wrapper
    RW=$(reproduce_of "$A/failure.json" b)
    [ "$RW" = './mvnw -pl b -am "-Dtest=fix.BTest#adds" "-Dsurefire.failIfNoSpecifiedTests=false" test' ] || fail "wrapper: reproduce $RW"
    bash_reproduce "$JH" "$R" "$RW" "$O/wrapper-bash.txt"
    [ "$STATUS" = 1 ] || fail "wrapper: bash exit $STATUS: $(tail -5 "$O/wrapper-bash.txt")"
    has "$O/wrapper-bash.txt" 'Tests run: 1, Failures: 1, Errors: 0, Skipped: 0' wrapper-bash
    if command -v cmd.exe > /dev/null && command -v powershell.exe > /dev/null; then
        cmd_reproduce "$JH" "$R" ".\\mvnw.cmd${RW#./mvnw}" "$O/wrapper-cmd.txt"
        [ "$STATUS" = 1 ] || fail "wrapper: cmd.exe exit $STATUS: $(tail -5 "$O/wrapper-cmd.txt")"
        has "$O/wrapper-cmd.txt" 'Tests run: 1, Failures: 1, Errors: 0, Skipped: 0' wrapper-cmd
        pwsh_reproduce "$JH" "$R" ".\\mvnw.cmd${RW#./mvnw}" "$O/wrapper-pwsh.txt"
        [ "$STATUS" = 1 ] || fail "wrapper: PowerShell exit $STATUS: $(tail -5 "$O/wrapper-pwsh.txt")"
        has "$O/wrapper-pwsh.txt" 'Tests run: 1, Failures: 1, Errors: 0, Skipped: 0' wrapper-pwsh
    else
        echo "AGENT_EVENTS_TEST wrapper cmd.exe/PowerShell NOT RUN: no cmd.exe or powershell.exe"
    fi
    rm -f "$R/mvnw" "$R/mvnw.cmd"

    mvnx "$JH" "$T/$N" "$O/elsewhere.txt" -q -Dmaven.agent=true -fae -f "fx é dir/pom.xml" verify
    [ "$STATUS" = 1 ] || fail "elsewhere: exit $STATUS"
    RE=$(reproduce_of "$A/failure.json" b)
    [ "$RE" = 'mvn -f "fx é dir/pom.xml" -pl b -am "-Dtest=fix.BTest#adds" "-Dsurefire.failIfNoSpecifiedTests=false" test' ] || fail "elsewhere: reproduce $RE"
    bash_reproduce "$JH" "$T/$N" "$RE" "$O/elsewhere-run.txt"
    [ "$STATUS" = 1 ] || fail "elsewhere: rerun exit $STATUS: $(tail -5 "$O/elsewhere-run.txt")"
    has "$O/elsewhere-run.txt" 'Tests run: 1, Failures: 1, Errors: 0, Skipped: 0' elsewhere

    mvnx "$JH" "$R" "$O/typo.txt" -q -Dmaven.agent=true verfy
    [ "$STATUS" = 1 ] || fail "typo: exit $STATUS"
    grep -q '"reproduce":"mvn \(-pl \. -am \)\{0,1\}verfy"' "$A/failure.json" || fail "typo: $(cat "$A/failure.json")"

    mkdir -p "$T/$N/sharedtmp"; printf 'squatter\n' > "$T/$N/sharedtmp/maven-build-events"
    EXTRA_OPTS="-Djava.io.tmpdir=$(cygpath -m "$T/$N/sharedtmp" 2>/dev/null || echo "$T/$N/sharedtmp")" \
        mvnx "$JH" "$R" "$O/squat.txt" -q -Dmaven.agent=true -pl a validate
    [ "$STATUS" = 0 ] || fail "squat: exit $STATUS: $(tail -5 "$O/squat.txt")"
    [ -s "$A/build-events.jsonl" ] && [ -s "$A/summary.txt" ] || fail "squat: no events published: $(tail -5 "$O/squat.txt")"
    grep -q 'Build events stopped' "$O/squat.txt" && fail "squat: the spy stopped"

    M="$T/$N/more r"; MA="$M/target/agent"; mkdir -p "$M"; fixture2 "$M"
    mvnx "$JH" "$M" "$O/dep.txt" -q -Dmaven.agent=true -pl dep verify
    [ "$STATUS" = 1 ] || fail "dep: exit $STATUS"
    has "$MA/failure.json" '"message":"Could not resolve dependencies for project fix:dep:jar:1.0-SNAPSHOT[^"]*missing-lib:jar:9.9[^"]*offline' dep
    mvnx "$JH" "$M" "$O/enforcer.txt" -q -Dmaven.agent=true -pl plug validate
    [ "$STATUS" = 1 ] || fail "enforcer: exit $STATUS"
    has "$MA/failure.json" '"message":"Rule 0[^"]*must.be.set is required' enforcer
    has "$MA/build-events.jsonl" '"type":"mojo-end",.*"outcome":"failure",.*"error":"Rule 0[^"]*must.be.set is required' enforcer

    mvnx "$JH" "$M" "$O/profile.txt" -q -Dmaven.agent=true -Pstrict -pl prof -am compile
    [ "$STATUS" = 1 ] || fail "profile: exit $STATUS"
    RP=$(reproduce_of "$MA/failure.json" prof)
    [ "$RP" = 'mvn "-Pstrict" -pl prof -am compile' ] || fail "profile: reproduce $RP"
    bash_reproduce "$JH" "$M" "$RP" "$O/profile-run.txt"
    [ "$STATUS" = 1 ] || fail "profile: the reproduce command passed: $(tail -5 "$O/profile-run.txt")"
    has "$O/profile-run.txt" 'warnings found and -Werror specified' profile
    mvnx "$JH" "$M" "$O/property.txt" -q -Dmaven.agent=true -Dmaven.compiler.failOnWarning=true -pl prof -am compile
    [ "$STATUS" = 1 ] || fail "property: exit $STATUS"
    RP=$(reproduce_of "$MA/failure.json" prof)
    [ "$RP" = 'mvn "-Dmaven.compiler.failOnWarning=true" -pl prof -am compile' ] || fail "property: reproduce $RP"
    bash_reproduce "$JH" "$M" "$RP" "$O/property-run.txt"
    [ "$STATUS" = 1 ] || fail "property: the reproduce command passed: $(tail -5 "$O/property-run.txt")"

    mvnx "$JH" "$M" "$O/secret.txt" -q -Dmaven.agent=true -N org.apache.maven.plugins:maven-dependency-plugin:3.6.1:get \
        "-DremoteRepositories=central::default::https://alice:S3cretPw@repo.example.com/maven2" -Dartifact=no.such:artifact:1.0
    [ "$STATUS" = 1 ] || fail "secret: exit $STATUS"
    LEAK=$(grep -rl 'S3cretPw' "$MA" "$O/secret.txt")
    [ -z "$LEAK" ] || fail "secret: the password is in $LEAK"
    RS=$(reproduce_of "$MA/failure.json" .)
    [ "$RS" = 'mvn "-Dartifact=no.such:artifact:1.0" -pl . -am org.apache.maven.plugins:maven-dependency-plugin:3.6.1:get' ] || fail "secret: reproduce $RS"

    SECONDS=0
    mvnx "$JH" "$M" "$O/longline.txt" -l "$O/longline.log" -Dmaven.agent=true "-Dmaven.events.log=$O/longline.log" -pl long test
    [ "$STATUS" = 0 ] || fail "longline: exit $STATUS: $(tail -5 "$O/longline.log")"
    [ "$SECONDS" -lt 90 ] || fail "longline: the build took $SECONDS s"
    echo "AGENT_EVENTS_TEST longline $N: $SECONDS s"
    cp -r "$MA" "$O/agent-more"

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
