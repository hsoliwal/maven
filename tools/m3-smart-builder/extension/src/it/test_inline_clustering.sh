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
# Inline code clustering in real offline Maven builds of a scratch reactor, with no goal and no POM change. The
# reactor lies under a directory with two consecutive spaces (paths reach the plugin as file URIs).
#   compile      mvn compile writes every jar module's target/code-clustering/inline tables during compilation,
#                identical to the standalone CodeClustering cluster scan of its src/main/java (same JDK); JNI.tsv
#                holds the native method; module b compiles with -Xlint:all -Werror; a modular module (module-info)
#                and default-package files of the same name in two directories are atomized too
#   processors   Lombok still runs from the class path (d) and from annotationProcessorPaths (c); a processor named
#                in annotationProcessors (nm) and one found on the class path (gu) still run; generated sources go
#                to inline/generated, identical to the scan of target/generated-sources/annotations
#   test         mvn test-compile writes target/code-clustering/inline/test for the test sources
#   incremental  a changed source after a second compile is in the tables; a source newly excluded drops out
#   off          -Dclustering.inline=false or FALSE writes nothing; a POM property turns one module off
#   safe         an output path that is a file leaves the build green with a warning; a missing plugin jar and a
#                jar without the javac registration (given, or in the local repository) are logged, build green
#   local repo   without clustering.pluginJar the plugin comes from the local repository
#   reactor      a reactor that builds maven-code-clustering-plugin: annotationProcessorPaths are left alone
#                (logged), other modules are wired; also with -T 4
#   java 23+     with JDK23_HOME set (a JDK 23 or newer): named and class-path processors still run, no -proc:none
#                where processors are named
# Usage: test_inline_clustering.sh <extension.jar> <plugin.jar> <java-home> [mvn] [local-repository]
# With a local repository, REPO_TAIL=<repository> chains another one behind it (maven.repo.local.tail), so the plugin
# can be installed in a scratch repository while everything else comes from the usual one. Needs
# maven-compiler-plugin, maven-resources-plugin, maven-clean-plugin and Lombok 1.18.36 in the local repository.
# With KEEP_LOGS=<dir> the Maven logs are copied there.
set -uo pipefail
EXT=${1:?extension jar}; PLUGIN=${2:?plugin jar}; JH=${3:?java home}; MVN=${4:-mvn}; REPO=${5:-}
T=$(mktemp -d); trap '[ -n "${KEEP_LOGS:-}" ] && mkdir -p "$KEEP_LOGS" && cp "$T"/*.log "$KEEP_LOGS"/; rm -rf "$T"' EXIT
fail() { echo "INLINE_CLUSTERING_TEST FAIL $*"; exit 1; }
pass() { echo "PASS $*"; }
printf '<settings/>\n' > "$T/settings.xml"
R="$T/two  spaces/r"; P='<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
I=target/code-clustering/inline
PARENT='<parent><groupId>fix</groupId><artifactId>root</artifactId><version>1.0-SNAPSHOT</version></parent>'
LOMBOK='<dependencies><dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.36</version><scope>provided</scope></dependency></dependencies>'
PROC='<dependencies><dependency><groupId>fix</groupId><artifactId>proc</artifactId><version>1.0-SNAPSHOT</version><scope>provided</scope></dependency></dependencies>'
COMPILER='<build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><configuration>%s</configuration></plugin></plugins></build>'
module() { # module <name> <extra POM text>: a jar module of the reactor
    mkdir -p "$R/$1/src/main/java"; printf '%s%s<artifactId>%s</artifactId>%s</project>\n' "$P" "$PARENT" "$1" "$2" > "$R/$1/pom.xml"
}
mkdir -p "$R"
printf '%s<groupId>fix</groupId><artifactId>root</artifactId><version>1.0-SNAPSHOT</version><packaging>pom</packaging><modules><module>a</module><module>b</module><module>c</module><module>d</module><module>m</module><module>dp</module><module>x</module><module>proc</module><module>gu</module><module>nm</module></modules><properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>\n' "$P" > "$R/pom.xml"
module a ''
module b "<dependencies><dependency><groupId>fix</groupId><artifactId>a</artifactId><version>1.0-SNAPSHOT</version></dependency></dependencies>$(printf "$COMPILER" '<compilerArgs><arg>-Xlint:all</arg><arg>-Werror</arg></compilerArgs>')"
module c "$LOMBOK$(printf "$COMPILER" '<annotationProcessorPaths><path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.36</version></path></annotationProcessorPaths>')"
module d "$LOMBOK"
module m ''; module dp ''; module x ''
module proc "$(printf "$COMPILER" '<proc>none</proc>')"
module gu "$PROC$(printf "$COMPILER" '<proc>full</proc>')"
module nm "$PROC$(printf "$COMPILER" '<annotationProcessors><annotationProcessor>gen.GenProc</annotationProcessor></annotationProcessors>')"
SUM='    int sum(int[] values) {\n        int total = 0;\n        for (int v : values) {\n            total += v;\n        }\n        return total;\n    }\n'
mkdir -p "$R/a/src/main/java/a" "$R/a/src/test/java/a" "$R/b/src/main/java/b" "$R/m/src/main/java/m" "$R/dp/src/main/java/one" "$R/dp/src/main/java/two" "$R/x/src/main/java/x"
printf "package a;\n\npublic class Lib {\n$SUM    int add(int[] xs) {\n        int acc = 0;\n        for (int x : xs) {\n            acc += x;\n        }\n        return acc;\n    }\n}\n" > "$R/a/src/main/java/a/Lib.java"
printf 'package a;\n\npublic class Native {\n    public static native long open(String path, int[] flags);\n}\n' > "$R/a/src/main/java/a/Native.java"
printf "package a;\n\nclass LibCheck {\n$SUM}\n" > "$R/a/src/test/java/a/LibCheck.java"
printf "package b;\n\npublic class Use {\n$SUM    int twice(a.Lib lib, int[] v) {\n        return 2 * v.length + lib.hashCode();\n    }\n}\n" > "$R/b/src/main/java/b/Use.java"
for m in c d; do
    mkdir -p "$R/$m/src/main/java/$m"
    printf 'package %s;\n\n@lombok.Getter\npublic class Bean {\n    private int size = 3;\n}\n' "$m" > "$R/$m/src/main/java/$m/Bean.java"
    printf 'package %s;\n\nclass UseBean {\n    int size() {\n        return new Bean().getSize();\n    }\n}\n' "$m" > "$R/$m/src/main/java/$m/UseBean.java"
