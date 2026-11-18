#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
"""Refuse incomplete/asymmetric triggers for the expensive exact LLVM source proof.

This verifier reads workflow text without installing native tools or PyYAML.
Only canonical literal GitHub Actions on: push/pull_request: paths: lines
are admitted; unfamiliar trigger structure fails closed.
"""

from __future__ import annotations

import argparse
import ast
import re
from pathlib import Path

REQUIRED = frozenset({
    ".github/workflows/m3-llvm-fork-source-proof.yml",
    "tools/m3-polyglot-maven-plugin/scripts/verify-pinned-llvm-fork.sh",
    "tools/m3-polyglot-maven-plugin/scripts/verify-llvm-fork-source-triggers.py",
    "tools/m3-polyglot-maven-plugin/docs/LLVM_FORK_SOURCE_PROOF.md",
    "tools/m3-polyglot-maven-plugin/pom.xml",
    "tools/m3-polyglot-maven-plugin/src/main/java/io/synexia/maven/polyglot/LlvmCompilerProvider.java",
    "tools/m3-polyglot-maven-plugin/src/main/java/io/synexia/maven/polyglot/M3ToolProvider.java",
    "tools/m3-polyglot-maven-plugin/src/main/java/io/synexia/maven/polyglot/M3ToolProviders.java",
    "tools/m3-polyglot-maven-plugin/src/main/java/io/synexia/maven/polyglot/M3ToolReuseInputs.java",
    "tools/m3-polyglot-maven-plugin/src/main/java/io/synexia/maven/polyglot/TaskRunner.java",
    "tools/m3-polyglot-maven-plugin/src/main/java/io/synexia/maven/polyglot/TaskManifest.java",
    "tools/m3-polyglot-maven-plugin/src/main/java/io/synexia/maven/polyglot/InputStamps.java",
    "tools/m3-polyglot-maven-plugin/src/main/java/io/synexia/maven/polyglot/BuildCache.java",
    "tools/m3-polyglot-maven-plugin/src/test/java/io/synexia/maven/polyglot/LlvmCompilerIntegrationTestMain.java",
    "tools/m3-polyglot-maven-plugin/src/main/resources/META-INF/services/io.synexia.maven.polyglot.M3ToolProvider",
})
MAX_WORKFLOW_BYTES = 1024 * 1024
MAX_PATHS = 128


def trigger_paths(source: str) -> dict[str, tuple[str, ...]]:
    if len(source.encode("utf-8")) > MAX_WORKFLOW_BYTES:
        raise ValueError("workflow exceeds 1 MiB budget")
    events = {"push": [], "pull_request": []}
    current: str | None = None
    paths = False
    found_on = False
    found_event: set[str] = set()
    for line in source.splitlines():
        if line == "on:":
            if found_on:
                raise ValueError("duplicate on: trigger")
            found_on = True
            current, paths = None, False
            continue
        if not found_on:
            continue
        if line and not line.startswith(" ") and not line.startswith("#"):
            break
        event = re.fullmatch(r"  (push|pull_request):", line)
        if event:
            current = event.group(1)
            if current in found_event:
                raise ValueError("duplicate event " + current)
            found_event.add(current)
            paths = False
            continue
        if re.fullmatch(r"  [a-z][a-z_]+:", line):
            current, paths = None, False
            continue
        if current is None:
            continue
        if line == "    paths-ignore:":
            raise ValueError("paths-ignore changes exact source-proof semantics")
        if line == "    paths:":
            if paths:
                raise ValueError("duplicate paths list for " + current)
            paths = True
            continue
        if line.startswith("    ") and not line.startswith("      "):
            paths = False
            continue
        if paths and line.startswith("      - "):
            atom = line[8:]
            try:
                value = ast.literal_eval(atom)
            except (SyntaxError, ValueError) as invalid:
                raise ValueError("invalid literal trigger: " + atom) from invalid
            if (not isinstance(value, str) or not value or value != value.strip()
                    or any(x in value for x in ("*", "?", "!", "\n", "\r"))):
                raise ValueError("expected a literal, non-negated source-owner path")
            if value in events[current]:
                raise ValueError("duplicate trigger " + current + ": " + value)
            events[current].append(value)
            if len(events[current]) > MAX_PATHS:
                raise ValueError("too many trigger paths")
        elif paths and line.strip() and not line.lstrip().startswith("#"):
            raise ValueError("unsupported trigger path syntax: " + line)
    if not found_on or found_event != set(events):
        raise ValueError("missing push/pull_request event")
    for event, files in events.items():
        missing = sorted(REQUIRED.difference(files))
        if missing:
            raise ValueError(event + " misses required paths: " + ", ".join(missing))
    if set(events["push"]) != set(events["pull_request"]):
        raise ValueError("source-proof push and pull_request paths differ")
    return {event: tuple(sorted(paths)) for event, paths in events.items()}


def main() -> None:
    default = Path(__file__).resolve().parents[3] / ".github/workflows/m3-llvm-fork-source-proof.yml"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("workflow", nargs="?", type=Path, default=default)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[3])
    args = parser.parse_args()
    parsed = trigger_paths(args.workflow.read_text(encoding="utf-8"))
    for path in sorted(REQUIRED):
        owner = args.root / path
        if not owner.is_file() or owner.is_symlink():
            raise ValueError("source-proof owner absent or aliased: " + path)
    print("M3-LLVM-FORK-SOURCE-PROOF-TRIGGERS-V1")
    for key in ("push", "pull_request"):
        print(f"{key}\t{len(parsed[key])}\tPASS")
    print(f"PASS: {len(REQUIRED)} mandatory owners, symmetric exact source-proof triggers")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        raise SystemExit("FAIL: " + str(error)) from error
