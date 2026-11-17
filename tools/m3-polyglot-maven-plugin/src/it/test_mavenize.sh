#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
# Mavenizer end to end: a repository with no Maven files (a Node app, a Go module, a Python package, a Make project
# and a Rust crate, each with a real test) is mavenized, then one mvn verify builds and tests all five with their
# own tools through Maven's phases; a broken Go test fails the build.
# Usage: test_mavenize.sh <smart-maven-extension.jar> <java-home> [mvn command]. Needs node/npm, go, python, make
# and cargo on the path, m3-polyglot-maven-plugin installed (with the mavenize goal), no network for the tools.
set -uo pipefail
EXT=${1:?extension jar}; JH=${2:?java home}; MVN=${3:-mvn}
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail() { echo "MAVENIZE_TEST FAIL $*"; exit 1; }
PLUGIN=io.synexia:m3-polyglot-maven-plugin:0.1.0-SNAPSHOT
printf '<settings/>\n' > "$T/settings.xml"
R="$T/repo"; mkdir -p "$R/apps/web" "$R/services/api" "$R/libs/text/tests" "$R/native/calc" "$R/crates/math/src"
# Node: a test script, no dependencies
printf '{\n  "name": "web",\n  "version": "1.0.0",\n  "scripts": {\n    "test": "node test.js"\n  }\n}\n' > "$R/apps/web/package.json"
printf 'const assert = require("assert");\nassert.strictEqual(1 + 1, 2);\nrequire("fs").writeFileSync("node-test.ok", "ok");\n' > "$R/apps/web/test.js"
# Go: a package and its test
printf 'module example.com/api\n\ngo 1.21\n' > "$R/services/api/go.mod"
printf 'package api\n\nfunc Add(a, b int) int { return a + b }\n' > "$R/services/api/api.go"
printf 'package api\n\nimport "testing"\n\nfunc TestAdd(t *testing.T) { if Add(1, 2) != 3 { t.Fatal("add") } }\n' > "$R/services/api/api_test.go"
# Python: a package with a unittest folder
printf '[project]\nname = "text"\nversion = "1.0"\n' > "$R/libs/text/pyproject.toml"
printf 'def shout(s):\n    return s.upper()\n' > "$R/libs/text/text.py"
printf 'import unittest, text\n\nclass T(unittest.TestCase):\n    def test_shout(self):\n        self.assertEqual(text.shout("a"), "A")\n' > "$R/libs/text/tests/test_text.py"
printf '' > "$R/libs/text/tests/__init__.py"
# Make: build and test targets without a compiler
printf 'all:\n\techo built > calc.out\ntest: all\n\techo tested > make-test.ok\n' > "$R/native/calc/Makefile"
# Rust: a library crate with a unit test and no dependencies
printf '[package]\nname = "math"\nversion = "0.1.0"\nedition = "2021"\n' > "$R/crates/math/Cargo.toml"
printf 'pub fn twice(x: i32) -> i32 { x * 2 }\n\n#[cfg(test)]\nmod tests { #[test] fn t() { assert_eq!(super::twice(2), 4); } }\n' > "$R/crates/math/src/lib.rs"

mvnx() { local dir=$1 log=$2; shift 2; (cd "$dir" && JAVA_HOME="$JH" MAVEN_OPTS="-Dmaven.ext.class.path=$EXT" "$MVN" -B -ntp -s "$T/settings.xml" "$@") > "$T/$log" 2>&1; }
# Do not load the fork extension: test the generated opt-in profile under ordinary Maven.
mvnx_stock() { local dir=$1 log=$2; shift 2; (cd "$dir" && JAVA_HOME="$JH" MAVEN_OPTS="" "$MVN" -B -ntp -s "$T/settings.xml" "$@") > "$T/$log" 2>&1; }

mvnx "$R" plan.log "$PLUGIN:mavenize" -Dm3.polyglot.dryRun=true || fail "plan: $(grep ERROR "$T/plan.log" | head -2)"
[ -f "$R/pom.xml" ] && fail "plan: a dry run wrote files"
grep -q 'PLAN crates/math/m3-build.tsv' "$T/plan.log" || fail "plan: no plan lines"
mvnx "$R" mavenize.log "$PLUGIN:mavenize" -Dm3.polyglot.mavenize.groupId=org.example || fail "mavenize: $(grep ERROR "$T/mavenize.log" | head -2)"
for f in pom.xml apps/pom.xml apps/web/pom.xml apps/web/m3-build.tsv services/api/m3-build.tsv libs/text/m3-build.tsv \
        native/calc/m3-build.tsv crates/math/m3-build.tsv; do
    [ -f "$R/$f" ] || fail "mavenize: $f not written"
done
mvnx "$R" verify.log -o -Dm3.polyglot.lifecycle=true verify || fail "verify: build not green: $(grep -E 'ERROR|FAIL' "$T/verify.log" | head -4)"
grep -q 'Polyglot lifecycle: web runs m3-build.tsv targets \[validate, test\]' "$T/verify.log" || fail "verify: web binding"
grep -q 'Polyglot lifecycle: math runs m3-build.tsv targets \[compile, test, package\]' "$T/verify.log" || fail "verify: math binding"
[ -f "$R/apps/web/node-test.ok" ] || fail "verify: the Node test did not run"
[ -f "$R/native/calc/make-test.ok" ] || fail "verify: the Make test did not run"
# Stock Maven also uses the same native task manifests through generated profile bindings.
mvnx_stock "$R" stock.log -o -Pm3-polyglot verify || fail "stock Maven: $(grep -E 'ERROR|FAIL' "$T/stock.log" | head -4)"
grep -q 'TASK go-test' "$T/stock.log" && grep -q 'TASK python-test' "$T/stock.log" && grep -q 'TASK cargo-test' "$T/stock.log" \
    || fail "stock Maven: Go, Python or Cargo were not executed"
grep -q 'TASK go-test' "$T/verify.log" && grep -q 'TASK python-test' "$T/verify.log" && grep -q 'TASK cargo-test' "$T/verify.log" \
    || fail "verify: go, python or cargo tests not run"
printf 'package api\n\nimport "testing"\n\nfunc TestAdd(t *testing.T) { if Add(1, 2) != 4 { t.Fatal("add") } }\n' > "$R/services/api/api_test.go"
mvnx "$R" broken.log -o -Dm3.polyglot.lifecycle=true verify && fail "broken: a failing Go test left the build green"
grep -q 'polyglot tool failed: go-test' "$T/broken.log" || fail "broken: not reported as the Go test"
echo "MAVENIZE_TEST PASS"
