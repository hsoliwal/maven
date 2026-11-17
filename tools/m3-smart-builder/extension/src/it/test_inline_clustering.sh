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
#                holds the native method; module b compiles with -Xlint:all -Werror; a modular module (module-info),
#                default-package files of the same name in two directories, a package named build (a) and two
#                source roots (mr, the scan of both) are atomized too
#   processors   Lombok still runs from the class path (d) and from annotationProcessorPaths (c); a processor named
#                in annotationProcessors (nm) and one found on the class path (gu) still run; generated sources go
#                to inline/generated, identical to the scan of target/generated-sources/annotations; a processor
#                path in the compilerArguments map (cm) is left alone, logged, and its processor still runs
#   test         mvn test-compile writes target/code-clustering/inline/test for the test sources
#   incremental  a changed source after a second compile is in the tables; a source newly excluded drops out; with
#                useIncrementalCompilation=false (pm) a deleted source drops out although javac does not run
#   off          -Dclustering.inline=false, FALSE, 0, no, off or empty writes nothing; a POM property turns one
#                module off
#   safe         an output path that is a file leaves the build green with a warning; a missing plugin jar, a jar
#                without the javac registration (given, or in the local repository), a jar compiled for a newer Java
#                and a jar path holding the path separator are logged and left out; a jar missing a class leaves the
#                build green with one warning; build green in every case
#   local repo   without clustering.pluginJar the plugin comes from the local repository
#   reactor      a reactor that builds maven-code-clustering-plugin: annotationProcessorPaths are left alone
#                (logged), other modules are wired; also with -T 4
#   toolchain    a JDK toolchain stored in the build context by a plugin other than maven-toolchains-plugin leaves
#                the execution alone (needs a scratch local repository: the plugin is built and installed there)
#   heap         sources too large for this compilation's share of -Xmx are left alone, logged, build green
#   java 23+     with JDK23_HOME set (a JDK 23 or newer): named and class-path processors still run, no -proc:none
#                where processors are named
#   maven 4      with MVN4 set (a Maven 4 mvn): maven-compiler-plugin 4.0.0-beta-5 is left alone, logged, and
#                Lombok on its class path still runs
# Usage: test_inline_clustering.sh <extension.jar> <plugin.jar> <java-home> [mvn] [local-repository]
# With a local repository, REPO_TAIL=<repository> chains another one behind it (maven.repo.local.tail), so the plugin
# can be installed in a scratch repository while everything else comes from the usual one. Needs
# maven-compiler-plugin, maven-resources-plugin, maven-clean-plugin and Lombok 1.18.36 in the local repository
# (maven-plugin-plugin 3.15.2 and maven-core 3.9.9 for the toolchain step, maven-compiler-plugin 4.0.0-beta-5 for
# the Maven 4 step). With KEEP_LOGS=<dir> the Maven logs are copied there.
set -uo pipefail
EXT=${1:?extension jar}; PLUGIN=${2:?plugin jar}; JH=${3:?java home}; MVN=${4:-mvn}; REPO=${5:-}
T=$(mktemp -d); trap '[ -n "${KEEP_LOGS:-}" ] && mkdir -p "$KEEP_LOGS" && cp "$T"/*.log "$KEEP_LOGS"/; rm -rf "$T"' EXIT
fail() { echo "INLINE_CLUSTERING_TEST FAIL $*"; exit 1; }
pass() { echo "PASS $*"; }
printf '<settings/>\n' > "$T/settings.xml"
R="$T/two  spaces/r"; P='<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
I=target/code-clustering/inline
PARENT='<parent><groupId>fix</groupId><artifactId>root</artifactId><version>1.0-SNAPSHOT</version></parent>'
PROPS='<properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>'
LOMBOK='<dependencies><dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.36</version><scope>provided</scope></dependency></dependencies>'
PROC='<dependencies><dependency><groupId>fix</groupId><artifactId>proc</artifactId><version>1.0-SNAPSHOT</version><scope>provided</scope></dependency></dependencies>'
AFTER_PROC='<dependencies><dependency><groupId>fix</groupId><artifactId>proc</artifactId><version>1.0-SNAPSHOT</version><scope>test</scope></dependency></dependencies>'
COMPILER='<build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><configuration>%s</configuration></plugin></plugins></build>'
TABLES="ATOM_HASHES.tsv CLUSTERS.tsv PLANE_SUMMARY.tsv FILE_PAIRS.tsv FILE_CLUSTERS.tsv"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';' ;; *) SEP=':' ;; esac
module() { # module <name> <extra POM text>: a jar module of the reactor
    mkdir -p "$R/$1/src/main/java"; printf '%s%s<artifactId>%s</artifactId>%s</project>\n' "$P" "$PARENT" "$1" "$2" > "$R/$1/pom.xml"
}
mkdir -p "$R"
printf '%s<groupId>fix</groupId><artifactId>root</artifactId><version>1.0-SNAPSHOT</version><packaging>pom</packaging><modules><module>a</module><module>b</module><module>c</module><module>d</module><module>m</module><module>dp</module><module>x</module><module>proc</module><module>gu</module><module>nm</module><module>cm</module><module>pm</module><module>mr</module></modules>%s</project>\n' "$P" "$PROPS" > "$R/pom.xml"
module a ''
module b "<dependencies><dependency><groupId>fix</groupId><artifactId>a</artifactId><version>1.0-SNAPSHOT</version></dependency></dependencies>$(printf "$COMPILER" '<compilerArgs><arg>-Xlint:all</arg><arg>-Werror</arg></compilerArgs>')"
module c "$LOMBOK$(printf "$COMPILER" '<annotationProcessorPaths><path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.36</version></path></annotationProcessorPaths>')"
module d "$LOMBOK"
module m ''; module dp ''; module x ''
module proc "$(printf "$COMPILER" '<proc>none</proc>')"
module gu "$PROC$(printf "$COMPILER" '<proc>full</proc>')"
module nm "$PROC$(printf "$COMPILER" '<annotationProcessors><annotationProcessor>gen.GenProc</annotationProcessor></annotationProcessors>')"
module cm "$AFTER_PROC$(printf "$COMPILER" '<compilerArguments><processorpath>${project.basedir}/../proc/target/classes</processorpath></compilerArguments><annotationProcessors><annotationProcessor>gen.AllProc</annotationProcessor></annotationProcessors>')"
module pm "$(printf "$COMPILER" '<useIncrementalCompilation>false</useIncrementalCompilation>')"
module mr "$(printf "$COMPILER" '<compileSourceRoots><compileSourceRoot>${project.basedir}/src/main/java</compileSourceRoot><compileSourceRoot>${project.basedir}/src/extra/java</compileSourceRoot></compileSourceRoots>')"
SUM='    int sum(int[] values) {\n        int total = 0;\n        for (int v : values) {\n            total += v;\n        }\n        return total;\n    }\n'
mkdir -p "$R/a/src/main/java/a/build" "$R/a/src/test/java/a" "$R/b/src/main/java/b" "$R/m/src/main/java/m" "$R/dp/src/main/java/one" "$R/dp/src/main/java/two" "$R/x/src/main/java/x"
printf "package a;\n\npublic class Lib {\n$SUM    int add(int[] xs) {\n        int acc = 0;\n        for (int x : xs) {\n            acc += x;\n        }\n        return acc;\n    }\n}\n" > "$R/a/src/main/java/a/Lib.java"
printf 'package a;\n\npublic class Native {\n    public static native long open(String path, int[] flags);\n}\n' > "$R/a/src/main/java/a/Native.java"
printf "package a.build;\n\npublic class Gen {\n$SUM}\n" > "$R/a/src/main/java/a/build/Gen.java"
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
mkdir -p "$R/proc/src/main/java/gen" "$R/proc/src/main/resources/META-INF/services" "$R/gu/src/main/java/p" "$R/nm/src/main/java/p" "$R/cm/src/main/java/p" "$R/pm/src/main/java/pm" "$R/mr/src/main/java/z" "$R/mr/src/extra/java/a"
printf 'package gen;\n\n@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)\npublic @interface Gen { }\n' > "$R/proc/src/main/java/gen/Gen.java"
GENERATED='"package " + pkg + ";\n\npublic class " + name + " {\n    public int generatedSum(int[] values) {\n"
                        + "        int total = 0;\n        for (int v : values) {\n            total += v;\n        }\n"
                        + "        return total;\n    }\n}\n"'
