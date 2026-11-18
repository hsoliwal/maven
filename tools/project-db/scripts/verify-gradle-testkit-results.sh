#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Run the Java 17 secure JUnit admission parser. Original Maven CI seals
# this source file in the SHA-256 verified Gradle consumer artifact.
set -eu
if [ "$#" -ne 1 ]; then
    echo 'usage: verify-gradle-testkit-results.sh TEST_RESULT_DIRECTORY' >&2
    exit 2
fi
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CLASSES=$(mktemp -d)
trap 'rm -rf "$CLASSES"' 0
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d "$CLASSES" \
    "$SCRIPT_DIR/M3GradleTestKitReceiptMain.java"
java -cp "$CLASSES" M3GradleTestKitReceiptMain "$1"
