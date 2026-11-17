#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DEST="$ROOT/target/offline-contract"
mkdir -p "$DEST"
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d "$DEST" \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/TaskManifest.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/TaskRunner.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/InputStamps.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/QualityTaskCommands.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/PolyglotCli.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/TaskEngineContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/QualityProviderContractTestMain.java
java -cp "$DEST" io.synexia.maven.polyglot.TaskEngineContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.QualityProviderContractTestMain
