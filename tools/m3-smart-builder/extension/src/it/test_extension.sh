#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
# The core extension in real Maven builds, offline, no model (provider none: the hook records facts only).
#   antrun      without the extension, the version 2 antrun step fails that reactor on b's dependency resolution
#   bound       -Dchromellm.advice=true: m3:build-hook runs at validate in all three projects of a reactor whose
#               jar module b depends on jar module a (nothing installed); the version 2 antrun steps are removed,
#               so the build stays green
#   off         without the property nothing is bound and the antrun profile stays inactive
#   declared    a project that declares the plugin itself keeps its own binding only
#   no plugin   with a local repository that lacks m3-smart-maven-plugin the extension only logs why
#   java 17     (when a JDK below 21 is given) the extension only logs why
# Usage: test_extension.sh <extension.jar> <java-21-home> [<java-17-home>]. Needs mvn and the plugin installed.
set -uo pipefail
EXT=${1:?extension jar}; J21=${2:?java 21 home}; J17=${3:-}
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail() { echo "M3_SMART_EXTENSION_TEST FAIL $*"; exit 1; }
printf '<settings/>\n' > "$T/settings.xml"
ant='<profiles><profile><id>chromellm-advice</id><activation><property><name>chromellm.advice</name><value>true</value></property></activation><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-antrun-plugin</artifactId><version>3.1.0</version><executions><execution><id>chromellm-pre-build</id><phase>validate</phase><goals><goal>run</goal></goals><configuration><target><echo message="ANT-STEP-RAN"/></target></configuration></execution></executions></plugin></plugins></build></profile></profiles>'
reactor() { # reactor <dir> <root profiles xml>
    mkdir -p "$1/a" "$1/b"
    printf '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>fix</groupId><artifactId>root</artifactId><version>1.0-SNAPSHOT</version><packaging>pom</packaging><modules><module>a</module><module>b</module></modules>%s</project>\n' "$2" > "$1/pom.xml"
    # jar modules: at validate the reactor has no jar of a yet, which is what makes a dependency-resolving step fail
    printf '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><parent><groupId>fix</groupId><artifactId>root</artifactId><version>1.0-SNAPSHOT</version></parent><artifactId>a</artifactId></project>\n' > "$1/a/pom.xml"
    printf '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><parent><groupId>fix</groupId><artifactId>root</artifactId><version>1.0-SNAPSHOT</version></parent><artifactId>b</artifactId><dependencies><dependency><groupId>fix</groupId><artifactId>a</artifactId><version>1.0-SNAPSHOT</version></dependency></dependencies></project>\n' > "$1/b/pom.xml"
}
mvnx() { # mvnx <java home> <dir> <log> <args...>
    local jh=$1 dir=$2 log=$3; shift 3
    (cd "$dir" && JAVA_HOME="$jh" MAVEN_OPTS="-Dmaven.ext.class.path=$EXT" mvn -o -B -ntp -s "$T/settings.xml" "$@") > "$T/$log" 2>&1
}
report() { [ -s "$1/target/m3-smart/pre-build.json" ]; }

R="$T/r"; reactor "$R" "$ant"
(cd "$R" && JAVA_HOME="$J21" mvn -o -B -ntp -s "$T/settings.xml" -Dchromellm.advice=true validate) > "$T/antrun.log" 2>&1 \
    && fail "antrun: without the extension the antrun step was expected to fail on dependency resolution"
grep -q 'Could not resolve dependencies for project fix:b' "$T/antrun.log" || fail "antrun: failed for another reason"
rm -rf "$R/target" "$R/a/target" "$R/b/target"
mvnx "$J21" "$R" bound.log -Dchromellm.advice=true validate || fail "bound: build not green: $(grep -E 'ERROR' "$T/bound.log" | head -3)"
grep -q 'ChromeLLM advice: m3:build-hook bound before and after 3 project(s)' "$T/bound.log" || fail "bound: no binding line"
for p in "$R" "$R/a" "$R/b"; do report "$p" || fail "bound: no pre-build report in $p"; done
grep -q '"phase":"pre-build"' "$R/b/target/m3-smart/pre-build.json" || fail "bound: report phase"
grep -q 'ANT-STEP-RAN' "$T/bound.log" && fail "bound: the antrun step still ran"
grep -q 'build-hook (chromellm-pre-build)' "$T/bound.log" || fail "bound: execution id"

rm -rf "$R/target" "$R/a/target" "$R/b/target"
mvnx "$J21" "$R" off.log validate || fail "off: build not green"
grep -q 'ChromeLLM advice' "$T/off.log" && fail "off: extension acted without the property"
report "$R/a" && fail "off: report written"

D="$T/d"; mkdir -p "$D"
printf '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>fix</groupId><artifactId>declared</artifactId><version>1</version><packaging>pom</packaging><build><plugins><plugin><groupId>io.synexia</groupId><artifactId>m3-smart-maven-plugin</artifactId><version>0.1.0-SNAPSHOT</version><executions><execution><id>own</id><phase>validate</phase><goals><goal>build-hook</goal></goals><configuration><phase>pre-build</phase></configuration></execution></executions></plugin></plugins></build></project>\n' > "$D/pom.xml"
mvnx "$J21" "$D" declared.log -Dchromellm.advice=true validate || fail "declared: build not green"
grep -q 'bound before and after 0 project(s)' "$T/declared.log" || fail "declared: the extension bound a declared plugin"
[ "$(grep -c 'Build advice pre-build report' "$T/declared.log")" = 1 ] || fail "declared: the hook ran more than once"

P="$T/p"; reactor "$P" ""
mvnx "$J21" "$P" noplugin.log -Dchromellm.advice=true "-Dmaven.repo.local=$T/empty-repo" validate || fail "no plugin: build not green: $(grep ERROR "$T/noplugin.log" | head -2)"
grep -q 'ChromeLLM advice skipped: io.synexia:m3-smart-maven-plugin:0.1.0-SNAPSHOT is not in the local repository' "$T/noplugin.log" \
    || fail "no plugin: no skip line"

if [ -n "$J17" ]; then
    rm -rf "$P/target" "$P/a/target" "$P/b/target"
    mvnx "$J17" "$P" java17.log -Dchromellm.advice=true validate || fail "java 17: build not green"
    grep -q 'ChromeLLM advice skipped: m3:build-hook needs Maven on Java 21 or newer' "$T/java17.log" || fail "java 17: no skip line"
    report "$P/a" && fail "java 17: report written"
fi
echo "M3_SMART_EXTENSION_TEST PASS"
