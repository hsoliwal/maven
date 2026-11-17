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
# Inline code clustering in real offline Maven builds of a scratch reactor, with no goal and no POM change:
#   compile      mvn compile writes every jar module's target/code-clustering tables during compilation, identical
#                to the standalone CodeClustering cluster scan of its src/main/java (same JDK); JNI.tsv holds the
#                native method; module b compiles with -Xlint:all -Werror
#   processors   Lombok still runs: from the class path in module d (no annotationProcessorPaths) and from
#                annotationProcessorPaths in module c, where the plugin joins by coordinates
#   test         mvn test-compile writes target/code-clustering/test for the test sources
#   incremental  a changed source after a second compile is in the tables, again identical to a fresh scan
#   off          -Dclustering.inline=false writes nothing; a POM property clustering.inline=false turns one module off
#   safe         an output path that is a file leaves the build green with a warning; a missing plugin jar is logged
#   local repo   without clustering.pluginJar the plugin comes from the local repository
# Usage: test_inline_clustering.sh <extension.jar> <plugin.jar> <java-home> [mvn] [local-repository]
# Needs maven-compiler-plugin, maven-resources-plugin, maven-clean-plugin and Lombok 1.18.36 in the local repository.
# With KEEP_LOGS=<dir> the Maven logs are copied there.
set -uo pipefail
EXT=${1:?extension jar}; PLUGIN=${2:?plugin jar}; JH=${3:?java home}; MVN=${4:-mvn}; REPO=${5:-}
T=$(mktemp -d); trap '[ -n "${KEEP_LOGS:-}" ] && mkdir -p "$KEEP_LOGS" && cp "$T"/*.log "$KEEP_LOGS"/; rm -rf "$T"' EXIT
fail() { echo "INLINE_CLUSTERING_TEST FAIL $*"; exit 1; }
pass() { echo "PASS $*"; }
printf '<settings/>\n' > "$T/settings.xml"
R="$T/r"; P='<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
PARENT='<parent><groupId>fix</groupId><artifactId>root</artifactId><version>1.0-SNAPSHOT</version></parent>'
LOMBOK='<dependencies><dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.36</version><scope>provided</scope></dependency></dependencies>'
mkdir -p "$R/a/src/main/java/a" "$R/a/src/test/java/a" "$R/b/src/main/java/b" "$R/c/src/main/java/c" "$R/d/src/main/java/d"
printf '%s<groupId>fix</groupId><artifactId>root</artifactId><version>1.0-SNAPSHOT</version><packaging>pom</packaging><modules><module>a</module><module>b</module><module>c</module><module>d</module></modules><properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>\n' "$P" > "$R/pom.xml"
printf '%s%s<artifactId>a</artifactId></project>\n' "$P" "$PARENT" > "$R/a/pom.xml"
printf '%s%s<artifactId>b</artifactId><dependencies><dependency><groupId>fix</groupId><artifactId>a</artifactId><version>1.0-SNAPSHOT</version></dependency></dependencies><build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><configuration><compilerArgs><arg>-Xlint:all</arg><arg>-Werror</arg></compilerArgs></configuration></plugin></plugins></build></project>\n' "$P" "$PARENT" > "$R/b/pom.xml"
printf '%s%s<artifactId>c</artifactId>%s<build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><configuration><annotationProcessorPaths><path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.36</version></path></annotationProcessorPaths></configuration></plugin></plugins></build></project>\n' "$P" "$PARENT" "$LOMBOK" > "$R/c/pom.xml"
printf '%s%s<artifactId>d</artifactId>%s</project>\n' "$P" "$PARENT" "$LOMBOK" > "$R/d/pom.xml"
SUM='    int sum(int[] values) {\n        int total = 0;\n        for (int v : values) {\n            total += v;\n        }\n        return total;\n    }\n'
printf "package a;\n\npublic class Lib {\n$SUM    int add(int[] xs) {\n        int acc = 0;\n        for (int x : xs) {\n            acc += x;\n        }\n        return acc;\n    }\n}\n" > "$R/a/src/main/java/a/Lib.java"
printf 'package a;\n\npublic class Native {\n    public static native long open(String path, int[] flags);\n}\n' > "$R/a/src/main/java/a/Native.java"
printf "package a;\n\nclass LibCheck {\n$SUM}\n" > "$R/a/src/test/java/a/LibCheck.java"
printf "package b;\n\npublic class Use {\n$SUM    int twice(a.Lib lib, int[] v) {\n        return 2 * v.length + lib.hashCode();\n    }\n}\n" > "$R/b/src/main/java/b/Use.java"
for m in c d; do
    printf 'package %s;\n\n@lombok.Getter\npublic class Bean {\n    private int size = 3;\n}\n' "$m" > "$R/$m/src/main/java/$m/Bean.java"
    printf 'package %s;\n\nclass UseBean {\n    int size() {\n        return new Bean().getSize();\n    }\n}\n' "$m" > "$R/$m/src/main/java/$m/UseBean.java"
