#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

# Compile the dependency-free controller independently of the application build.
# Java 21+ and Bash are prerequisites; no Maven lifecycle runs inside the action.
action_path=${HOOK_ACTION_PATH:-$(cd -- "$(dirname -- "$0")" && pwd)}
repository=$(cd -- "$action_path/../../.." && pwd)
sources="$repository/tools/m3-smart-builder/src/main/java/io/synexia/maven/smart"
classes=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/chrome-ai-hook.XXXXXX")
trap 'rm -rf -- "$classes"' EXIT
java_cmd=java
javac_cmd=javac
if [[ -n ${JAVA_HOME:-} ]]; then
  java_cmd="$JAVA_HOME/bin/java"
  javac_cmd="$JAVA_HOME/bin/javac"
fi
files=()
for file in "$sources"/*.java; do
  [[ $file == *Mojo.java ]] || files+=("$file")
done
"$javac_cmd" --release 21 -encoding UTF-8 -Xlint:all -Werror -d "$classes" "${files[@]}"

project=$(cd -- "${HOOK_PROJECT:-.}" && pwd)
phase=${HOOK_PHASE:?phase is required}
build_dir=${HOOK_BUILD_DIR:-.github-build}
backend=${HOOK_BACKEND:-chrome}
case "$phase" in pre-build|post-build) ;; *) printf '%s\n' 'invalid hook phase' >&2; exit 2 ;; esac
case "$project$build_dir" in *$'\n'*|*$'\r'*) printf '%s\n' 'report path contains a line break' >&2; exit 2 ;; esac
args=(--phase "$phase" --project-dir "$project" --build-dir "$build_dir" --backend "$backend"
      --timeout-seconds "${HOOK_TIMEOUT:-120}")
[[ -z ${HOOK_BUILD_OUTCOME:-} ]] || args+=(--build-outcome "$HOOK_BUILD_OUTCOME")
[[ -z ${HOOK_LOG:-} ]] || args+=(--log-file "$HOOK_LOG")
[[ -z ${HOOK_SOURCE:-} ]] || args+=(--source-root "$HOOK_SOURCE")
[[ -z ${HOOK_ATOM_PATTERN_MANIFEST:-} ]] || args+=(--atom-pattern-manifest "$HOOK_ATOM_PATTERN_MANIFEST")
args+=(--require-atom-pattern "${HOOK_REQUIRE_ATOM_PATTERN:-false}")
[[ -z ${HOOK_PREPARATION_REPORT:-} ]] || args+=(--preparation-report "$HOOK_PREPARATION_REPORT")
args+=(--preparation-required "${HOOK_PREPARATION_REQUIRED:-false}")
if [[ $backend == chrome ]]; then
  chrome=${HOOK_CHROME:-}
  if [[ -z $chrome ]]; then chrome=$(command -v google-chrome || true); fi
  args+=(--chrome-llm-jar "${HOOK_MODULE:?chrome-llm-jar is required}"
         --chrome-binary "${chrome:?Chrome executable is required}"
         --chrome-profile "${HOOK_PROFILE:?dedicated chrome-profile is required}"
         --headless "${HOOK_HEADLESS:-true}" --allow-download "${HOOK_DOWNLOAD:-false}")
elif [[ $backend != none ]]; then
  printf '%s\n' 'The composite action accepts backend chrome or none; native providers use the Java/Maven entry point.' >&2
  exit 2
fi
status=0
"$java_cmd" -cp "$classes" io.synexia.maven.smart.BuildHookMain "${args[@]}" || status=$?
if [[ $build_dir == /* ]]; then report="$build_dir/m3-smart/$phase.json";
else report="$project/$build_dir/m3-smart/$phase.json"; fi
if [[ -n ${GITHUB_OUTPUT:-} ]]; then
  printf 'hook-status=%s\n' "$status" >> "$GITHUB_OUTPUT"
  [[ ! -f $report ]] || printf 'report=%s\n' "$report" >> "$GITHUB_OUTPUT"
fi
exit "$status"
