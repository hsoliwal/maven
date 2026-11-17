#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DEST="$ROOT/target/offline-contract"
mkdir -p "$DEST"
if command -v javac >/dev/null 2>&1; then
  compile() { javac --release 17 -encoding UTF-8 -Xlint:all -Werror "$@"; }
else
  # Reduced JDK images can expose jdk.compiler but omit the launcher and ct.sym.
  # This fallback checks Java 17 syntax/bytecode; it cannot certify --release 17 APIs.
  compile() { java -m jdk.compiler/com.sun.tools.javac.Main \
    -source 17 -target 17 -encoding UTF-8 -Xlint:all,-options -Werror "$@"; }
fi
compile -d "$DEST" \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/TaskManifest.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/TaskRunner.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/InputStamps.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/BuildCache.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/ModuleStateDb.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3ModuleDbBackendProvider.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3ModuleDbRunLock.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3ModuleJdbcStorage.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3DbModuleStore.java \
  "$ROOT"/src/main/java/jdk/m3/indexdb/M3IndexDB.java \
  "$ROOT"/src/main/java/jdk/m3/indexdb/M3IndexDbArtifact.java \
  "$ROOT"/src/main/java/jdk/m3/indexdb/M3IndexDbSemanticCodec.java \
  "$ROOT"/src/main/java/jdk/m3/indexdb/M3IndexDbSemanticEdge.java \
  "$ROOT"/src/main/java/jdk/m3/indexdb/M3IndexDbSemanticFingerprint.java \
  "$ROOT"/src/main/java/jdk/m3/indexdb/M3IndexDbSemanticIndex.java \
  "$ROOT"/src/main/java/jdk/m3/indexdb/M3IndexDbSemanticKind.java \
  "$ROOT"/src/main/java/jdk/m3/indexdb/M3IndexDbSemanticNode.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/Mavenizer.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/MavenizerContractTestMain.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/EclipseProjectModel.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/EclipseModelCli.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/EclipseProjectModelContractTestMain.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/QualityTaskCommands.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3ToolCapability.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3ToolProvider.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3ToolProviders.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/JdkCompilerProvider.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/PolyglotCli.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/PolyglotCliContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/TaskEngineContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/ModuleStateDbContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/M3PortableDbContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/M3JdbcDbContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/M3DbModuleStoreContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/ModuleStateContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/BackendStateContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/BuildCacheContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/BuildCacheStateContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/QualityProviderContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/M3ToolProvidersContractTestMain.java
java -cp "$DEST" io.synexia.maven.polyglot.TaskEngineContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.ModuleStateDbContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.M3PortableDbContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.M3DbModuleStoreContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.ModuleStateContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.BackendStateContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.BuildCacheContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.BuildCacheStateContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.QualityProviderContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.MavenizerContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.EclipseProjectModelContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.PolyglotCliContractTestMain
java -cp "$DEST:$ROOT/src/main/resources:$ROOT/src/test/resources" \
  io.synexia.maven.polyglot.M3ToolProvidersContractTestMain
if [ -n "${M3_JDBC_DRIVER_CLASSPATH:-}" ]; then
  java -cp "$DEST" io.synexia.maven.polyglot.M3JdbcDbContractTestMain \
    --driver-classpath "$M3_JDBC_DRIVER_CLASSPATH" h2 sqlite
fi
