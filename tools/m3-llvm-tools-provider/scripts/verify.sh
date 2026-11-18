#!/bin/sh
# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
MAVEN=$(CDPATH= cd -- "$ROOT/../m3-polyglot-maven-plugin" && pwd)
DEST="$ROOT/target/offline-contract"
mkdir -p "$DEST"
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d "$DEST" \
  "$MAVEN"/src/main/java/io/synexia/maven/polyglot/TaskManifest.java \
  "$MAVEN"/src/main/java/io/synexia/maven/polyglot/M3ToolCapability.java \
  "$MAVEN"/src/main/java/io/synexia/maven/polyglot/M3ToolProvider.java \
  "$MAVEN"/src/main/java/io/synexia/maven/polyglot/M3ToolProviders.java \
  "$ROOT"/src/main/java/io/synexia/maven/llvm/LlvmNativeToolsProvider.java \
  "$ROOT"/src/main/java/io/synexia/maven/llvm/LlvmToolchainEvidence.java \
  "$ROOT"/src/test/java/io/synexia/maven/llvm/LlvmNativeToolsContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/llvm/LlvmToolchainEvidenceContractTestMain.java
java -cp "$DEST:$ROOT/src/main/resources" \
  io.synexia.maven.llvm.LlvmNativeToolsContractTestMain
java -cp "$DEST:$ROOT/src/main/resources" \
  io.synexia.maven.llvm.LlvmToolchainEvidenceContractTestMain
