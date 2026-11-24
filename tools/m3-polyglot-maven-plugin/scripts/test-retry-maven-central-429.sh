#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
# Stub sleep: deterministic test never waits or contacts the network.
cat > "$tmp/bin/sleep" <<'END'
#!/usr/bin/env bash
exit 0
END
chmod +x "$tmp/bin/sleep"
cat > "$tmp/bin/fixture" <<'END'
#!/usr/bin/env bash
set -euo pipefail
n=0
if [[ -f "$M3_RETRY_TEST_COUNT" ]]; then n=$(cat "$M3_RETRY_TEST_COUNT"); fi
n=$((n+1)); echo "$n" > "$M3_RETRY_TEST_COUNT"
case "$M3_RETRY_TEST_MODE" in
  once) if (( n == 1 )); then
    echo '[ERROR] Plugin org.apache.maven.plugins:maven-plugin-plugin or one of its dependencies could not be resolved:'
    echo '[ERROR] Could not transfer artifact from/to central (https://repo.maven.apache.org/maven2): HTTP Status: 429'
    exit 1
  fi ;;
  always) echo '[ERROR] Plugin org.apache.maven.plugins:fake or one of its dependencies could not be resolved:'
    echo '[ERROR] Could not transfer artifact from/to central (https://repo.maven.apache.org/maven2): HTTP Status: 429'
    exit 1 ;;
  compile) echo '[ERROR] COMPILATION ERROR'; exit 7 ;;
  foreign) echo '[ERROR] Plugin some:fake could not be resolved:'
    echo '[ERROR] Could not transfer artifact from/to other (https://mirror.example.test/): HTTP Status: 429'; exit 9 ;;
esac
echo 'PASS: accepted command'
END
chmod +x "$tmp/bin/fixture"
export PATH="$tmp/bin:$PATH"
export M3_RETRY_TEST_COUNT="$tmp/count"
for scenario in once always compile foreign; do
  rm -f "$tmp/count"
  export M3_RETRY_TEST_MODE="$scenario"
  status=0
  bash "$here/retry-maven-central-429.sh" "$tmp/bin/fixture" > "$tmp/log" 2>&1 || status=$?
  case "$scenario" in
    once) expected_count=2; expected_status=0 ;;
    always) expected_count=3; expected_status=1 ;;
    compile) expected_count=1; expected_status=7 ;;
    foreign) expected_count=1; expected_status=9 ;;
  esac
  [[ "$(cat "$tmp/count")" == "$expected_count" && "$status" == "$expected_status" ]] || {
    printf 'FAIL: %s got attempts=%s exit=%s expected attempts=%s exit=%s\n' "$scenario" "$(cat "$tmp/count")" "$status" "$expected_count" "$expected_status" >&2
    cat "$tmp/log" >&2
    exit 1
  }
done
printf 'PASS: bounded Maven Central 429 retry, no retry for compiler or foreign mirror failures\n'