done
printf 'module m {\n    exports m;\n}\n' > "$R/m/src/main/java/module-info.java"
printf "package m;\n\npublic class Mod {\n$SUM}\n" > "$R/m/src/main/java/m/Mod.java"
printf "class UtilOne {\n$SUM}\n" > "$R/dp/src/main/java/one/Util.java"
printf "class UtilTwo {\n    int twice(int v) {\n        return 2 * v;\n    }\n}\n" > "$R/dp/src/main/java/two/Util.java"
printf "package x;\n\nclass X {\n$SUM}\n" > "$R/x/src/main/java/x/X.java"
printf "package x;\n\nclass Y {\n$SUM}\n" > "$R/x/src/main/java/x/Y.java"
mkdir -p "$R/proc/src/main/java/gen" "$R/proc/src/main/resources/META-INF/services" "$R/gu/src/main/java/p" "$R/nm/src/main/java/p"
printf 'package gen;\n\n@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)\npublic @interface Gen { }\n' > "$R/proc/src/main/java/gen/Gen.java"
cat > "$R/proc/src/main/java/gen/GenProc.java" <<'JAVA'
package gen;

import java.io.IOException;
import java.io.Writer;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;

@SupportedAnnotationTypes("gen.Gen")
public class GenProc extends AbstractProcessor {
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (Element e : round.getElementsAnnotatedWith(Gen.class)) {
            String pkg = ((PackageElement) e.getEnclosingElement()).getQualifiedName().toString();
            String name = e.getSimpleName() + "Gen";
            try (Writer w = processingEnv.getFiler().createSourceFile(pkg + "." + name, e).openWriter()) {
                w.write("package " + pkg + ";\n\npublic class " + name + " {\n    public int generatedSum(int[] values) {\n"
                        + "        int total = 0;\n        for (int v : values) {\n            total += v;\n        }\n"
                        + "        return total;\n    }\n}\n");
            } catch (IOException x) {
                throw new IllegalStateException(x);
            }
        }
        return true;
    }
}
JAVA
printf 'gen.GenProc\n' > "$R/proc/src/main/resources/META-INF/services/javax.annotation.processing.Processor"
for m in gu nm; do
    printf "package p;\n\n@gen.Gen\nclass A {\n$SUM}\n" > "$R/$m/src/main/java/p/A.java"
    printf 'package p;\n\nclass B {\n    int use() {\n        return new AGen().generatedSum(new int[] {1});\n    }\n}\n' > "$R/$m/src/main/java/p/B.java"
