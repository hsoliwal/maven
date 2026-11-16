#!/bin/sh
# Offline JDK 21 core regression; does not claim Maven plugin descriptor verification.
set -eu
cd "$(dirname "$0")/.."
# Class path separator of the JDK in use: a Windows JDK under Git Bash wants ';'.
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=';' ;; *) sep=':' ;; esac
mkdir -p target/jdk-only
javac -encoding UTF-8 -Xlint:all -Werror -d target/jdk-only \
  src/main/java/io/synexia/maven/smart/Hashes.java \
  src/main/java/io/synexia/maven/smart/ArtifactManifest.java \
  src/main/java/io/synexia/maven/smart/ArtifactStore.java \
  src/main/java/io/synexia/maven/smart/TinyJson.java \
  src/main/java/io/synexia/maven/smart/PomFacts.java \
  src/main/java/io/synexia/maven/smart/PlanEngine.java \
  src/main/java/io/synexia/maven/smart/McpAdviser.java \
  src/main/java/io/synexia/maven/smart/BuildLogFacts.java \
  src/main/java/io/synexia/maven/smart/SourceInventory.java \
  src/main/java/io/synexia/maven/smart/AtomPatternEvidence.java \
  src/main/java/io/synexia/maven/smart/CanonicalPreparationEvidence.java \
  src/main/java/io/synexia/maven/smart/SafeBuildOutput.java \
  src/main/java/io/synexia/maven/smart/DiagnoseEngine.java \
  src/main/java/io/synexia/maven/smart/BuildHookMain.java \
  src/test/java/io/synexia/maven/smart/SmartBuilderTest.java \
  src/test/java/io/synexia/maven/smart/BuildHookTest.java \
  src/test/java/io/synexia/maven/smart/AtomPatternEvidenceTest.java \
  src/test/java/io/synexia/maven/smart/CanonicalPreparationEvidenceTestMain.java
# Canon gate: the hard run contract, compiled and tested without any Maven API.
mkdir -p target/canon-only
javac -encoding UTF-8 -Xlint:all -Werror -d target/canon-only \
  src/main/java/io/synexia/maven/smart/canon/*.java \
  src/test/java/io/synexia/maven/smart/canon/*.java
# Syntax plane: the AST copy received from its JDK owner (docs/SYNTAX_PLANE.md), JDK only. The proof programs live in
# src/test/syntax, which the Maven build does not compile: the fault agent reads the JDK's internal class-file library.
mkdir -p target/syntax-only
javac -encoding UTF-8 -Xlint:all -Werror --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED \
  -d target/syntax-only \
  $(find src/main/java/io/synexia/maven/smart/ast src/test/syntax -name '*.java' -print | LC_ALL=C sort)
printf 'Premain-Class: io.synexia.maven.smart.ast.SyntaxGrowthAgent\n' > target/syntax-agent.mf
jar --create --file target/syntax-agent.jar --manifest target/syntax-agent.mf -C target/syntax-only .
# Compile the Maven adapters against intentionally minimal API stubs.
# This is NOT a real Maven descriptor or Maven runtime integration test.
mkdir -p target/adapter-only
javac -encoding UTF-8 -Xlint:all -Werror -cp "target/jdk-only${sep}target/canon-only" -d target/adapter-only \
  $(find scripts/adapter-stubs -name '*.java' -print | LC_ALL=C sort) \
  src/main/java/io/synexia/maven/smart/PlanMojo.java \
  src/main/java/io/synexia/maven/smart/ProvisionMojo.java \
  src/main/java/io/synexia/maven/smart/DiagnoseMojo.java \
  src/main/java/io/synexia/maven/smart/BuildHookMojo.java \
  src/main/java/io/synexia/maven/smart/CanonMojo.java \
  src/test/java/io/synexia/maven/smart/SmartBuilderAdapterTest.java
mkdir -p target/mcp-fixture
javac -encoding UTF-8 -Xlint:all -Werror -d target/mcp-fixture \
  src/test/fixture/module-info.java \
  src/test/fixture/io/synexia/chromellm/standalone/StandaloneMain.java
jar --create --file target/mcp-fixture.jar -C target/mcp-fixture .
# Compiler and all test fixtures are complete before executing tests.
java -cp target/jdk-only io.synexia.maven.smart.SmartBuilderTest
java -cp target/jdk-only io.synexia.maven.smart.BuildHookTest
java -cp target/jdk-only io.synexia.maven.smart.AtomPatternEvidenceTest
java -cp target/canon-only io.synexia.maven.smart.canon.CanonGateTest
for program in ASTHierarchyContract ASTHierarchyEncodingTest ProbeGuardContract SyntaxCoordinatesContract \
  SyntaxDeltaContract SyntaxIndexContract SyntaxIndexFactsFirstContract SyntaxIndexKindPostingsContract \
  SyntaxIndexStableScatterContract SyntaxJavacContract SyntaxOriginsContract SyntaxTreeAtomicGrowthContract \
  SyntaxTreeContract FactsFirstPerformanceProof internal.NullFlowFactsProbe internal.NullFlowVariableIdsProbe \
  internal.NullFlowWorklistProbe; do
  java -cp target/syntax-only "io.synexia.maven.smart.ast.$program"
done
# The failure contract needs the agent that injects a fault before each of the ten array copies of a grow.
java --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED -javaagent:target/syntax-agent.jar \
  -cp target/syntax-only io.synexia.maven.smart.ast.SyntaxTreeAtomicFailureContract
java -cp target/jdk-only io.synexia.maven.smart.CanonicalPreparationEvidenceTestMain
java -Dm3.smart.testRoot="$(pwd)/target" -Dm3.smart.fixtureJar="$(pwd)/target/mcp-fixture.jar" -cp "target/jdk-only${sep}target/adapter-only" io.synexia.maven.smart.SmartBuilderAdapterTest
# Runtime verification uses the separate synthetic MCP subprocess; it is not a trained model.
java -Dm3.smart.fixtureJar="$(pwd)/target/mcp-fixture.jar" -cp target/jdk-only io.synexia.maven.smart.SmartBuilderTest
java -Dm3.smart.fixtureJar="$(pwd)/target/mcp-fixture.jar" -cp target/jdk-only io.synexia.maven.smart.BuildHookTest
java -Dm3.smart.fixtureJar="$(pwd)/target/mcp-fixture.jar" -cp target/jdk-only io.synexia.maven.smart.AtomPatternEvidenceTest
