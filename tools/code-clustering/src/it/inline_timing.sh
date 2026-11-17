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
# Compile cost with and without the javac plugin CodeClustering over the same sources, in one JVM
# (InlineTiming: warm-up, then <runs> pairs in alternating order; wall time and the compiling thread's CPU time).
# Run from the module directory after mvn test-compile; the javac measured is the one of <java-home>.
# Usage: inline_timing.sh <java-home> <runs> <source-root> [javac argument...]
# Example, the JDK's own java.util as the corpus: unzip java.base/java/util from <java-home>/lib/src.zip into
# s/java/util, then  inline_timing.sh "$JAVA_HOME" 5 s --patch-module java.base=s
set -uo pipefail
JH=${1:?java home}; RUNS=${2:?runs}; SRC=${3:?source root}; shift 3
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';' ;; *) SEP=':' ;; esac
"$JH/bin/java" -Xmx2g -cp "target/test-classes${SEP}target/classes" org.apache.maven.plugins.clustering.InlineTiming \
    "$SRC" "$RUNS" "$T" "$@"
