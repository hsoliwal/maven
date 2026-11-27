#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
"""Read-only Windows LLVM proof trigger and preflight admission (no PyYAML)."""
from __future__ import annotations

import argparse
import re
from pathlib import Path

WORKFLOW = ".github/workflows/m3-llvm-windows-stl-compat.yml"
PREFLIGHT = ".github/scripts/require-m3-llvm-windows-stl.ps1"
SELF = ".github/scripts/verify-m3-llvm-windows-triggers.py"
REQUIRED = frozenset({
    ".gitattributes",
    ".github/workflows/maven.yml",
    ".github/workflows/m3-polyglot-verify.yml",
    WORKFLOW,
    PREFLIGHT,
    SELF,
    "tools/m3-polyglot-maven-plugin/docs/LLVM_WINDOWS_STL_COMPAT.md",
    "tools/m3-polyglot-maven-plugin/pom.xml",
    "tools/m3-polyglot-maven-plugin/src/main/java/io/synexia/maven/polyglot/LlvmCompilerProvider.java",
    "tools/m3-polyglot-maven-plugin/src/main/java/io/synexia/maven/polyglot/M3ToolProvider.java",
    "tools/m3-polyglot-maven-plugin/src/main/java/io/synexia/maven/polyglot/M3ToolProviders.java",
    "tools/m3-polyglot-maven-plugin/src/main/java/io/synexia/maven/polyglot/M3ToolReuseInputs.java",
    "tools/m3-polyglot-maven-plugin/src/main/java/io/synexia/maven/polyglot/TaskRunner.java",
    "tools/m3-polyglot-maven-plugin/src/main/java/io/synexia/maven/polyglot/TaskManifest.java",
    "tools/m3-polyglot-maven-plugin/src/main/java/io/synexia/maven/polyglot/InputStamps.java",
    "tools/m3-polyglot-maven-plugin/src/main/java/io/synexia/maven/polyglot/BuildCache.java",
    "tools/m3-polyglot-maven-plugin/src/main/resources/META-INF/services/io.synexia.maven.polyglot.M3ToolProvider",
    "tools/m3-polyglot-maven-plugin/src/test/java/io/synexia/maven/polyglot/LlvmCompilerIntegrationTestMain.java",
    "tools/m3-polyglot-maven-plugin/src/test/java/io/synexia/maven/polyglot/LlvmCompilerSelectionContractTestMain.java",
    "tools/m3-polyglot-maven-plugin/src/test/java/io/synexia/maven/polyglot/LlvmInstallationSelectionContractTestMain.java",
})


def workflow_contract(source: str) -> None:
    """Accept a deliberately small, literal Actions event grammar; fail closed."""
    if len(source.encode("utf-8")) > 1024 * 1024:
        raise ValueError("workflow exceeds 1 MiB")
    lines = source.splitlines()
    toplevel: dict[str, int] = {}
    for i, line in enumerate(lines):
        if not line.strip() or line.lstrip().startswith("#") or line.startswith((" ", "\t")):
            continue
        m = re.fullmatch(r"([a-z][a-z_0-9-]*):(?: .*)?", line)
        if not m:
            raise ValueError("unrecognized top-level YAML mapping")
        key = m.group(1)
        if key in toplevel:
            raise ValueError("duplicate YAML mapping: " + key)
        toplevel[key] = i
    if "on" not in toplevel or "permissions" not in toplevel or "jobs" not in toplevel:
        raise ValueError("workflow missing on, permissions or jobs")
    if not (toplevel["on"] < toplevel["permissions"] < toplevel["jobs"]):
        raise ValueError("unexpected workflow mapping order")

    active = [line for line in lines[toplevel["on"] + 1:toplevel["permissions"]]
              if line.strip() and not line.lstrip().startswith("#")]
    if not active or active[0] != "  pull_request:":
        raise ValueError("expected pull_request event, not privileged or hidden event")
    if "  workflow_dispatch:" not in active or active.count("  workflow_dispatch:") != 1:
        raise ValueError("missing or duplicate manual dispatch")
    dispatch = active.index("  workflow_dispatch:")
    if active[dispatch + 1:]:
        raise ValueError("manual dispatch accepts no unreviewed inputs or nested actions")
    prefix = active[1:dispatch]
    if prefix[:3] != [
        "    types: [opened, reopened, synchronize, ready_for_review]",
        "    branches: [master]",
        "    paths:",
    ]:
        raise ValueError("unsafe pull_request activity, branch or paths selection")
    paths = []
    for line in prefix[3:]:
        m = re.fullmatch(r"      - '([a-zA-Z0-9._/-]+)'", line)
        if not m:
            raise ValueError("nonliteral path, wildcard, alias or unexpected trigger directive")
        path = m.group(1)
        if path.startswith("/") or ".." in Path(path).parts:
            raise ValueError("absolute or escaping owner path")
        paths.append(path)
    if len(paths) > 128 or len(set(paths)) != len(paths):
        raise ValueError("too many or duplicate trigger paths")
    if set(paths) != REQUIRED:
        raise ValueError("Windows LLVM proof trigger owner set differs: "
                         + repr(sorted(REQUIRED.symmetric_difference(paths))))

    perm_lines = [line for line in lines[toplevel["permissions"] + 1:toplevel["jobs"]]
                  if line.strip() and not line.lstrip().startswith("#")]
    if perm_lines != ["  contents: read"]:
        raise ValueError("Windows LLVM proof must have read-only repository permissions")
    if "  windows-msvc-llvm:" not in lines[toplevel["jobs"] + 1:]:
        raise ValueError("missing original Windows native test job")
    if "LlvmCompilerIntegrationTestMain --required" not in source:
        raise ValueError("native Maven integration must be required")
    if "Get-FileHash -Algorithm SHA256" not in source:
        raise ValueError("missing post-native executable digest verification")
    if "Join-Path $env:GITHUB_WORKSPACE '.github/scripts/require-m3-llvm-windows-stl.ps1'" not in source:
        raise ValueError("absolute checkout PowerShell preflight invocation absent")
    for forbidden in ("_ALLOW_COMPILER_AND_STL_VERSION_MISMATCH", "-Dspotless.skip", "-DskipTests"):
        if forbidden in source:
            raise ValueError("Windows native CI suppression: " + forbidden)


