#!/bin/sh
# Offline JDK 21 core regression; does not claim Maven plugin descriptor verification.
set -eu
cd "$(dirname "$0")/.."
mkdir -p target/jdk-only
javac -encoding UTF-8 -Xlint:all -Werror -d target/jdk-only \
  src/main/java/io/synexia/maven/smart/Hashes.java \
  src/main/java/io/synexia/maven/smart/ArtifactManifest.java \
  src/main/java/io/synexia/maven/smart/ArtifactStore.java \
  src/main/java/io/synexia/maven/smart/TinyJson.java \
  src/main/java/io/synexia/maven/smart/PomFacts.java \
  src/main/java/io/synexia/maven/smart/PlanEngine.java \
  src/main/java/io/synexia/maven/smart/McpAdviser.java \
  src/test/java/io/synexia/maven/smart/SmartBuilderTest.java
# Compile the two Maven adapters against intentionally minimal API stubs.
# This is NOT a real Maven descriptor or Maven runtime integration test.
mkdir -p target/adapter-only
javac -encoding UTF-8 -Xlint:all -Werror -cp target/jdk-only -d target/adapter-only \
  $(find scripts/adapter-stubs -name '*.java' -print | LC_ALL=C sort) \
  src/main/java/io/synexia/maven/smart/PlanMojo.java \
  src/main/java/io/synexia/maven/smart/ProvisionMojo.java
mkdir -p target/mcp-fixture
javac -encoding UTF-8 -Xlint:all -Werror -d target/mcp-fixture \
  src/test/fixture/module-info.java \
  src/test/fixture/io/synexia/chromellm/standalone/StandaloneMain.java
jar --create --file target/mcp-fixture.jar -C target/mcp-fixture .
java -Dm3.smart.fixtureJar="$(pwd)/target/mcp-fixture.jar" -cp target/jdk-only io.synexia.maven.smart.SmartBuilderTest
