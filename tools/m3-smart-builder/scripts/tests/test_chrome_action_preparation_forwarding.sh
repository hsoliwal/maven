#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
repo=$(cd -- "$script_dir/../../../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT
mkdir -p "$tmp/mock-jdk/bin" "$tmp/project"
cat > "$tmp/mock-jdk/bin/javac" <<'SH'
#!/usr/bin/env bash
exit 0
SH
cat > "$tmp/mock-jdk/bin/java" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$CAPTURE_PATH"
SH
chmod +x "$tmp/mock-jdk/bin/javac" "$tmp/mock-jdk/bin/java"
export JAVA_HOME="$tmp/mock-jdk"
export RUNNER_TEMP="$tmp"
export CAPTURE_PATH="$tmp/arguments.txt"
export HOOK_ACTION_PATH="$repo/.github/actions/chrome-ai"
export HOOK_PROJECT="$tmp/project"
export HOOK_PHASE=pre-build
export HOOK_BACKEND=none
export HOOK_SOURCE=src
export HOOK_ATOM_PATTERN_MANIFEST=.reports/checkpoint.tsv
export HOOK_REQUIRE_ATOM_PATTERN=true
export HOOK_PREPARATION_REPORT=.reports/preparation.json
export HOOK_PREPARATION_REQUIRED=true
unset GITHUB_OUTPUT

has_pair() {
  local key=$1 expected=$2 i
  for ((i=0; i<${#captured[@]}-1; i++)); do
    if [[ ${captured[$i]} == "$key" && ${captured[$((i+1))]} == "$expected" ]]; then return 0; fi
  done
  printf 'Missing argument %s=%s\n' "$key" "$expected" >&2
  return 1
}
has_name() {
  local key=$1 i
  for ((i=0; i<${#captured[@]}; i++)); do
    [[ ${captured[$i]} != "$key" ]] || return 0
  done
  return 1
}
bash "$repo/.github/actions/chrome-ai/run.sh"
mapfile -t captured < "$CAPTURE_PATH"
has_pair --require-atom-pattern true
has_pair --atom-pattern-manifest .reports/checkpoint.tsv
has_pair --preparation-required true
has_pair --preparation-report .reports/preparation.json
has_pair --source-root src

unset HOOK_PREPARATION_REPORT
unset HOOK_PREPARATION_REQUIRED
unset HOOK_ATOM_PATTERN_MANIFEST
export HOOK_REQUIRE_ATOM_PATTERN=false
bash "$repo/.github/actions/chrome-ai/run.sh"
mapfile -t captured < "$CAPTURE_PATH"
has_pair --require-atom-pattern false
has_pair --preparation-required false
if has_name --preparation-report; then
  echo 'Legacy Action unexpectedly supplied a preparation report' >&2
  exit 1
fi
# Chrome-provider argument forwarding uses the same admission ordering.
export HOOK_BACKEND=chrome
export HOOK_MODULE="$tmp/chrome-provider.jar"
export HOOK_CHROME="$tmp/chrome-executable"
export HOOK_PROFILE="$tmp/chrome-profile"
export HOOK_PREPARATION_REPORT=.reports/preparation.json
export HOOK_PREPARATION_REQUIRED=true
export HOOK_ATOM_PATTERN_MANIFEST=.reports/checkpoint.tsv
export HOOK_REQUIRE_ATOM_PATTERN=true
bash "$repo/.github/actions/chrome-ai/run.sh"
mapfile -t captured < "$CAPTURE_PATH"
has_pair --backend chrome
has_pair --preparation-required true
has_pair --preparation-report .reports/preparation.json
has_pair --require-atom-pattern true
has_pair --atom-pattern-manifest .reports/checkpoint.tsv
has_pair --chrome-llm-jar "$HOOK_MODULE"
has_pair --chrome-binary "$HOOK_CHROME"
has_pair --chrome-profile "$HOOK_PROFILE"

echo 'CHROME_ACTION_PREPARATION_FORWARDING_PASS'
