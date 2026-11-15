#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
# Actual failed Maven compilation bracketed by the product action entry point.
# Inference uses the named protocol fixture, never a real browser/model.
set -euo pipefail
tool=$(cd -- "$(dirname -- "$0")/.." && pwd)
repository=$(cd -- "$tool/../.." && pwd)
work=$(mktemp -d "$tool/target/hook-workflow.XXXXXX")
project="$work/project with spaces"
mkdir -p "$project/src/main/java"
cat > "$project/pom.xml" <<'POM'
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion><groupId>test</groupId><artifactId>hook-failure</artifactId><version>1</version>
  <properties><maven.compiler.release>21</maven.compiler.release></properties>
  <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version></plugin></plugins></build>
</project>
POM
printf '%s\n' 'class Broken { MissingType value; }' > "$project/src/main/java/Broken.java"
printf '%s\n' 'synthetic browser path, never executable' > "$work/browser"
export HOOK_ACTION_PATH="$repository/.github/actions/chrome-ai"
export HOOK_PROJECT="$project" HOOK_BUILD_DIR=.reports HOOK_BACKEND=chrome
export HOOK_MODULE="$tool/target/mcp-fixture.jar" HOOK_CHROME="$work/browser" HOOK_PROFILE="$work/profile"
export HOOK_HEADLESS=true HOOK_DOWNLOAD=false HOOK_TIMEOUT=30 HOOK_SOURCE=src
export GITHUB_OUTPUT="$work/action-outputs"
test -f "$HOOK_MODULE"
set +e
(
  set -e
  HOOK_PHASE=pre-build bash "$HOOK_ACTION_PATH/run.sh"
  status=0
  "${HOOK_TEST_MAVEN:-mvn}" -B -ntp -f "$project/pom.xml" clean verify > "$project/build.log" 2>&1 || status=$?
  printf '%s' "$status" > "$work/maven-status"
  outcome=success
  [[ $status == 0 ]] || outcome=failure
  HOOK_PHASE=post-build HOOK_BUILD_OUTCOME="$outcome" HOOK_LOG=build.log bash "$HOOK_ACTION_PATH/run.sh"
  exit "$status"
)
workflow_status=$?
set -e
test "$workflow_status" -ne 0
test "$workflow_status" -eq "$(cat "$work/maven-status")"
java -cp "$tool/target/jdk-only" io.synexia.maven.smart.BuildHookTest --workflow "$project"
printf 'FAILED MAVEN WORKFLOW PASS: original exit=%s, both reports preserved\n' "$workflow_status"
