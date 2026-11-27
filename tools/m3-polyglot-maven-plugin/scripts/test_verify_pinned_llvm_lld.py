#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
"""Synthetic fail-closed admission tests. No LLVM binary is built or executed."""

from __future__ import annotations

import os
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("verify-pinned-llvm-lld.sh")
PIN = "591f3d1edf0a9ee718d17a0ee70dd013b3c01908"
TOOLS = ("clang", "clang++", "llc", "opt", "llvm-as", "llvm-link", "ld.lld", "wasm-ld", "lld-link")


def executable(path: Path, contents: str) -> None:
    path.write_text(contents, encoding="utf-8")
    path.chmod(0o755)


class Fixture:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.source = root / "source"
        self.build = root / "build"
        self.evidence = root / "evidence"
        self.runner = root / "runner"
        self.bin = root / "mock-bin"
        self.script = root / "tools/m3-polyglot-maven-plugin/scripts/verify-pinned-llvm-lld.sh"
        for path in (self.source, self.build / "bin", self.evidence, self.runner,
                     self.bin, self.script.parent):
            path.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(SCRIPT, self.script)
        self.script.chmod(0o755)
        self.plugin = root / "tools/m3-polyglot-maven-plugin"
        task = self.plugin / "target/classes/io/synexia/maven/polyglot/TaskRunner.class"
        task.parent.mkdir(parents=True, exist_ok=True)
        task.write_bytes(b"synthetic compiled Maven task runner\n")
        self.service = root / "tools/m3-llvm-tools-provider/src/main/resources/META-INF/services/io.synexia.maven.polyglot.M3ToolProvider"
        self.service.parent.mkdir(parents=True, exist_ok=True)
        self.service.write_text("io.synexia.maven.llvm.LlvmNativeToolsProvider\n", encoding="utf-8")
        for tool in TOOLS:
            executable(self.build / "bin" / tool, "#!/bin/sh\nexit 0\n")
        (self.evidence / "source-receipt.tsv").write_text(
            f"key\tvalue\nsource_revision\t{PIN}\n"
            "source_built_clang_llc_maven_native\tPASS\n"
            "source_built_llvm_ir_tools_native_jdk21\tPASS\n", encoding="utf-8")
        source_binaries = [f"source_revision\t{PIN}\n"]
        for tool in TOOLS:
            binary = self.build / "bin" / tool
            source_binaries += [f"tool\t{tool}\n",
                                f"{hashlib.sha256(binary.read_bytes()).hexdigest()}  {binary}\n",
                                f"{tool} version synthetic\n"]
        (self.evidence / "binary-receipt.txt").write_text(
            "".join(source_binaries), encoding="utf-8")
        executable(self.bin / "git", '''#!/usr/bin/env bash
if [[ "$*" == *"rev-parse --verify HEAD"* ]]; then
  printf '%s\\n' "${FAKE_GIT_SHA:-591f3d1edf0a9ee718d17a0ee70dd013b3c01908}"
elif [[ "$*" == *"status --porcelain --untracked-files=normal"* ]]; then
  printf '%s' "${FAKE_GIT_DIRTY:-}"
else
  exit 44
fi
''')
        executable(self.bin / "mvn", '''#!/usr/bin/env bash
printf 'Apache Maven (synthetic)\\nJava version: %s.0.0, vendor: synthetic\\n' "${FAKE_LANE:-21}"
''')
        executable(self.bin / "javac", '''#!/usr/bin/env bash
if [[ "${FAKE_JAVAC_FAIL:-0}" == 1 ]]; then exit 13; fi
while [[ $# -gt 0 ]]; do
  if [[ "$1" == -d ]]; then
    shift
    mkdir -p "$1/io/synexia/maven/llvm"
    printf 'synthetic\\n' > "$1/io/synexia/maven/llvm/LlvmWasmCoffIntegrationTestMain.class"
    exit 0
  fi
  shift
done
exit 41
''')
        executable(self.bin / "java", '''#!/usr/bin/env bash
if [[ "${1:-}" == -version ]]; then
  printf 'openjdk version "%s.0.0"\\n' "${FAKE_LANE:-21}" >&2
  exit 0
fi
if [[ -n "${FAKE_MUTATE_BINARY:-}" ]]; then
  printf 'mutation\\n' >> "$FAKE_MUTATE_BINARY"
fi
if [[ "${FAKE_BAD_OUTPUT:-0}" == 1 ]]; then
  echo 'NOT_RUN: native tool unavailable'
else
  echo 'PASS: 45 LLVM WASM and PE/COFF native output, invalidation and cache-exclusion assertions (synthetic test double)'
fi
''')
        self.env = dict(os.environ, PATH=f"{self.bin}:{os.environ['PATH']}",
                        RUNNER_TEMP=str(self.runner), FAKE_LANE="21")

    def run(self, lane: str = "21", **env: str) -> subprocess.CompletedProcess[str]:
        selected_env = dict(self.env, FAKE_LANE=lane)
        selected_env.update(env)
        return subprocess.run(["bash", str(self.script), str(self.source),
                               str(self.build), str(self.evidence), lane],
                              env=selected_env,
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              check=False, timeout=20)

    def receipt(self, lane: str = "21") -> bool:
        return f"source_built_llvm_lld_wasm_coff_native_jdk{lane}\tPASS\n" in (
            self.evidence / "source-receipt.tsv").read_text(encoding="utf-8")


class NativeToolProofAdmissionTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="m3-llvm-lld-proof-test-",
                                                dir=os.environ.get("RUNNER_TEMP"))
        self.addCleanup(self.temp.cleanup)
        self.f = Fixture(Path(self.temp.name))

    def test_happy_jdk21_and_jdk17_preserve_binary_bytes(self) -> None:
        before = {t: (self.f.build / "bin" / t).read_bytes() for t in TOOLS}
        result = self.f.run()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertTrue(self.f.receipt("21"))
        with (self.f.evidence / "source-receipt.tsv").open("a", encoding="utf-8") as f:
            f.write("source_built_clang_llc_maven_native_jdk17\tPASS\n")
            f.write("source_built_llvm_ir_tools_native_jdk17\tPASS\n")
        second = self.f.run("17")
        self.assertEqual(second.returncode, 0, second.stdout)
        self.assertTrue(self.f.receipt("17"))
        self.assertEqual(before, {t: (self.f.build / "bin" / t).read_bytes() for t in TOOLS})
        self.assertEqual(list(self.f.runner.iterdir()), [], "no compiled class leakage")

    def test_missing_arguments_refused(self) -> None:
        p = subprocess.run(["bash", str(self.f.script)], env=self.f.env,
                           text=True, capture_output=True, check=False)
        self.assertEqual(p.returncode, 64)

    def test_invalid_lane_refused(self) -> None:
        self.assertEqual(self.f.run("19").returncode, 64)

    def test_wrong_git_commit_refused(self) -> None:
        self.assertEqual(self.f.run(FAKE_GIT_SHA="0" * 40).returncode, 66)
        self.assertFalse(self.f.receipt())

    def test_dirty_git_checkout_refused(self) -> None:
        self.assertEqual(self.f.run(FAKE_GIT_DIRTY=" M llvm/lib/Transforms/Pass.cpp").returncode, 66)

    def test_missing_compiler_receipt_refused(self) -> None:
        (self.f.evidence / "source-receipt.tsv").unlink()
        self.assertEqual(self.f.run().returncode, 66)

    def test_missing_prior_jdk21_ir_receipt_refused(self) -> None:
        receipt = self.f.evidence / "source-receipt.tsv"
        receipt.write_text(receipt.read_text().replace(
            "source_built_llvm_ir_tools_native_jdk21\tPASS\n", ""))
        result = self.f.run()
        self.assertEqual(result.returncode, 66, result.stdout)
        self.assertFalse(self.f.receipt())

    def test_missing_jdk17_ir_receipt_refused(self) -> None:
        receipt = self.f.evidence / "source-receipt.tsv"
        with receipt.open("a", encoding="utf-8") as out:
            out.write("source_built_clang_llc_maven_native_jdk17\tPASS\n")
            out.write("source_built_llvm_lld_wasm_coff_native_jdk21\tPASS\n")
        result = self.f.run("17")
        self.assertEqual(result.returncode, 66, result.stdout)
        self.assertFalse(self.f.receipt("17"))

    def test_missing_jdk17_compiler_receipt_refused(self) -> None:
        self.assertEqual(self.f.run("17").returncode, 66)

    def test_missing_native_binary_refused(self) -> None:
        (self.f.build / "bin/llvm-link").unlink()
        self.assertEqual(self.f.run().returncode, 67)

    def test_missing_ld_lld_driver_refused(self) -> None:
        (self.f.build / "bin/ld.lld").unlink()
        self.assertEqual(self.f.run().returncode, 67)
        self.assertFalse(self.f.receipt())

    def test_missing_wasm_ld_driver_refused(self) -> None:
        (self.f.build / "bin/wasm-ld").unlink()
        self.assertEqual(self.f.run().returncode, 67)
        self.assertFalse(self.f.receipt())

    def test_missing_lld_link_driver_refused(self) -> None:
        (self.f.build / "bin/lld-link").unlink()
        self.assertEqual(self.f.run().returncode, 67)
        self.assertFalse(self.f.receipt())

    def test_incompatible_jvm_lane_refused(self) -> None:
        result = self.f.run("21", FAKE_LANE="17")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.f.receipt())

    def test_missing_service_descriptor_refused(self) -> None:
        self.f.service.unlink()
        self.assertEqual(self.f.run().returncode, 66)

    def test_missing_task_runner_refused(self) -> None:
        (self.f.plugin / "target/classes/io/synexia/maven/polyglot/TaskRunner.class").unlink()
        self.assertEqual(self.f.run().returncode, 66)

    def test_failed_provider_compilation_refused(self) -> None:
        self.assertEqual(self.f.run(FAKE_JAVAC_FAIL="1").returncode, 13)
        self.assertFalse(self.f.receipt())
        self.assertEqual(list(self.f.runner.iterdir()), [])

    def test_failed_runtime_refused(self) -> None:
        self.assertNotEqual(self.f.run(FAKE_BAD_OUTPUT="1").returncode, 0)
        self.assertFalse(self.f.receipt())

    def test_mutated_native_binary_refused(self) -> None:
        target = self.f.build / "bin/opt"
        self.assertNotEqual(self.f.run(FAKE_MUTATE_BINARY=str(target)).returncode, 0)
        self.assertFalse(self.f.receipt())

    def test_duplicate_success_refused(self) -> None:
        self.assertEqual(self.f.run().returncode, 0)
        self.assertEqual(self.f.run().returncode, 65)

    def test_missing_original_binary_receipt_refused(self) -> None:
        (self.f.evidence / "binary-receipt.txt").unlink()
        result = self.f.run()
        self.assertEqual(result.returncode, 66, result.stdout)
        self.assertFalse(self.f.receipt())

    def test_changed_original_source_binary_refused(self) -> None:
        binary = self.f.build / "bin" / "opt"
        binary.write_text("#!/bin/sh\necho altered binary\n", encoding="utf-8")
        binary.chmod(0o755)
        result = self.f.run()
        self.assertEqual(result.returncode, 66, result.stdout)
        self.assertFalse(self.f.receipt())

    def test_unrecorded_source_revision_refused(self) -> None:
        receipt = self.f.evidence / "source-receipt.tsv"
        receipt.write_text(receipt.read_text().replace(f"source_revision\t{PIN}\n", ""))
        self.assertEqual(self.f.run().returncode, 66)
        self.assertFalse(self.f.receipt())

    def test_duplicate_original_binary_hash_refused(self) -> None:
        receipt = self.f.evidence / "binary-receipt.txt"
        old = receipt.read_text()
        digest = hashlib.sha256((self.f.build / "bin/opt").read_bytes()).hexdigest()
        receipt.write_text(old + f"{digest}  {self.f.build / 'bin/opt'}\n")
        self.assertEqual(self.f.run().returncode, 66)
        self.assertFalse(self.f.receipt())

    def test_duplicate_original_source_identity_refused(self) -> None:
        receipt = self.f.evidence / "source-receipt.tsv"
        receipt.write_text(receipt.read_text() + f"source_revision\t{PIN}\n")
        self.assertEqual(self.f.run().returncode, 66)
        self.assertFalse(self.f.receipt())

    def test_jdk17_requires_prior_jdk21_native_lld_receipt(self) -> None:
        with (self.f.evidence / "source-receipt.tsv").open("a") as out:
            out.write("source_built_clang_llc_maven_native_jdk17\tPASS\n")
            out.write("source_built_llvm_ir_tools_native_jdk17\tPASS\n")
        result = self.f.run("17")
        self.assertEqual(result.returncode, 66, result.stdout)
        self.assertFalse(self.f.receipt("17"))

    def test_jdk17_requires_previous_binary_transcript(self) -> None:
        self.assertEqual(self.f.run().returncode, 0)
        (self.f.evidence / "llvm-lld-jdk21-binaries-after.sha256").unlink()
        with (self.f.evidence / "source-receipt.tsv").open("a") as out:
            out.write("source_built_clang_llc_maven_native_jdk17\tPASS\n")
            out.write("source_built_llvm_ir_tools_native_jdk17\tPASS\n")
        result = self.f.run("17")
        self.assertEqual(result.returncode, 66, result.stdout)
        self.assertFalse(self.f.receipt("17"))

    def test_jdk17_refuses_cross_lane_transcript_drift(self) -> None:
        self.assertEqual(self.f.run().returncode, 0)
        with (self.f.evidence / "source-receipt.tsv").open("a") as out:
            out.write("source_built_clang_llc_maven_native_jdk17\tPASS\n")
            out.write("source_built_llvm_ir_tools_native_jdk17\tPASS\n")
        previous = self.f.evidence / "llvm-lld-jdk21-binaries-after.sha256"
        previous.write_text(previous.read_text().replace("a", "b", 1))
        result = self.f.run("17")
        self.assertEqual(result.returncode, 66, result.stdout)
        self.assertFalse(self.f.receipt("17"))

    def test_jdk17_rejects_swapped_binary_even_if_baseline_receipt_rewritten(self) -> None:
        self.assertEqual(self.f.run().returncode, 0)
        with (self.f.evidence / "source-receipt.tsv").open("a") as out:
            out.write("source_built_clang_llc_maven_native_jdk17\tPASS\n")
            out.write("source_built_llvm_ir_tools_native_jdk17\tPASS\n")
        binary = self.f.build / "bin/opt"
        old_digest = hashlib.sha256(binary.read_bytes()).hexdigest()
        binary.write_text("#!/bin/sh\necho replaced after JDK 21\n", encoding="utf-8")
        binary.chmod(0o755)
        new_digest = hashlib.sha256(binary.read_bytes()).hexdigest()
        original = self.f.evidence / "binary-receipt.txt"
        original.write_text(original.read_text().replace(
            f"{old_digest}  {binary}\n", f"{new_digest}  {binary}\n"))
        result = self.f.run("17")
        self.assertEqual(result.returncode, 66, result.stdout)
        self.assertFalse(self.f.receipt("17"))



if __name__ == "__main__":
    unittest.main(verbosity=2)