cat > "$R/proc/src/main/java/gen/GenProc.java" <<JAVA
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
                w.write($GENERATED);
            } catch (IOException x) {
                throw new IllegalStateException(x);
            }
        }
        return true;
    }
}
JAVA
cat > "$R/proc/src/main/java/gen/AllProc.java" <<JAVA
package gen;

import java.io.IOException;
import java.io.Writer;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;

/** Not registered: runs only when named. Writes p.AGen in the first round. */
@SupportedAnnotationTypes("*")
public class AllProc extends AbstractProcessor {
    private boolean done;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (done) return false;
        done = true;
        String pkg = "p";
        String name = "AGen";
        try (Writer w = processingEnv.getFiler().createSourceFile(pkg + "." + name).openWriter()) {
            w.write($GENERATED);
        } catch (IOException x) {
            throw new IllegalStateException(x);
        }
        return false;
    }
}
JAVA
printf 'gen.GenProc\n' > "$R/proc/src/main/resources/META-INF/services/javax.annotation.processing.Processor"
for m in gu nm; do
    printf "package p;\n\n@gen.Gen\nclass A {\n$SUM}\n" > "$R/$m/src/main/java/p/A.java"
done
for m in gu nm cm; do
    printf 'package p;\n\nclass B {\n    int use() {\n        return new AGen().generatedSum(new int[] {1});\n    }\n}\n' > "$R/$m/src/main/java/p/B.java"
