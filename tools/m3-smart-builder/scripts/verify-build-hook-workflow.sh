#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
# Actual failed Maven compilation bracketed by the build-hook entry point (BuildHookMain, cli backend).
# Inference uses the named protocol fixture, never a real model.
set -euo pipefail
tool=$(cd -- "$(dirname -- "$0")/.." && pwd)
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
printf '%s\n' 'synthetic llama.cpp path, never executed' > "$work/llama"
model="$work/mcp-evidence-observer.gguf"
printf '%s\n' 'synthetic model' > "$model"
module="$tool/target/mcp-fixture.jar"
test -f "$module"
hook() { # hook <phase> [extra BuildHookMain options...]
    local phase=$1; shift
    java -cp "$tool/target/jdk-only" io.synexia.maven.smart.BuildHookMain --phase "$phase" \
        --project-dir "$project" --build-dir .reports --source-root src \
        --backend cli --chrome-llm-jar "$module" --llama-bin "$work/llama" --model "$model" \
        --timeout-seconds 30 --require-atom-pattern true "$@"
}
# This fixture checks structure and source binding, not semantic proof authenticity.
java -cp "$tool/target/jdk-only" io.synexia.maven.smart.AtomPatternEvidenceTest \
  --write-action-fixture "$project" src
if hook pre-build --atom-pattern-manifest absent.tsv; then
  printf '%s\n' 'Missing required evidence unexpectedly reached inference' >&2
  exit 1
fi
test ! -e "$model.invoked"
set +e
(
  set -e
  hook pre-build --atom-pattern-manifest synthetic-checkpoints.tsv
  status=0
  "${HOOK_TEST_MAVEN:-mvn}" -B -ntp -f "$project/pom.xml" clean verify > "$project/build.log" 2>&1 || status=$?
  printf '%s' "$status" > "$work/maven-status"
  outcome=success
  [[ $status == 0 ]] || outcome=failure
  hook post-build --atom-pattern-manifest synthetic-checkpoints.tsv --build-outcome "$outcome" --log-file build.log
  exit "$status"
)
workflow_status=$?
set -e
test "$workflow_status" -ne 0
test "$workflow_status" -eq "$(cat "$work/maven-status")"
java -cp "$tool/target/jdk-only" io.synexia.maven.smart.BuildHookTest --workflow "$project"
printf 'FAILED MAVEN WORKFLOW PASS: original exit=%s, both reports preserved\n' "$workflow_status"