done
mvnj() { # mvnj <java-home> <dir> <log> <args...>: Maven with the extension in <dir>
    local jh=$1 dir=$2 log=$3; shift 3
    (cd "$dir" && JAVA_HOME="$jh" MAVEN_OPTS="-Dmaven.ext.class.path=$EXT" "$MVN" -o -B -ntp -s "$T/settings.xml" \
        ${REPO:+-Dmaven.repo.local=$REPO} ${REPO_TAIL:+-Dmaven.repo.local.tail=$REPO_TAIL} "$@") > "$T/$log" 2>&1
}
mvnx() { local log=$1; shift; mvnj "$JH" "$R" "$log" "$@"; }
same() { # same <module> <src dir> <table dir> [java-home]: the inline tables equal the standalone scan
    rm -rf "$T/cli"; "${4:-$JH}/bin/java" -cp "$PLUGIN" org.apache.maven.plugins.clustering.CodeClustering cluster "$T/cli" "$R/$1/$2" > /dev/null || return 1
    for t in ATOM_HASHES.tsv CLUSTERS.tsv PLANE_SUMMARY.tsv FILE_PAIRS.tsv FILE_CLUSTERS.tsv; do
        cmp -s "$R/$1/$3/$t" "$T/cli/$t" || { echo "  differs: $1/$3/$t"; return 1; }
    done
}
tables() { find "$R" -path '*/target/code-clustering*' -name ATOM_HASHES.tsv | wc -l | tr -d ' '; }
generated() { # generated <module> [java-home]: main tables equal the scan of src/main/java, generated ones of the generated dir
    same "$1" src/main/java "$I" "${2:-}" && same "$1" target/generated-sources/annotations "$I/generated" "${2:-}" \
        && grep -q $'^p/AGen.java\tTYPE' "$R/$1/$I/generated/ATOM_HASHES.tsv"
}