done
printf "package p;\n\nclass A {\n$SUM}\n" > "$R/cm/src/main/java/p/A.java"
printf "package pm;\n\nclass X {\n$SUM}\n" > "$R/pm/src/main/java/pm/X.java"
printf "package pm;\n\nclass Y {\n$SUM}\n" > "$R/pm/src/main/java/pm/Y.java"
printf "package z;\n\nclass Z {\n$SUM}\n" > "$R/mr/src/main/java/z/Z.java"
printf "package a;\n\nclass A {\n$SUM}\n" > "$R/mr/src/extra/java/a/A.java"
mvnj() { # mvnj <java-home> <dir> <log> <args...>: Maven ($MVN, or MVNX) with the extension in <dir>; MOPTS adds JVM options
    local jh=$1 dir=$2 log=$3; shift 3
    (cd "$dir" && JAVA_HOME="$jh" MAVEN_OPTS="-Dmaven.ext.class.path=$EXT ${MOPTS:-}" "${MVNX:-$MVN}" -o -B -ntp -s "$T/settings.xml" \
        ${REPO:+-Dmaven.repo.local=$REPO} ${REPO_TAIL:+-Dmaven.repo.local.tail=$REPO_TAIL} "$@") > "$T/$log" 2>&1
}
mvnx() { local log=$1; shift; mvnj "$JH" "$R" "$log" "$@"; }
scanned() { # scanned <java-home> <module> <table dir> <src dir>...: the inline tables equal the standalone scan of the roots
    local jh=$1 m=$2 tdir=$3; shift 3
    local roots=(); for s in "$@"; do roots+=("$R/$m/$s"); done
    rm -rf "$T/cli"; "$jh/bin/java" -cp "$PLUGIN" org.apache.maven.plugins.clustering.CodeClustering cluster "$T/cli" "${roots[@]}" > /dev/null || return 1
    for t in $TABLES; do cmp -s "$R/$m/$tdir/$t" "$T/cli/$t" || { echo "  differs: $m/$tdir/$t"; return 1; }; done
}
same() { scanned "${4:-$JH}" "$1" "$3" "$2"; } # same <module> <src dir> <table dir> [java-home]
tables() { find "$R" -path '*/target/code-clustering*' -name ATOM_HASHES.tsv | wc -l | tr -d ' '; }
generated() { # generated <module> [java-home]: main tables equal the scan of src/main/java, generated ones of the generated dir
    same "$1" src/main/java "$I" "${2:-}" && same "$1" target/generated-sources/annotations "$I/generated" "${2:-}" \
        && grep -q $'^p/AGen.java\tTYPE' "$R/$1/$I/generated/ATOM_HASHES.tsv"
}
variant() { # variant <name> <class file to drop | -> [major]: a copy of the plugin jar at $T/<name>.jar
    rm -rf "$T/v" && mkdir -p "$T/v" && (cd "$T/v" && "$JH/bin/jar" xf "$PLUGIN") || return 1
    [ "$2" = - ] || rm "$T/v/org/apache/maven/plugins/clustering/$2" || return 1
    [ -z "${3:-}" ] || printf "\\x00\\x$(printf '%02x' "$3")" | dd of="$T/v/org/apache/maven/plugins/clustering/InlineClustering.class" bs=1 seek=6 count=2 conv=notrunc 2> /dev/null
    "$JH/bin/jar" cf "$T/$1.jar" -C "$T/v" .
}

