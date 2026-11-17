#!/bin/sh
# Licensed to the Apache Software Foundation (ASF) under the Apache License, Version 2.0.
# Offline contract check without Maven: compiles the engine (sibling m3-polyglot-maven-plugin, mojos excluded) and the
# adapters with --release 17 -Werror, then runs the contract suite. With M2_REPO pointing at a local Maven repository
# that holds LSP4J 0.23.1 and gson 2.11.0, the LSP collector is compiled too and the real-run suite becomes available:
#   scripts/verify.sh [<scratch-root-for-real-runs>]
# Real runs read tool locations from -D properties in the java @argfile named by M3_REAL_RUN_ARGFILE (quotes allowed
# for paths with spaces; see ToolAdaptersRealRunTestMain).
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ENGINE="$ROOT/../m3-polyglot-maven-plugin"
DEST="$ROOT/target/offline-contract"
# ':' everywhere: Git Bash (MSYS) converts POSIX path lists to Windows ones for java.exe; -D values it does not.
SEP=:
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -wp "$1"; else printf '%s' "$1"; fi; }
rm -rf "$DEST" && mkdir -p "$DEST/engine" "$DEST/classes" "$DEST/test-classes"
compile() { javac --release 17 -encoding UTF-8 -Xlint:all -Werror "$@"; }
compile -d "$DEST/engine" $(ls "$ENGINE"/src/main/java/io/synexia/maven/polyglot/*.java | grep -v 'Mojo\.java$')
cp -r "$ENGINE/src/main/resources/META-INF" "$DEST/engine/"
M2="${M2_REPO:-$HOME/.m2/repository}"
LSP4J="$M2/org/eclipse/lsp4j/org.eclipse.lsp4j/0.23.1/org.eclipse.lsp4j-0.23.1.jar"
JSONRPC="$M2/org/eclipse/lsp4j/org.eclipse.lsp4j.jsonrpc/0.23.1/org.eclipse.lsp4j.jsonrpc-0.23.1.jar"
GSON="$M2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar"
SOURCES=$(ls "$ROOT"/src/main/java/org/apache/maven/plugins/polyglot/adapters/*.java)
if [ -f "$LSP4J" ] && [ -f "$JSONRPC" ] && [ -f "$GSON" ]; then
  LSP_CP="$LSP4J$SEP$JSONRPC$SEP$GSON"
else
  echo "NOT RUN: LSP collector compile (LSP4J 0.23.1 / gson 2.11.0 not under $M2)"
  LSP_CP=""
  SOURCES=$(echo "$SOURCES" | grep -v 'LspDiagnosticsMain\.java$')
fi
compile -cp "$DEST/engine$SEP$LSP_CP" -d "$DEST/classes" $SOURCES
cp -r "$ROOT/src/main/resources/META-INF" "$DEST/classes/"
compile -cp "$DEST/engine$SEP$DEST/classes$SEP$LSP_CP" -d "$DEST/test-classes" \
  "$ROOT"/src/test/java/org/apache/maven/plugins/polyglot/adapters/*.java
CP="$DEST/engine$SEP$DEST/classes$SEP$DEST/test-classes"
java -cp "$CP" org.apache.maven.plugins.polyglot.adapters.ToolAdaptersContractTestMain
if [ $# -ge 1 ]; then
  if [ -z "$LSP_CP" ]; then echo "NOT RUN: real runs need the LSP4J jars for the linter case"; exit 0; fi
  java ${M3_REAL_RUN_ARGFILE:+"@$M3_REAL_RUN_ARGFILE"} -Dm3.lsp4j.path="$(native "$LSP_CP")" -cp "$CP$SEP$LSP_CP" \
    org.apache.maven.plugins.polyglot.adapters.ToolAdaptersRealRunTestMain "$1"
fi
