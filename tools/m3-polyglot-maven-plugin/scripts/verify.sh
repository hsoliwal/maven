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
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/TaskInvalidationIndex.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/InputStamps.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/BuildCache.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/ModuleStateDb.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3ModuleDbBackendProvider.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3PortableDbRuntime.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3RocksDbBackendProvider.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3MapDbBackendProvider.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3LmdbBackendProvider.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3ModuleDbRunLock.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3ModuleJdbcStorage.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3DbModuleStore.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/IndexStore.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/IndexStores.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/LibraryIndexStore.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/PlatformIndexStore.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/DonorIndexStore.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/StateFiles.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/ModuleStateBackend.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/ImageStateBackend.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/DirectoryStateBackend.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3DirectoryVacuum.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/JdbcStateBackend.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/ProviderStateBackend.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/ModuleStateView.java \
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
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3EclipseBuilderProvider.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3EclipseBuilderRegistry.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3EclipseBuildContextWire.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3EclipseBuildHandoffWire.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3EclipseBuildReconciliation.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/M3EclipseBuildReconciliationContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/M3EclipseBuildHandoffWireContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/M3EclipseBuildContextWireContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/M3EclipseBuilderRegistryContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/EclipseProjectModelContractTestMain.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/QualityTaskCommands.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3ToolCapability.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3ToolProvider.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/M3ToolProviders.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/JdkCompilerProvider.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/LlvmCompilerProvider.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/CargoTestProvider.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/CargoTestReport.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/CargoTestOutput.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/TestSuiteReport.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/SurefireXmlWriter.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/CargoTestReportContractTestMain.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/PolyglotCli.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/PolyglotCliContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/TaskEngineContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/ModuleStateDbContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/M3PortableDbContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/M3PortableEngineConformanceTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/M3JdbcDbContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/M3DbModuleStoreContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/M3PackedReceiptContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/M3ReceiptArrayModelTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/M3DirectoryVacuumTest.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/IndexStoreContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/ModuleStateContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/BackendStateContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/BuildCacheContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/BuildCacheStateContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/QualityProviderContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/M3ToolProvidersContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/LlvmCompilerProviderContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/LlvmCompilerSelectionContractTestMain.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/LlvmRealTestsMain.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/EcjArguments.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/EcjCompilerProvider.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/EcjCompilerProviderContractTestMain.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/DotnetSteps.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/TrxReport.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/JUnitReport.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/CTestReportContractTestMain.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/SurefireReportProvider.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/SurefireReportMain.java \
  "$ROOT"/src/main/java/io/synexia/maven/polyglot/NugetPackages.java \
  "$ROOT"/src/test/java/io/synexia/maven/polyglot/DotnetReportsContractTestMain.java
java -cp "$DEST" io.synexia.maven.polyglot.TaskEngineContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.ModuleStateDbContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.M3PortableDbContractTestMain
java -cp "$DEST:$ROOT/src/main/resources" \
  io.synexia.maven.polyglot.M3PortableEngineConformanceTestMain
java -cp "$DEST" io.synexia.maven.polyglot.M3DbModuleStoreContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.M3PackedReceiptContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.M3ReceiptArrayModelTestMain
java -cp "$DEST" io.synexia.maven.polyglot.M3DirectoryVacuumTest
java -cp "$DEST" io.synexia.maven.polyglot.IndexStoreContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.ModuleStateContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.BackendStateContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.BuildCacheContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.BuildCacheStateContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.QualityProviderContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.MavenizerContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.EclipseProjectModelContractTestMain
java -cp "$DEST:$ROOT/src/main/resources:$ROOT/src/test/resources" \
  io.synexia.maven.polyglot.M3EclipseBuilderRegistryContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.M3EclipseBuildContextWireContractTestMain
java -cp "$DEST:$ROOT/src/test/resources" io.synexia.maven.polyglot.M3EclipseBuildHandoffWireContractTestMain
java -cp "$DEST:$ROOT/src/test/resources" io.synexia.maven.polyglot.M3EclipseBuildReconciliationContractTestMain
java -cp "$DEST" io.synexia.maven.polyglot.PolyglotCliContractTestMain
java -cp "$DEST:$ROOT/src/main/resources:$ROOT/src/test/resources" \
  io.synexia.maven.polyglot.M3ToolProvidersContractTestMain
java -cp "$DEST:$ROOT/src/main/resources" \
  io.synexia.maven.polyglot.LlvmCompilerSelectionContractTestMain
java -cp "$DEST:$ROOT/src/main/resources" \
  io.synexia.maven.polyglot.LlvmRealTestsMain
java -cp "$DEST:$ROOT/src/main/resources:$ROOT/src/test/resources" \
  io.synexia.maven.polyglot.CargoTestReportContractTestMain
java -cp "$DEST:$ROOT/src/main/resources:$ROOT/src/test/resources" \
  io.synexia.maven.polyglot.CTestReportContractTestMain
if [ -n "${M3_JDBC_DRIVER_CLASSPATH:-}" ]; then
  java -cp "$DEST" io.synexia.maven.polyglot.M3JdbcDbContractTestMain \
    --driver-classpath "$M3_JDBC_DRIVER_CLASSPATH" h2 sqlite
fi
# A real ECJ compile runs only when M3_POLYGLOT_ECJ_JAR names an ECJ jar; otherwise the case reports NOT_RUN.
java ${M3_POLYGLOT_ECJ_JAR:+-Dm3.polyglot.ecj.jar="$M3_POLYGLOT_ECJ_JAR"} \
  -cp "$DEST:$ROOT/src/main/resources" io.synexia.maven.polyglot.EcjCompilerProviderContractTestMain
java -cp "$DEST:$ROOT/src/main/resources:$ROOT/src/test/resources" \
  io.synexia.maven.polyglot.DotnetReportsContractTestMain
# Select an installed toolchain for native LLVM cases; absence is reported as NOT_RUN.
java "-Dm3.polyglot.llvm.test.home=${M3_POLYGLOT_LLVM_TEST_HOME:-}" \
  -cp "$DEST:$ROOT/src/main/resources:$ROOT/src/test/resources" \
  io.synexia.maven.polyglot.LlvmCompilerProviderContractTestMain