mvnx compile.log -Dclustering.pluginJar="$PLUGIN" compile || fail "compile: build not green: $(grep ERROR "$T/compile.log" | head -3)"
grep -q 'Inline code clustering: javac plugin CodeClustering from' "$T/compile.log" || fail "compile: no wiring line"
grep -q -- '--- .*code-clustering' "$T/compile.log" && fail "compile: a code-clustering goal ran"
grep 'Inline code clustering skipped' "$T/compile.log" | grep -v 'skipped for cm default-compile' | grep -q . && fail "compile: an execution skipped: $(grep 'Inline code clustering skipped' "$T/compile.log" | head -2)"
for m in a b c d m dp x pm; do same $m src/main/java $I || fail "compile: $m tables differ from the scan"; done
scanned "$JH" mr $I src/main/java src/extra/java || fail "compile: mr tables differ from the scan of both roots"
pass "compile: 9 modules (modular, default-package files in two directories, a package named build, two roots), tables identical to the scan, no goal"
grep -q $'a/Native.java\tNative.open(String,int\[\])\ta/Native\topen\tLjava/lang/String;\[I' "$R/a/$I/JNI.tsv" || fail "compile: JNI row"
pass "compile: JNI.tsv row with binary name and descriptor; b green with -Xlint:all -Werror"
[ -f "$R/c/target/classes/c/UseBean.class" ] && [ -f "$R/d/target/classes/d/UseBean.class" ] || fail "processors: Lombok did not run"
grep -q $'^c/Bean.java\tTYPE' "$R/c/$I/ATOM_HASHES.tsv" || fail "processors: no c atoms"
pass "processors: Lombok ran from the class path (d) and from annotationProcessorPaths (c)"
generated gu || fail "processors: class-path processor (gu) tables"
generated nm || fail "processors: named processor (nm) tables"
pass "processors: named and class-path processors ran; generated sources in inline/generated, both identical to their scans"
grep -q 'Inline code clustering skipped for cm default-compile: processor path set in the compiler arguments' "$T/compile.log" && [ -f "$R/cm/target/classes/p/B.class" ] \
    || fail "processors: cm (processor path in compilerArguments) not left alone, or its processor did not run"
