#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements; see the
# NOTICE file. The ASF licenses this file to you under the Apache License, Version 2.0.
#
# Native build policy in the fork's distribution, offline with an EMPTY local repository, so the enforcer plugin
# cannot be resolved: a build that passes proves the plugin was absorbed, not run.
#   absorbed     requireMavenVersion + requireJavaVersion in an enforcer execution: evaluated natively, build green
#   java/maven   an unmet Java feature or Maven version fails the build natively, before any goal
#   properties   maven.policy.javaFeature without any plugin fails the same way; maven.policy.severity=WARN warns
#   kept         a rule the service cannot express keeps the execution, so the missing plugin is reported
#   off          -Dmaven.policy.native=false leaves everything to the plugin
#   stock        (when a stock mvn is given) stock Maven cannot build the absorbed case offline
# Usage: test_native_policy.sh <fork-maven-home> <java-home> [stock mvn command]
set -uo pipefail
M4=${1:?fork maven home}; JH=${2:?java home}; STOCK=${3:-}
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail() { echo "NATIVE_POLICY_TEST FAIL $*"; exit 1; }
printf '<settings/>\n' > "$T/settings.xml"
pom() { # pom <dir> <enforcer rules xml or empty> <properties xml or empty>
    mkdir -p "$1"
    local plugin=""
    [ -n "$2" ] && plugin="<build><plugins><plugin><artifactId>maven-enforcer-plugin</artifactId><version>3.5.0</version><executions><execution><id>enforce</id><goals><goal>enforce</goal></goals><configuration><rules>$2</rules></configuration></execution></executions></plugin></plugins></build>"
    printf '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>fix</groupId><artifactId>%s</artifactId><version>1</version><packaging>pom</packaging><properties>%s</properties>%s</project>\n' "$(basename "$1")" "$3" "$plugin" > "$1/pom.xml"
}
run() { local dir=$1 log=$2; shift 2; (cd "$dir" && env -u MAVEN_OPTS JAVA_HOME="$JH" "$M4/bin/mvn" -o -B -ntp -s "$T/settings.xml" "-Dmaven.repo.local=$T/empty-repo" "$@" validate) > "$T/$log" 2>&1; }

pom "$T/absorbed" '<requireMavenVersion><version>[3.9,)</version></requireMavenVersion><requireJavaVersion><version>[17,)</version></requireJavaVersion>' ''
run "$T/absorbed" absorbed.log || fail "absorbed: build not green: $(grep -E 'ERROR' "$T/absorbed.log" | head -3)"
grep -q "Native policy: absorbed evaluates enforcer execution 'enforce' natively" "$T/absorbed.log" || fail "absorbed: no absorption line"
[ -d "$T/empty-repo/org/apache/maven/plugins/maven-enforcer-plugin" ] && fail "absorbed: the enforcer plugin was resolved"

pom "$T/java" '<requireJavaVersion><version>99</version></requireJavaVersion>' ''
run "$T/java" java.log && fail "java: an unmet Java requirement left the build green"
grep -q 'Native build policy failed' "$T/java.log" && grep -q 'java: Maven JVM Java feature [0-9]* is below 99 (requireJavaVersion)' "$T/java.log" \
    || fail "java: not reported natively: $(grep ERROR "$T/java.log" | head -2)"

pom "$T/maven" '<requireMavenVersion><version>[9,)</version></requireMavenVersion>' ''
run "$T/maven" maven.log && fail "maven: an unmet Maven requirement left the build green"
grep -q 'does not satisfy \[9,) (requireMavenVersion)' "$T/maven.log" || fail "maven: not reported natively"

pom "$T/properties" '' '<maven.policy.javaFeature>99</maven.policy.javaFeature>'
run "$T/properties" properties.log && fail "properties: an unmet property rule left the build green"
grep -q 'properties: Maven JVM Java feature [0-9]* is below 99' "$T/properties.log" || fail "properties: not reported"
pom "$T/warned" '' '<maven.policy.javaFeature>99</maven.policy.javaFeature><maven.policy.severity>WARN</maven.policy.severity>'
run "$T/warned" warned.log || fail "warned: a WARN rule failed the build"
grep -q 'WARNING.*Native policy: warned: Maven JVM Java feature [0-9]* is below 99' "$T/warned.log" || fail "warned: no warning"

pom "$T/kept" '<requireJavaVersion><version>17</version></requireJavaVersion><bannedDependencies><excludes><exclude>x:y</exclude></excludes></bannedDependencies>' ''
run "$T/kept" kept.log && fail "kept: an execution with a rule the service cannot express was absorbed"
grep -q 'maven-enforcer-plugin' "$T/kept.log" || fail "kept: the plugin was not asked for: $(grep ERROR "$T/kept.log" | head -2)"
grep -q 'Native policy: kept evaluates' "$T/kept.log" && fail "kept: absorption line for a kept execution"

run "$T/java" off.log -Dmaven.policy.native=false && fail "off: build green without the enforcer plugin"
grep -q 'Native build policy failed' "$T/off.log" && fail "off: native policy ran although disabled"
grep -q 'maven-enforcer-plugin' "$T/off.log" || fail "off: the plugin was not asked for"

if [ -n "$STOCK" ]; then
    (cd "$T/absorbed" && env -u MAVEN_OPTS JAVA_HOME="$JH" "$STOCK" -o -B -ntp -s "$T/settings.xml" "-Dmaven.repo.local=$T/empty-stock" validate) > "$T/stock.log" 2>&1 \
        && fail "stock: stock Maven built the absorbed case without the plugin"
    grep -q 'maven-enforcer-plugin' "$T/stock.log" || fail "stock: failed for another reason"
fi
echo "NATIVE_POLICY_TEST PASS"