done
mvnx() { # mvnx <log> <args...>: the build with the extension and, unless the args set another, the plugin jar
    local log=$1; shift
    (cd "$R" && JAVA_HOME="$JH" MAVEN_OPTS="-Dmaven.ext.class.path=$EXT" "$MVN" -o -B -ntp -s "$T/settings.xml" \
        ${REPO:+-Dmaven.repo.local=$REPO} "$@") > "$T/$log" 2>&1
}
same() { # same <module> <src dir> <table dir>: the inline tables equal the standalone scan
    rm -rf "$T/cli"; "$JH/bin/java" -cp "$PLUGIN" org.apache.maven.plugins.clustering.CodeClustering cluster "$T/cli" "$R/$1/$2" > /dev/null || return 1
    for t in ATOM_HASHES.tsv CLUSTERS.tsv PLANE_SUMMARY.tsv FILE_PAIRS.tsv FILE_CLUSTERS.tsv; do
        cmp -s "$R/$1/$3/$t" "$T/cli/$t" || { echo "  differs: $1/$3/$t"; return 1; }
    done
}
tables() { find "$R" -path '*/target/code-clustering*' -name ATOM_HASHES.tsv | wc -l | tr -d ' '; }

mvnx compile.log -Dclustering.pluginJar="$PLUGIN" compile || fail "compile: build not green: $(grep ERROR "$T/compile.log" | head -3)"
grep -q 'Inline code clustering: javac plugin CodeClustering from' "$T/compile.log" || fail "compile: no wiring line"
grep -q -- '--- .*code-clustering' "$T/compile.log" && fail "compile: a code-clustering goal ran"
for m in a b c d; do same $m src/main/java target/code-clustering || fail "compile: $m tables differ from the scan"; done
pass "compile: 4 modules, tables identical to the standalone scan, no goal"
grep -q $'a/Native.java\tNative.open(String,int\[\])\ta/Native\topen\tLjava/lang/String;\[I' "$R/a/target/code-clustering/JNI.tsv" || fail "compile: JNI row"
pass "compile: JNI.tsv row with binary name and descriptor; b green with -Xlint:all -Werror"
[ -f "$R/c/target/classes/c/UseBean.class" ] && [ -f "$R/d/target/classes/d/UseBean.class" ] || fail "processors: Lombok did not run"
grep -q $'^c/Bean.java\tTYPE' "$R/c/target/code-clustering/ATOM_HASHES.tsv" || fail "processors: no c atoms"
pass "processors: Lombok ran from the class path (d) and from annotationProcessorPaths (c)"
mvnx test.log -Dclustering.pluginJar="$PLUGIN" test-compile || fail "test: build not green"
same a src/test/java target/code-clustering/test || fail "test: test tables differ from the scan"
pass "test: target/code-clustering/test identical to the scan of src/test/java"
printf "package a;\n\npublic class Lib {\n$SUM    int product(int[] xs) {\n        int acc = 1;\n        for (int x : xs) {\n            acc *= x;\n        }\n        return acc;\n    }\n}\n" > "$R/a/src/main/java/a/Lib.java"
mvnx again.log -Dclustering.pluginJar="$PLUGIN" compile || fail "incremental: build not green"
grep -q $'Lib.product(int\[\])' "$R/a/target/code-clustering/ATOM_HASHES.tsv" && same a src/main/java target/code-clustering \
    || fail "incremental: changed source not in the tables"
pass "incremental: changed source in the tables, identical to a fresh scan"
mvnx clean-off.log -Dclustering.pluginJar="$PLUGIN" -Dclustering.inline=false clean compile || fail "off: build not green"
[ "$(tables)" = 0 ] || fail "off: tables written with clustering.inline=false"
grep -q 'Inline code clustering' "$T/clean-off.log" && fail "off: wiring logged"
pass "off: -Dclustering.inline=false writes nothing"
sed -i 's#<artifactId>b</artifactId>#<artifactId>b</artifactId><properties><clustering.inline>false</clustering.inline></properties>#' "$R/b/pom.xml"
mvnx pom-off.log -Dclustering.pluginJar="$PLUGIN" clean compile || fail "pom off: build not green"
[ -f "$R/a/target/code-clustering/ATOM_HASHES.tsv" ] && [ ! -e "$R/b/target/code-clustering" ] || fail "pom off: b not off or a off"
pass "off: POM property clustering.inline=false turns module b off"
mvnx clean.log clean || fail "safe: clean"
mkdir -p "$R/a/target" && printf 'a file\n' > "$R/a/target/code-clustering"
mvnx safe.log -Dclustering.pluginJar="$PLUGIN" compile || fail "safe: an unwritable output failed the build"
grep -q 'code clustering: COMPILATION not recorded' "$T/safe.log" || fail "safe: no warning"
pass "safe: unwritable output, build green, warning logged"
mvnx missing.log -Dclustering.pluginVersion=0.0.0-missing compile || fail "safe: missing plugin failed the build"
grep -q 'Inline code clustering off: org.apache.maven.plugins:maven-code-clustering-plugin:0.0.0-missing is not in the local repository' "$T/missing.log" || fail "safe: missing plugin not logged"
pass "safe: missing plugin jar logged, build green"
rm -rf "$R/a/target"
mvnx repo.log compile || fail "local repo: build not green"
same a src/main/java target/code-clustering || fail "local repo: tables differ"
pass "local repo: plugin from the local repository"
echo "INLINE_CLUSTERING_TEST PASS"