pass "processors: a processor path in the compilerArguments map is left alone (logged), its named processor ran"
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
rm "$R/pm/src/main/java/pm/Y.java"
mvnx pm.log -Dclustering.pluginJar="$PLUGIN" -pl pm compile || fail "incremental: pm build not green"
grep -q 'Nothing to compile' "$T/pm.log" || fail "incremental: pm compiled (the step needs javac not to run)"
same pm src/main/java $I && ! grep -q 'Y.java' "$R/pm/$I/ATOM_SOURCES.tsv" || fail "incremental: deleted Y.java still in pm's tables"
pass "incremental: useIncrementalCompilation=false, a deleted source drops out although javac did not run"
mvnx clean-off.log -Dclustering.pluginJar="$PLUGIN" -Dclustering.inline=false clean compile || fail "off: build not green"
[ "$(tables)" = 0 ] || fail "off: tables written with clustering.inline=false"
grep -q 'Inline code clustering' "$T/clean-off.log" && fail "off: wiring logged"
mvnx upper-off.log -Dclustering.pluginJar="$PLUGIN" -Dclustering.inline=FALSE clean compile || fail "off: FALSE build not green"
[ "$(tables)" = 0 ] || fail "off: tables written with clustering.inline=FALSE"
for v in 0 no off ''; do
    mvnx "off-$v.log" -Dclustering.pluginJar="$PLUGIN" "-Dclustering.inline=$v" -pl a clean compile || fail "off: '$v' build not green"
    [ ! -e "$R/a/target/code-clustering" ] || fail "off: clustering.inline='$v' wrote tables"
done
pass "off: -Dclustering.inline=false, FALSE, 0, no, off and empty write nothing"
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
variant newer - 99 && mvnx newer.log -Dclustering.pluginJar="$T/newer.jar" -pl a clean compile || fail "safe: a jar for a newer Java failed the build"
grep -q 'Inline code clustering off: .*newer.jar needs Java 55' "$T/newer.log" || fail "safe: a jar for a newer Java not logged"
mkdir -p "$T/semi${SEP}colon" && cp "$PLUGIN" "$T/semi${SEP}colon/plugin.jar"
mvnx sep.log -Dclustering.pluginJar="$T/semi${SEP}colon/plugin.jar" -pl a clean compile || fail "safe: a jar path with the path separator failed the build"
grep -q 'Inline code clustering off: .*plugin.jar contains the path separator' "$T/sep.log" || fail "safe: a jar path with the path separator not logged"
variant partial InlineOptions.class && mvnx partial.log -Dclustering.pluginJar="$T/partial.jar" -pl a clean compile || fail "safe: a jar without InlineOptions failed the build: $(grep -E 'ERROR|exception' "$T/partial.log" | head -2)"
grep -q 'code clustering plugin disabled: java.lang.NoClassDefFoundError' "$T/partial.log" || fail "safe: a jar without InlineOptions not logged"
variant parse PlaneTokens.class && mvnx parse.log -Dclustering.pluginJar="$T/parse.jar" -pl a clean compile || fail "safe: a jar without PlaneTokens failed the build"
[ "$(grep -c 'code clustering: .* not recorded' "$T/parse.log")" = 1 ] || fail "safe: not one warning after a LinkageError: $(grep -c 'code clustering: .* not recorded' "$T/parse.log")"
pass "safe: jars for a newer Java, on a path with '$SEP', or missing a class: logged, one warning after a LinkageError, build green"
mvnx repo.log clean compile || fail "local repo: build not green: $(grep ERROR "$T/repo.log" | head -3)"
same a src/main/java $I && same c src/main/java $I || fail "local repo: tables differ"
pass "local repo: plugin from the local repository, annotationProcessorPaths (c) by coordinates"
mvnx t4.log -T 4 clean compile || fail "-T 4: build not green: $(grep ERROR "$T/t4.log" | head -3)"
for m in a c d m dp pm; do same $m src/main/java $I || fail "-T 4: $m tables differ"; done
pass "-T 4: green, tables identical to the scan"
R2="$T/rr"; mkdir -p "$R2/stub" "$R2/c2/src/main/java/c2" "$R2/e/src/main/java/e"
printf '%s<groupId>fix</groupId><artifactId>rr</artifactId><version>1.0-SNAPSHOT</version><packaging>pom</packaging><modules><module>stub</module><module>c2</module><module>e</module></modules>%s</project>\n' "$P" "$PROPS" > "$R2/pom.xml"
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
if [ -n "$REPO" ]; then
    TC="$T/tc"; mkdir -p "$TC/tcset/src/main/java/tcset" "$TC/app/src/main/java/p"
    printf '%s<groupId>fix</groupId><artifactId>tcset-maven-plugin</artifactId><version>1.0-SNAPSHOT</version><packaging>maven-plugin</packaging>%s<dependencies><dependency><groupId>org.apache.maven</groupId><artifactId>maven-plugin-api</artifactId><version>3.9.9</version><scope>provided</scope></dependency><dependency><groupId>org.apache.maven</groupId><artifactId>maven-core</artifactId><version>3.9.9</version><scope>provided</scope></dependency><dependency><groupId>org.apache.maven.plugin-tools</groupId><artifactId>maven-plugin-annotations</artifactId><version>3.15.2</version><scope>provided</scope></dependency></dependencies><build><plugins><plugin><artifactId>maven-plugin-plugin</artifactId><version>3.15.2</version></plugin></plugins></build></project>\n' "$P" "$PROPS" > "$TC/tcset/pom.xml"
    cat > "$TC/tcset/src/main/java/tcset/SelectMojo.java" <<'JAVA'
