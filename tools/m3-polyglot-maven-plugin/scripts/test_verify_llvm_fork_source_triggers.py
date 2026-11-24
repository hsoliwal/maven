#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
"""Deterministic positive/negative contracts for the exact-fork CI trigger gate."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "verify-llvm-fork-source-triggers.py"
ROOT = HERE.parents[2]
WORKFLOW = ROOT / ".github/workflows/m3-llvm-fork-source-proof.yml"
SPEC = importlib.util.spec_from_file_location("m3_llvm_trigger_gate", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class TriggerProofTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_valid_canonical_trigger(self) -> None:
        result = GATE.trigger_paths(self.workflow)
        self.assertEqual(len(result["push"]), len(GATE.REQUIRED))
        self.assertEqual(result["push"], result["pull_request"])
        self.assertEqual(set(result["push"]), GATE.REQUIRED)

    def test_unchanged_on_reformatting_with_comments(self) -> None:
        changed = self.workflow.replace(
            "  workflow_dispatch:\n", "  # Manual qualification is retained\n  workflow_dispatch:\n", 1
        )
        self.assertEqual(GATE.trigger_paths(changed), GATE.trigger_paths(self.workflow))

    def test_rejects_trigger_bypasses(self) -> None:
        source = self.workflow
        variants = {
            "duplicated on after jobs": source + "\non:\n  workflow_dispatch:\n",
            "wrong master push branch": source.replace("branches: [master]", "branches: [not-master]", 1),
            "removed master push branch": source.replace("    branches: [master]\n", "", 1),
            "push tags only": source.replace("    branches: [master]", "    tags: [v1]", 1),
            "push branches-ignore": source.replace("    branches: [master]", "    branches-ignore: [master]", 1),
            "PR branch restriction": source.replace("  pull_request:\n", "  pull_request:\n    branches: [other] \n", 1),
            "PR event type restriction": source.replace("  pull_request:\n", "  pull_request:\n    types: [closed]\n", 1),
            "PR paths-ignore": source.replace("  pull_request:\n", "  pull_request:\n    paths-ignore: [docs/**]\n", 1),
            "manual dispatch removed": source.replace("  workflow_dispatch:\n", "", 1),
            "manual dispatch inputs": source.replace("  workflow_dispatch:\n", "  workflow_dispatch:\n    inputs:\n      target:\n        required: true\n", 1),
            "unknown event": source.replace("  workflow_dispatch:\n", "  unexpected_event:\n  workflow_dispatch:\n", 1),
            "noncanonical quoted on": source.replace("on:\n", "'on':\n", 1),
            "duplicate jobs mapping": source + "\njobs:\n  dummy: {}\n",
            "duplicate push": source.replace("  pull_request:\n", "  push:\n", 1),
            "duplicate workflow dispatch": source.replace("  workflow_dispatch:\n", "  workflow_dispatch:\n  workflow_dispatch:\n", 1),
            "missing paths section": source.replace("    paths:\n", "    pathz:\n", 1),
            "single-sided extra owner": source.replace(
                "    branches: [master]\n    paths:\n",
                "    branches: [master]\n    paths:\n      - 'tools/a-previously-untracked-file'\n", 1
            ),
            "negated path": source.replace("      - '.github/workflows/m3-llvm-fork-source-proof.yml'",
                                          "      - '!some/path'", 1),
            "wildcard path": source.replace("      - '.github/workflows/m3-llvm-fork-source-proof.yml'",
                                           "      - 'tools/**'", 1),
            "duplicated owner": source.replace("      - '.github/workflows/m3-llvm-fork-source-proof.yml'",
                                            "      - '.github/workflows/m3-llvm-fork-source-proof.yml'\n"
                                            "      - '.github/workflows/m3-llvm-fork-source-proof.yml'", 1),
            "unrecognized YAML alias": source.replace("    paths:\n", "    paths: &shared\n", 1),
            "Python-only path literal": source.replace("      - '.github/workflows/m3-llvm-fork-source-proof.yml'",
                                                       "      - '''.github/workflows/m3-llvm-fork-source-proof.yml'''", 1),
            "double-quoted alternative": source.replace("      - '.github/workflows/m3-llvm-fork-source-proof.yml'",
                                                        '      - ".github/workflows/m3-llvm-fork-source-proof.yml"', 1),
            "tab indentation": source.replace("  push:\n", "\tpush:\n", 1),
            "path traversal": source.replace("      - '.github/workflows/m3-llvm-fork-source-proof.yml'",
                                             "      - '../foreign'", 1),
            "overflow 1MiB": source + "#" + "x" * (GATE.MAX_WORKFLOW_BYTES + 1),
        }
        for label, candidate in variants.items():
            with self.subTest(label=label):
                with self.assertRaises(ValueError):
                    GATE.trigger_paths(candidate)

    @staticmethod
    def fixture_owner_root(root: Path) -> None:
        for name in GATE.REQUIRED:
            file = root / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text("fixture\n", encoding="utf-8")

    def test_owner_inventory_and_real_paths(self) -> None:
        with tempfile.TemporaryDirectory(prefix="m3-llvm-trigger-owner-") as directory:
            root = Path(directory) / "repository"
            root.mkdir()
            self.fixture_owner_root(root)
            GATE.validate_owners(root)
            missing = root / "tools/m3-polyglot-maven-plugin/pom.xml"
            missing.unlink()
            with self.assertRaises(ValueError):
                GATE.validate_owners(root)

    @unittest.skipIf(sys.platform.startswith("win"), "Windows symlink rights are platform-dependent")
    def test_owner_directory_symlink_is_not_admitted(self) -> None:
        with tempfile.TemporaryDirectory(prefix="m3-llvm-trigger-alias-") as directory:
            root = Path(directory) / "repository"
            root.mkdir()
            self.fixture_owner_root(root)
            original = root / "tools/m3-polyglot-maven-plugin/scripts"
            renamed = root / "tools/m3-polyglot-maven-plugin/scripts-real"
            original.rename(renamed)
            original.symlink_to(renamed, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "aliased"):
                GATE.validate_owners(root)

    @unittest.skipIf(sys.platform.startswith("win"), "Windows symlink rights are platform-dependent")
    def test_owner_file_symlink_is_not_admitted(self) -> None:
        with tempfile.TemporaryDirectory(prefix="m3-llvm-trigger-leaf-") as directory:
            root = Path(directory) / "repository"
            root.mkdir()
            self.fixture_owner_root(root)
            owner = root / "tools/m3-polyglot-maven-plugin/pom.xml"
            shadow = owner.with_name("pom-original.xml")
            owner.rename(shadow)
            owner.symlink_to(shadow)
            with self.assertRaisesRegex(ValueError, "aliased"):
                GATE.validate_owners(root)

    def test_actual_validator_cli(self) -> None:
        with tempfile.TemporaryDirectory(prefix="m3-llvm-trigger-cli-") as directory:
            root = Path(directory) / "repository"
            root.mkdir()
            self.fixture_owner_root(root)
            workflow = root / ".github/workflows/m3-llvm-fork-source-proof.yml"
            workflow.write_text(self.workflow, encoding="utf-8")
            completed = subprocess.run(
                [sys.executable, str(SCRIPT), str(workflow), "--root", str(root)],
                capture_output=True, text=True, check=True
            )
            self.assertIn(f"PASS: {len(GATE.REQUIRED)} mandatory owners", completed.stdout)
            self.assertIn(f"push\t{len(GATE.REQUIRED)}\tPASS", completed.stdout)

    def test_read_only_validator(self) -> None:
        with tempfile.TemporaryDirectory(prefix="m3-llvm-trigger-nochange-") as directory:
            root = Path(directory) / "repository"
            root.mkdir()
            self.fixture_owner_root(root)
            workflow = root / ".github/workflows/m3-llvm-fork-source-proof.yml"
            workflow.write_text(self.workflow, encoding="utf-8")
            before = {str(p.relative_to(root)): (p.stat().st_mtime_ns, p.read_bytes())
                      for p in root.rglob("*") if p.is_file()}
            GATE.trigger_paths(self.workflow)
            GATE.validate_owners(root)
            after = {str(p.relative_to(root)): (p.stat().st_mtime_ns, p.read_bytes())
                     for p in root.rglob("*") if p.is_file()}
            self.assertEqual(before, after)


if __name__ == "__main__":
    unittest.main(verbosity=2)