mvnx compile.log -Dclustering.pluginJar="$PLUGIN" compile || fail "compile: build not green: $(grep ERROR "$T/compile.log" | head -3)"
grep -q 'Inline code clustering: javac plugin CodeClustering from' "$T/compile.log" || fail "compile: no wiring line"
grep -q -- '--- .*code-clustering' "$T/compile.log" && fail "compile: a code-clustering goal ran"
grep -q 'Inline code clustering skipped' "$T/compile.log" && fail "compile: an execution skipped: $(grep 'Inline code clustering skipped' "$T/compile.log" | head -2)"
for m in a b c d m dp x; do same $m src/main/java $I || fail "compile: $m tables differ from the scan"; done
pass "compile: 7 modules (modular, default-package files in two directories), tables identical to the scan, no goal"
grep -q $'a/Native.java\tNative.open(String,int\[\])\ta/Native\topen\tLjava/lang/String;\[I' "$R/a/$I/JNI.tsv" || fail "compile: JNI row"
pass "compile: JNI.tsv row with binary name and descriptor; b green with -Xlint:all -Werror"
[ -f "$R/c/target/classes/c/UseBean.class" ] && [ -f "$R/d/target/classes/d/UseBean.class" ] || fail "processors: Lombok did not run"
grep -q $'^c/Bean.java\tTYPE' "$R/c/$I/ATOM_HASHES.tsv" || fail "processors: no c atoms"
pass "processors: Lombok ran from the class path (d) and from annotationProcessorPaths (c)"
generated gu || fail "processors: class-path processor (gu) tables"
generated nm || fail "processors: named processor (nm) tables"
pass "processors: named and class-path processors ran; generated sources in inline/generated, both identical to their scans"
mvnx test.log -Dclustering.pluginJar="$PLUGIN" test-compile || fail "test: build not green"
same a src/test/java $I/test || fail "test: test tables differ from the scan"
pass "test: target/code-clustering/inline/test identical to the scan of src/test/java"
printf "package a;\n\npublic class Lib {\n$SUM    int product(int[] xs) {\n        int acc = 1;\n        for (int x : xs) {\n            acc *= x;\n        }\n        return acc;\n    }\n}\n" > "$R/a/src/main/java/a/Lib.java"
mvnx again.log -Dclustering.pluginJar="$PLUGIN" compile || fail "incremental: build not green"
grep -q $'Lib.product(int\[\])' "$R/a/$I/ATOM_HASHES.tsv" && same a src/main/java $I || fail "incremental: changed source not in the tables"
pass "incremental: changed source in the tables, identical to a fresh scan"
sed -i 's#<artifactId>x</artifactId>#<artifactId>x</artifactId><build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><configuration><excludes><exclude>**/Y.java</exclude></excludes></configuration></plugin></plugins></build>#' "$R/x/pom.xml"
mvnx excl.log -Dclustering.pluginJar="$PLUGIN" -pl x compile || fail "incremental: excludes build not green"
grep -q $'^x/X.java\t' "$R/x/$I/ATOM_SOURCES.tsv" || fail "incremental: X.java not in the tables"
grep -q 'Y.java' "$R/x/$I/ATOM_SOURCES.tsv" "$R/x/$I/ATOM_HASHES.tsv" && fail "incremental: excluded Y.java still in the tables"
pass "incremental: a newly excluded source drops out of the tables (full compilation)"
mvnx clean-off.log -Dclustering.pluginJar="$PLUGIN" -Dclustering.inline=false clean compile || fail "off: build not green"
[ "$(tables)" = 0 ] || fail "off: tables written with clustering.inline=false"
grep -q 'Inline code clustering' "$T/clean-off.log" && fail "off: wiring logged"
mvnx upper-off.log -Dclustering.pluginJar="$PLUGIN" -Dclustering.inline=FALSE clean compile || fail "off: FALSE build not green"
[ "$(tables)" = 0 ] || fail "off: tables written with clustering.inline=FALSE"
pass "off: -Dclustering.inline=false and FALSE write nothing"
sed -i 's#<artifactId>b</artifactId>#<artifactId>b</artifactId><properties><clustering.inline>false</clustering.inline></properties>#' "$R/b/pom.xml"
mvnx pom-off.log -Dclustering.pluginJar="$PLUGIN" clean compile || fail "pom off: build not green"
[ -f "$R/a/$I/ATOM_HASHES.tsv" ] && [ ! -e "$R/b/target/code-clustering" ] || fail "pom off: b not off or a off"
pass "off: POM property clustering.inline=false turns module b off"
mvnx clean.log clean || fail "safe: clean"
mkdir -p "$R/a/target/code-clustering" && printf 'a file\n' > "$R/a/$I"
mvnx safe.log -Dclustering.pluginJar="$PLUGIN" -pl a compile || fail "safe: an unwritable output failed the build"
grep -q 'code clustering: COMPILATION not recorded' "$T/safe.log" || fail "safe: no warning"
pass "safe: unwritable output, build green, warning logged"
mvnx missing.log -Dclustering.pluginVersion=0.0.0-missing -pl a clean compile || fail "safe: missing plugin failed the build"
grep -q 'Inline code clustering off: org.apache.maven.plugins:maven-code-clustering-plugin:0.0.0-missing is not in the local repository' "$T/missing.log" || fail "safe: missing plugin not logged"
pass "safe: missing plugin jar logged, build green"
mkdir -p "$T/noreg" && (cd "$T/noreg" && "$JH/bin/jar" xf "$PLUGIN") && rm "$T/noreg/META-INF/services/com.sun.source.util.Plugin" \
    && "$JH/bin/jar" cf "$T/noreg.jar" -C "$T/noreg" . || fail "safe: cannot make a jar without the registration"
mvnx noreg.log -Dclustering.pluginJar="$T/noreg.jar" clean compile || fail "safe: a jar without the javac registration failed the build: $(grep ERROR "$T/noreg.log" | head -2)"
grep -q 'Inline code clustering off: .*noreg.jar has no javac plugin CodeClustering' "$T/noreg.log" && [ "$(tables)" = 0 ] || fail "safe: noreg jar not logged, or tables written"
pass "safe: a given jar without the javac registration is logged and left out, build green"
if [ -n "$REPO" ]; then
    NR="$REPO/org/apache/maven/plugins/maven-code-clustering-plugin/0.0.1-noreg"; mkdir -p "$NR" && cp "$T/noreg.jar" "$NR/maven-code-clustering-plugin-0.0.1-noreg.jar"
    mvnx noreg-repo.log -Dclustering.pluginVersion=0.0.1-noreg clean compile || fail "safe: a local-repository jar without the registration failed the build"
    grep -q 'Inline code clustering off: .*0.0.1-noreg.jar has no javac plugin CodeClustering' "$T/noreg-repo.log" || fail "safe: local-repository noreg jar not logged"
    rm -rf "$NR"; pass "safe: a local-repository jar without the javac registration is logged and left out, build green"