package tcset;

import java.util.stream.Stream;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.toolchain.MisconfiguredToolchainException;
import org.apache.maven.toolchain.ToolchainManagerPrivate;
import org.apache.maven.toolchain.ToolchainPrivate;

/** Stores the first JDK toolchain in the build context, as toolchain selectors other than maven-toolchains-plugin do. */
@Mojo(name = "select", defaultPhase = LifecyclePhase.VALIDATE, threadSafe = true)
public class SelectMojo extends AbstractMojo {
    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Component
    private ToolchainManagerPrivate toolchains;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            ToolchainPrivate jdk = Stream.of(toolchains.getToolchainsForType("jdk", session)).findFirst()
                    .orElseThrow(() -> new MojoExecutionException("no jdk toolchain"));
            toolchains.storeToolchainToBuildContext(jdk, session);
            getLog().info("tcset selected " + jdk);
        } catch (MisconfiguredToolchainException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }
}
JAVA
    printf '%s<groupId>fix</groupId><artifactId>tcapp</artifactId><version>1.0-SNAPSHOT</version>%s<build><plugins><plugin><groupId>fix</groupId><artifactId>tcset-maven-plugin</artifactId><version>1.0-SNAPSHOT</version><executions><execution><goals><goal>select</goal></goals></execution></executions></plugin></plugins></build></project>\n' "$P" "$PROPS" > "$TC/app/pom.xml"
    printf "package p;\n\nclass A {\n$SUM}\n" > "$TC/app/src/main/java/p/A.java"
    JV=$("$JH/bin/java" -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"')
    printf '<toolchains><toolchain><type>jdk</type><provides><version>%s</version></provides><configuration><jdkHome>%s</jdkHome></configuration></toolchain></toolchains>\n' "$JV" "$JH" > "$T/toolchains.xml"
    mvnj "$JH" "$TC/tcset" tcset.log install || fail "toolchain: the selector plugin did not build: $(grep ERROR "$T/tcset.log" | head -3)"
    mvnj "$JH" "$TC/app" tc.log -t "$T/toolchains.xml" -Dclustering.pluginJar="$PLUGIN" compile || fail "toolchain: build not green: $(grep ERROR "$T/tc.log" | head -3)"
    grep -q 'Inline code clustering skipped for tcapp default-compile: toolchain javac' "$T/tc.log" || fail "toolchain: a build-context JDK toolchain not left alone"
    pass "toolchain: a JDK toolchain stored in the build context by another plugin leaves the execution alone (logged)"
else
    echo "SKIP toolchain: no scratch local repository given"
fi
HP="$T/hp"; mkdir -p "$HP/src/main/java/hp"
printf '%s<groupId>fix</groupId><artifactId>hp</artifactId><version>1.0-SNAPSHOT</version>%s</project>\n' "$P" "$PROPS" > "$HP/pom.xml"
{ printf 'package hp;\n\n/*\n'; awk 'BEGIN { for (i = 1; i <= 12000; i++) printf " * filler line %05d of a large source file, counted by the heap estimate\n", i }'; printf " */\npublic class Big {\n$SUM}\n"; } > "$HP/src/main/java/hp/Big.java"
MOPTS=-Xmx128m mvnj "$JH" "$HP" hp.log -T 4 -Dclustering.pluginJar="$PLUGIN" compile || fail "heap: build not green"
grep -q 'Inline code clustering skipped for hp default-compile: [0-9]* KB of Java sources need about [0-9]* MB of heap with the plugin' "$T/hp.log" || fail "heap: large sources under a tight -Xmx not left alone"
pass "heap: $(grep -o '[0-9]* KB of Java sources need about [0-9]* MB of heap with the plugin, this compilation.s share is [0-9]* MB' "$T/hp.log" | head -1): left alone, build green"
if [ -n "${JDK23_HOME:-}" ]; then
    mvnj "$JDK23_HOME" "$R" j23.log -X -Dclustering.pluginJar="$PLUGIN" -pl proc,gu,nm clean compile || fail "java 23+: build not green: $(grep ERROR "$T/j23.log" | head -3)"
    grep -- '-processor gen.GenProc' "$T/j23.log" | grep -q -- '-proc:none' && fail "java 23+: -proc:none added beside a named processor"
    generated gu "$JDK23_HOME" && generated nm "$JDK23_HOME" || fail "java 23+: generated tables"
    pass "java 23+ ($("$JDK23_HOME/bin/java" -version 2>&1 | head -1)): named and class-path processors ran, no -proc:none beside -processor"
else
    echo "SKIP java 23+: JDK23_HOME not set"
fi
if [ -n "${MVN4:-}" ]; then
    R4="$T/r4"; mkdir -p "$R4/src/main/java/q" "$R4/.mvn" # .mvn: maven-compiler-plugin 4.x needs the root directory
    printf '%s<groupId>fix</groupId><artifactId>r4</artifactId><version>1.0-SNAPSHOT</version>%s%s<build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><version>4.0.0-beta-5</version></plugin></plugins></build></project>\n' "$P" "$PROPS" "$LOMBOK" > "$R4/pom.xml"
    printf 'package q;\n\n@lombok.Getter\npublic class Bean {\n    private int size = 3;\n}\n' > "$R4/src/main/java/q/Bean.java"
    printf 'package q;\n\nclass UseBean {\n    int size() {\n        return new Bean().getSize();\n    }\n}\n' > "$R4/src/main/java/q/UseBean.java"
    MVNX="$MVN4" mvnj "$JH" "$R4" m4.log -Dclustering.pluginJar="$PLUGIN" compile || fail "maven 4: build not green: $(grep ERROR "$T/m4.log" | head -3)"
    grep -q 'Inline code clustering skipped for r4 default-compile: maven-compiler-plugin 4.0.0-beta-5' "$T/m4.log" || fail "maven 4: maven-compiler-plugin 4.x not left alone"
    pass "maven 4 ($("$MVN4" -v 2>/dev/null | head -1 | tr -d '\r')): maven-compiler-plugin 4.0.0-beta-5 left alone (logged), Lombok ran"
else
    echo "SKIP maven 4: MVN4 not set"
fi
echo "INLINE_CLUSTERING_TEST PASS"
