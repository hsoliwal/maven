#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SOURCE="$HERE/src/main/java/org/apache/maven/plugins/projectdb"
TEST="$HERE/src/test/java/org/apache/maven/plugins/projectdb"
DEST="$HERE/target/gradle-source-offline"
mkdir -p "$DEST"
if command -v javac >/dev/null 2>&1; then
  compile() { javac --release 17 -encoding UTF-8 -Xlint:all -Werror "$@"; }
else
  # Reduced JDK images may not include ct.sym; this fallback does
  # not prove --release 17 API compatibility.
  compile() { java -m jdk.compiler/com.sun.tools.javac.Main \
    -source 17 -target 17 -encoding UTF-8 -Xlint:all,-options -Werror "$@"; }
fi
compile -d "$DEST" \
  "$SOURCE/Source.java" "$SOURCE/Sources.java" "$SOURCE/Modules.java" \
  "$SOURCE/Chunk.java" "$SOURCE/Text.java" "$SOURCE/SurefireParser.java" \
  "$TEST/GradleSourceDiscoveryContractMain.java" "$TEST/ModulesPruneContractMain.java"
java -cp "$DEST" org.apache.maven.plugins.projectdb.GradleSourceDiscoveryContractMain
java -cp "$DEST" org.apache.maven.plugins.projectdb.ModulesPruneContractMain