else
    echo "SKIP safe: local-repository jar without the registration (no scratch local repository given)"
fi
mvnx repo.log clean compile || fail "local repo: build not green: $(grep ERROR "$T/repo.log" | head -3)"
same a src/main/java $I && same c src/main/java $I || fail "local repo: tables differ"
pass "local repo: plugin from the local repository, annotationProcessorPaths (c) by coordinates"
mvnx t4.log -T 4 clean compile || fail "-T 4: build not green: $(grep ERROR "$T/t4.log" | head -3)"
for m in a c d m dp; do same $m src/main/java $I || fail "-T 4: $m tables differ"; done
pass "-T 4: green, tables identical to the scan"
R2="$T/rr"; mkdir -p "$R2/stub" "$R2/c2/src/main/java/c2" "$R2/e/src/main/java/e"
printf '%s<groupId>fix</groupId><artifactId>rr</artifactId><version>1.0-SNAPSHOT</version><packaging>pom</packaging><modules><module>stub</module><module>c2</module><module>e</module></modules><properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>\n' "$P" > "$R2/pom.xml"
printf '%s<parent><groupId>fix</groupId><artifactId>rr</artifactId><version>1.0-SNAPSHOT</version></parent><groupId>org.apache.maven.plugins</groupId><artifactId>maven-code-clustering-plugin</artifactId><version>0.1.0-SNAPSHOT</version></project>\n' "$P" > "$R2/stub/pom.xml"
printf '%s<parent><groupId>fix</groupId><artifactId>rr</artifactId><version>1.0-SNAPSHOT</version></parent><artifactId>c2</artifactId>%s%s</project>\n' "$P" "$LOMBOK" "$(printf "$COMPILER" '<annotationProcessorPaths><path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.36</version></path></annotationProcessorPaths>')" > "$R2/c2/pom.xml"
printf '%s<parent><groupId>fix</groupId><artifactId>rr</artifactId><version>1.0-SNAPSHOT</version></parent><artifactId>e</artifactId></project>\n' "$P" > "$R2/e/pom.xml"
printf 'package c2;\n\n@lombok.Getter\npublic class Bean {\n    private int size = 3;\n}\n' > "$R2/c2/src/main/java/c2/Bean.java"
printf "package e;\n\nclass E {\n$SUM}\n" > "$R2/e/src/main/java/e/E.java"
for opts in "" "-T 4"; do
    mvnj "$JH" "$R2" rr.log $opts clean compile || fail "reactor ($opts): build not green: $(grep ERROR "$T/rr.log" | head -3)"
    grep -q 'Inline code clustering skipped for c2 default-compile: annotationProcessorPaths would resolve maven-code-clustering-plugin from this reactor' "$T/rr.log" || fail "reactor ($opts): c2 not skipped"
    [ -f "$R2/e/$I/ATOM_HASHES.tsv" ] && [ -f "$R2/c2/target/classes/c2/Bean.class" ] || fail "reactor ($opts): e not wired or c2 not compiled"
done
pass "reactor: building maven-code-clustering-plugin, annotationProcessorPaths left alone (logged), e wired; also -T 4"
if [ -n "${JDK23_HOME:-}" ]; then
    mvnj "$JDK23_HOME" "$R" j23.log -X -Dclustering.pluginJar="$PLUGIN" -pl proc,gu,nm clean compile || fail "java 23+: build not green: $(grep ERROR "$T/j23.log" | head -3)"
    grep -- '-processor gen.GenProc' "$T/j23.log" | grep -q -- '-proc:none' && fail "java 23+: -proc:none added beside a named processor"
    generated gu "$JDK23_HOME" && generated nm "$JDK23_HOME" || fail "java 23+: generated tables"
    pass "java 23+ ($("$JDK23_HOME/bin/java" -version 2>&1 | head -1)): named and class-path processors ran, no -proc:none beside -processor"
else
    echo "SKIP java 23+: JDK23_HOME not set"
fi
echo "INLINE_CLUSTERING_TEST PASS"
