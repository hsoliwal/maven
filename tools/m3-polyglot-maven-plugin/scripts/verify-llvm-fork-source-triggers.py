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
import re
from pathlib import Path

REQUIRED = frozenset({
    ".github/workflows/m3-llvm-fork-source-proof.yml",
    "tools/m3-polyglot-maven-plugin/scripts/verify-pinned-llvm-fork.sh",
    "tools/m3-polyglot-maven-plugin/scripts/verify-llvm-fork-source-triggers.py",
    "tools/m3-polyglot-maven-plugin/scripts/test_verify_llvm_fork_source_triggers.py",
    "tools/m3-polyglot-maven-plugin/docs/LLVM_SOURCE_PROOF_TRIGGER_HARDENING.md",
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
    "tools/m3-polyglot-maven-plugin/scripts/verify-pinned-llvm-ir-tools.sh",
    "tools/m3-polyglot-maven-plugin/scripts/test_verify_pinned_llvm_ir_tools.py",
    "tools/m3-llvm-tools-provider/src/main/java/io/synexia/maven/llvm/LlvmNativeToolsProvider.java",
    "tools/m3-llvm-tools-provider/src/main/java/io/synexia/maven/llvm/LlvmToolchainEvidence.java",
    "tools/m3-llvm-tools-provider/src/test/java/io/synexia/maven/llvm/LlvmNativePipelineIntegrationTestMain.java",
    "tools/m3-llvm-tools-provider/src/main/resources/META-INF/services/io.synexia.maven.polyglot.M3ToolProvider",
})
MAX_WORKFLOW_BYTES = 1024 * 1024
MAX_PATHS = 128


def trigger_paths(source: str) -> dict[str, tuple[str, ...]]:
    """Recognize exactly the fixed, literal source-proof trigger subset.

    Unlike a partial YAML parser, unfamiliar trigger keys *fail closed*.
    This also detects a second top-level ``on:`` after ``jobs:``; checking
    only until the next mapping was insufficient for duplicate-key YAML.
    """
    if len(source.encode("utf-8")) > MAX_WORKFLOW_BYTES:
        raise ValueError("workflow exceeds 1 MiB budget")
    lines = source.splitlines()
    top_level: set[str] = set()
    on_positions = []
    for index, line in enumerate(lines):
        if not line or line.lstrip().startswith("#"):
            continue
        if line.startswith((" ", "\t")):
            continue
        key_match = re.match(r"([A-Za-z][A-Za-z_0-9-]*)\s*:", line)
        if key_match:
            key = key_match.group(1)
            if key in top_level:
                raise ValueError("duplicate top-level YAML mapping: " + key)
            top_level.add(key)
        if re.match(r"(?:on|'on'|\"on\")\s*:", line):
            if line != "on:":
                raise ValueError("noncanonical top-level on: mapping")
            on_positions.append(index)
    if len(on_positions) != 1:
        raise ValueError("expected exactly one canonical top-level on: mapping")

    events: dict[str, list[str]] = {"push": [], "pull_request": []}
    seen_events: set[str] = set()
    paths_seen: set[str] = set()
    push_branches = False
    current: str | None = None
    in_paths = False
    for line in lines[on_positions[0] + 1:]:
        if line and not line.startswith((" ", "#")):
            break  # Next top-level mapping (globally checked for duplicates).
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if line.startswith("\t") or line[:len(line) - len(line.lstrip())].find("\t") != -1:
            raise ValueError("tab-indented YAML trigger is not admitted")
        event = re.fullmatch(r"  (push|pull_request|workflow_dispatch):", line)
        if event:
            current = event.group(1)
            if current in seen_events:
                raise ValueError("duplicate event: " + current)
            seen_events.add(current)
            in_paths = False
            continue
        if line == "    branches: [master]":
            if current != "push" or in_paths or push_branches:
                raise ValueError("unexpected or duplicate branch restriction")
            push_branches = True
            continue
        if line == "    paths:":
            if current not in events or current in paths_seen:
                raise ValueError("unexpected or duplicate paths list")
            paths_seen.add(current)
            in_paths = True
            continue
        if line.startswith("      - ") and in_paths and current in events:
            # Every admitted owner is a canonical, single-quoted ASCII path.
            # Python literal_eval also accepts Python-only syntax that YAML
            # would interpret differently; keep the two grammars aligned.
            match = re.fullmatch(r"      - '([A-Za-z0-9._/-]+)'", line)
            if match is None:
                raise ValueError("expected canonical single-quoted owner path")
            value = match.group(1)
            if value.startswith("/") or ".." in Path(value).parts:
                raise ValueError("expected a repository-relative owner path")
            if value in events[current]:
                raise ValueError("duplicate trigger " + current + ": " + value)
            events[current].append(value)
            if len(events[current]) > MAX_PATHS:
                raise ValueError("too many trigger paths")
            continue
        # `types`, `tags`, `branches-ignore`, restricted PR branches, input
        # aliases, merge refs and unsupported indentation all fail closed.
        raise ValueError("unrecognized source-proof trigger directive: " + line)

    if seen_events != {"push", "pull_request", "workflow_dispatch"}:
        raise ValueError("source-proof requires push, pull_request and workflow_dispatch")
    if not push_branches or paths_seen != set(events):
        raise ValueError("source-proof requires push master and both literal paths lists")
    for event, files in events.items():
        missing = sorted(REQUIRED.difference(files))
        if missing:
            raise ValueError(event + " misses required paths: " + ", ".join(missing))
    if set(events["push"]) != set(events["pull_request"]):
        raise ValueError("source-proof push and pull_request paths differ")
    return {event: tuple(sorted(files)) for event, files in events.items()}


def validate_owners(root: Path) -> None:
    """Disallow symlink aliases anywhere in mandatory source-owner paths."""
    root = root.absolute()
    if root.is_symlink() or not root.is_dir():
        raise ValueError("source-proof repository root must be a real directory")
    canonical = root.resolve(strict=True)
    if canonical != root:
        raise ValueError("source-proof repository root is aliased")
    for name in sorted(REQUIRED):
        path = canonical
        for component in Path(name).parts:
            path = path / component
            if path.is_symlink():
                raise ValueError("source-proof owner ancestor or file is aliased: " + name)
        if not path.is_file():
            raise ValueError("source-proof owner absent: " + name)
        if not path.resolve(strict=True).is_relative_to(canonical):
            raise ValueError("source-proof owner escapes repository: " + name)

def main() -> None:
    default = Path(__file__).resolve().parents[3] / ".github/workflows/m3-llvm-fork-source-proof.yml"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("workflow", nargs="?", type=Path, default=default)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[3])
    args = parser.parse_args()
    parsed = trigger_paths(args.workflow.read_text(encoding="utf-8"))
    validate_owners(args.root)
    print("M3-LLVM-FORK-SOURCE-PROOF-TRIGGERS-V1")
    for key in ("push", "pull_request"):
        print(f"{key}\t{len(parsed[key])}\tPASS")
    print(f"PASS: {len(REQUIRED)} mandatory owners, symmetric exact source-proof triggers")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        raise SystemExit("FAIL: " + str(error)) from error
