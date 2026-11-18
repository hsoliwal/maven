#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Source-owned Java 17 JUnit admission and refusal contracts without Gradle/Maven downloads.
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CLASSES=$(mktemp -d)
trap 'rm -rf "$CLASSES"' 0
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d "$CLASSES" \
  "$HERE/M3GradleTestKitReceiptMain.java" \
  "$HERE/M3GradleTestKitReceiptContractMain.java"
java -cp "$CLASSES" M3GradleTestKitReceiptContractMain