def preflight_contract(source: str) -> None:
    live = "\n".join(line for line in source.splitlines() if not line.lstrip().startswith("#"))
    required = (
        "$expectedVersion = '22.1.8'",
        "$expectedInstallerSha256 = '16e5709785fef73c854646241c4a92c5cd574318d1b33c63330dd7721903e55c'",
        "--allow-downgrade",
        'foreach ($compiler in @(\'clang\', \'clang++\'))',
        'Get-FileHash -Algorithm SHA256',
        'M3_POLYGLOT_LLVM_TEST_HOME=',
        "consteval int answer() { return 42; }",
        "if ($LASTEXITCODE -ne 0",
    )
    for token in required:
        if token not in live:
            raise ValueError("missing Windows host-toolchain evidence: " + token)
    for bad in ("_ALLOW_COMPILER_AND_STL_VERSION_MISMATCH", "-DskipTests", "-Dspotless.skip"):
        if bad in live:
            raise ValueError("Windows host-toolchain bypass: " + bad)


def self_test(workflow: str, preflight: str) -> None:
    workflow_contract(workflow)
    preflight_contract(preflight)
    owner = "      - '" + WORKFLOW + "'"
    mutations = {
        "duplicate on": workflow + "\non:\n  workflow_dispatch:\n",
        "privileged event": workflow.replace("  pull_request:\n", "  pull_request_target:\n", 1),
        "bad activity": workflow.replace("ready_for_review]", "closed]", 1),
        "bad branch": workflow.replace("branches: [master]", "branches: [other]", 1),
        "missing dispatch": workflow.replace("  workflow_dispatch:\n", "", 1),
        "manual inputs": workflow.replace("  workflow_dispatch:\n", "  workflow_dispatch:\n    inputs:\n", 1),
        "missing path": workflow.replace(owner + "\n", "", 1),
        "duplicated owner": workflow.replace(owner, owner + "\n" + owner, 1),
        "wildcard": workflow.replace(owner, "      - 'tools/**'", 1),
        "negated path": workflow.replace(owner, "      - '!src/main/java'", 1),
        "alias": workflow.replace("    paths:\n", "    paths: &unchecked\n", 1),
        "write permissions": workflow.replace("  contents: read", "  contents: write", 1),
        "no native assertion": workflow.replace("LlvmCompilerIntegrationTestMain --required",
                                                "LlvmCompilerIntegrationTestMain", 1),
        "no post-digest": workflow.replace("Get-FileHash -Algorithm SHA256",
                                            "Get-FileHash -Algorithm MD5", 1),
        "skip tests": workflow + "\n# mutation at executable location\n  - run: mvn -DskipTests\n",
    }
    for name, changed in mutations.items():
        if changed == workflow:
            raise AssertionError("inactive workflow mutation: " + name)
        try:
            workflow_contract(changed)
        except ValueError:
            continue
        raise AssertionError("admitted unsafe workflow mutation: " + name)
    scripts = {
        "wrong LLVM version": preflight.replace("$expectedVersion = '22.1.8'", "$expectedVersion = '20.1.8'", 1),
        "wrong SHA": preflight.replace("16e5709785fef73c854646241c4a92c5cd574318d1b33c63330dd7721903e55c",
                                       "0" * 64, 1),
        "no downgrade option": preflight.replace("--allow-downgrade", "", 1),
        "only one clang driver": preflight.replace("@('clang', 'clang++')", "@('clang')", 1),
        "checksum disabled": preflight.replace("Get-FileHash -Algorithm SHA256", "Get-FileHash -Algorithm MD5", 1),
        "unmatched STL": preflight + "\n_ALLOW_COMPILER_AND_STL_VERSION_MISMATCH=1\n",
    }
    for name, changed in scripts.items():
        if changed == preflight:
            raise AssertionError("inactive preflight mutation: " + name)
        try:
            preflight_contract(changed)
        except ValueError:
            continue
        raise AssertionError("admitted unsafe preflight mutation: " + name)
    print("PASS: Windows LLVM trigger self-test, 21 negative mutations rejected")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    root = args.root.resolve(strict=True)
    workflow = (root / WORKFLOW).read_text(encoding="utf-8")
    script = (root / PREFLIGHT).read_text(encoding="utf-8")
    if args.self_test:
        self_test(workflow, script)
        return
    workflow_contract(workflow)
    preflight_contract(script)
    for name in REQUIRED:
        path = root
        for component in Path(name).parts:
            path = path / component
            if path.is_symlink():
                raise ValueError("aliased Windows LLVM owner or ancestor: " + name)
        if not path.is_file() or not path.resolve(strict=True).is_relative_to(root):
            raise ValueError("missing or escaping Windows LLVM owner: " + name)
    print(f"PASS: {len(REQUIRED)} literal native owners, read-only CI, SHA-256 and required native execution")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, AssertionError) as exc:
        raise SystemExit("FAIL: " + str(exc)) from exc
