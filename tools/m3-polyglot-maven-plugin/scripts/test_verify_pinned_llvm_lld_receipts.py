#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
"""Synthetic adversarial receipt tests: no native LLVM build is claimed."""
from __future__ import annotations

import hashlib
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

SCRIPT = Path(__file__).with_name("verify-pinned-llvm-lld-receipts.py")
SPEC = importlib.util.spec_from_file_location("m3_lld_receipts", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
V = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(V)


class Fixture:
    def __init__(self, home: Path) -> None:
        self.home = home
        self.evidence = home / "m3-llvm-fork-native-receipt"
        self.build = home / "m3-llvm-original-build"
        self.evidence.mkdir()
        (self.build / "bin").mkdir(parents=True)
        self.digest = {}
        for tool in V.TOOLS:
            path = self.build / "bin" / tool
            content = ("lld-original-multicall\n" if tool in ("ld.lld", "wasm-ld", "lld-link")
                       else "original-" + tool + "\n")
            path.write_text(content, encoding="utf-8")
            path.chmod(0o755)
            self.digest[tool] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.write("source-receipt.tsv", "key\tvalue\n" + "".join(
            key + "\t" + value + "\n" for key, value in (
                ("source_repository", "hsoliwal/llvm-project"),
                ("source_revision", V.PIN),
                ("source_validation", "EXACT_COMMIT_CLEAN_WORKTREE"),
                ("native_build", "MUST_COMPILE_ORIGINAL_CLANG_AND_LLC"),
                ("fortran_source_build", "NOT_RUN_THIS_GATE"),
                ("source_built_clang_llc_maven_native", "PASS"),
                ("source_built_clang_llc_maven_native_jdk17", "PASS"),
                ("source_built_llvm_ir_tools_native_jdk21", "PASS"),
                ("source_built_llvm_ir_tools_native_jdk17", "PASS"),
                ("source_built_llvm_lld_wasm_coff_native_jdk21", "PASS"),
                ("source_built_llvm_lld_wasm_coff_native_jdk17", "PASS"),
            )))
        self.write("binary-receipt.txt", "source_revision\t" + V.PIN + "\n" + "".join(
            f"tool\t{tool}\n{self.digest[tool]}  {self.build / 'bin' / tool}\n{tool} version synthetic\n"
            for tool in V.TOOLS))
        self.write("source-file-sha256.txt", "".join(
            hashlib.sha256(path.encode()).hexdigest() + "  " + path + "\n" for path in V.SOURCE_FILES))
        for stage, names in V.STAGES.items():
            transcript = "".join(f"{self.digest[tool]}  {self.build / 'bin' / tool}\n" for tool in names)
            self.write(stage + "-binaries-before.sha256", transcript)
            self.write(stage + "-binaries-after.sha256", transcript)
        self.write("maven-native-run.log", "\n".join((*V.COMPILER_MARKERS[:3], "PASS: 46 " + V.COMPILER_MARKERS[-1])) + "\n")
        self.write("maven-native-jdk17.log", "\n".join((*V.COMPILER_MARKERS[:3], "PASS: 46 " + V.COMPILER_MARKERS[-1])) + "\n")
        for lane in (17, 21):
            self.write(f"llvm-ir-jdk{lane}-native.log", "PASS: 44 " + V.IR_MARKER + "\n")
            self.write(f"llvm-lld-jdk{lane}-native.log", "PASS: 55 " + V.LLD_MARKER + "\n")
        for name in ("maven-test-compile.log", "maven-jdk17-test-compile.log"):
            self.write(name, "[INFO] BUILD SUCCESS\n")
        (self.build / "CMakeCache.txt").write_text(
            "LLVM_ENABLE_PROJECTS:STRING=clang;lld\n"
            "LLVM_TARGETS_TO_BUILD:STRING=X86;WebAssembly\n"
            "CLANG_ENABLE_CIR:BOOL=OFF\n", encoding="utf-8")

    def write(self, name: str, content: str) -> None:
        (self.evidence / name).write_text(content, encoding="utf-8")

    def change(self, name: str, old: str, new: str) -> None:
        path = self.evidence / name
        src = path.read_text(encoding="utf-8")
        assert old in src, f"fixture missing: {old}"
        path.write_text(src.replace(old, new, 1), encoding="utf-8")

    def archive(self, name: str = "evidence.zip") -> Path:
        path = self.home / name
        with ZipFile(path, "w", ZIP_DEFLATED) as z:
            for file in sorted(self.evidence.iterdir()):
                z.write(file, "m3-llvm-fork-native-receipt/" + file.name)
            z.write(self.build / "CMakeCache.txt", "m3-llvm-original-build/CMakeCache.txt")
        return path

    def cli(self, *, archived: bool = False) -> subprocess.CompletedProcess[str]:
        args = [sys.executable, "-B", str(SCRIPT)]
        if archived:
            args.extend(["--archive", str(self.archive())])
        else:
            args.extend(["--evidence", str(self.evidence), "--build", str(self.build)])
        return subprocess.run(args, capture_output=True, text=True, check=False, timeout=10)


class ReceiptAuditTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="m3-llvm-lld-receipts-")
        self.addCleanup(self.temp.cleanup)
        self.f = Fixture(Path(self.temp.name))

    def refuses(self, reason: str, *, archived: bool = False) -> None:
        result = self.f.cli(archived=archived)
        self.assertNotEqual(result.returncode, 0, reason + ": " + result.stdout)
        self.assertIn("FAIL:", result.stderr, reason + ": " + result.stderr)

    def test_valid_synthetic_runner(self) -> None:
        result = self.f.cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("binary_bytes\tACTUAL_HASHED", result.stdout)
        self.assertIn("PASS: nine source-built tools", result.stdout)

    def test_valid_synthetic_archive_separates_binary_claim(self) -> None:
        result = self.f.cli(archived=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("binary_bytes\tNOT_PRESENT_ARCHIVE", result.stdout)

    def test_expected_github_artifact_sha256(self) -> None:
        path = self.f.archive()
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        expected = subprocess.run([sys.executable, "-B", str(SCRIPT), "--archive", str(path),
                                   "--expected-archive-sha256", digest],
                                  capture_output=True, text=True, check=False)
        self.assertEqual(expected.returncode, 0, expected.stderr)
        altered = subprocess.run([sys.executable, "-B", str(SCRIPT), "--archive", str(path),
                                  "--expected-archive-sha256", "0" * 64],
                                 capture_output=True, text=True, check=False)
        self.assertNotEqual(altered.returncode, 0)
        self.assertIn("digest mismatch", altered.stderr)

    def test_read_only_replay(self) -> None:
        before = {str(p.relative_to(self.f.home)): (p.stat().st_mtime_ns, hashlib.sha256(p.read_bytes()).hexdigest())
                  for p in self.f.home.rglob("*") if p.is_file()}
        for _ in range(2):
            self.assertEqual(self.f.cli().returncode, 0)
        after = {str(p.relative_to(self.f.home)): (p.stat().st_mtime_ns, hashlib.sha256(p.read_bytes()).hexdigest())
                 for p in self.f.home.rglob("*") if p.is_file()}
        self.assertEqual(before, after)

    def test_missing_native_success_refused(self) -> None:
        self.f.change("source-receipt.tsv", "source_built_llvm_lld_wasm_coff_native_jdk17\tPASS\n", "")
        self.refuses("missing JDK17 LLD result")

    def test_wrong_source_revision_refused(self) -> None:
        self.f.change("source-receipt.tsv", V.PIN, "0" * 40)
        self.refuses("wrong fork source")

    def test_duplicate_receipt_identity_refused(self) -> None:
        with (self.f.evidence / "source-receipt.tsv").open("a") as out:
            out.write("source_built_llvm_lld_wasm_coff_native_jdk21\tPASS\n")
        self.refuses("duplicate proof")

    def test_unqualified_fortran_refused(self) -> None:
        self.f.change("source-receipt.tsv", "NOT_RUN_THIS_GATE", "PASS")
        self.refuses("unsupported flang claim")

    def test_binary_missing_refused(self) -> None:
        (self.f.build / "bin" / "wasm-ld").unlink()
        self.refuses("missing native executable")

    def test_binary_mutation_refused(self) -> None:
        (self.f.build / "bin" / "wasm-ld").write_text("changed after source build\n")
        self.refuses("native binary mutated")

    def test_binary_name_duplicate_refused(self) -> None:
        self.f.change("binary-receipt.txt", "tool\twasm-ld", "tool\tld.lld")
        self.refuses("duplicate original tool")

    def test_binary_hash_drift_refused(self) -> None:
        self.f.change("binary-receipt.txt", self.f.digest["wasm-ld"], "a" * 64)
        self.refuses("binary digest mismatch", archived=True)

    def test_source_cmake_closure_missing_refused(self) -> None:
        self.f.change("source-file-sha256.txt", "  lld/tools/lld/CMakeLists.txt\n", "  lld/other/CMakeLists.txt\n")
        self.refuses("source LLD CMake closure absent", archived=True)

    def test_cmake_wrong_backend_refused(self) -> None:
        cache = self.f.build / "CMakeCache.txt"
        cache.write_text(cache.read_text().replace("X86;WebAssembly", "X86"))
        self.refuses("missing WebAssembly backend")

    def test_compiler_native_log_missing_refused(self) -> None:
        (self.f.evidence / "maven-native-jdk17.log").unlink()
        self.refuses("missing native runtime")

    def test_ir_native_skip_refused(self) -> None:
        self.f.change("llvm-ir-jdk17-native.log", "PASS: 44 ", "NOT_RUN: no tools\nPASS: 44 ")
        self.refuses("native skipped", archived=True)

    def test_lld_native_proof_missing_refused(self) -> None:
        self.f.change("llvm-lld-jdk21-native.log", "WASM and PE/COFF", "ELF")
        self.refuses("wrong native output")

    def test_same_lane_hash_drift_refused(self) -> None:
        self.f.change("llvm-lld-jdk21-binaries-after.sha256", self.f.digest["lld-link"], "f" * 64)
        self.refuses("binary mutated in JDK21")

    def test_cross_jdk_hash_drift_refused(self) -> None:
        self.f.change("llvm-lld-jdk17-binaries-before.sha256", self.f.digest["lld-link"], "f" * 64)
        self.f.change("llvm-lld-jdk17-binaries-after.sha256", self.f.digest["lld-link"], "f" * 64)
        self.refuses("cross-JDK mismatch", archived=True)

    def test_wrong_stage_coverage_refused(self) -> None:
        path = self.f.evidence / "llvm-lld-jdk17-binaries-before.sha256"
        path.write_text("".join(path.read_text().splitlines(keepends=True)[:-1]))
        self.refuses("missing ninth original tool")

    def test_archive_traversal_refused(self) -> None:
        path = self.f.archive("attack.zip")
        with ZipFile(path, "a") as z:
            z.writestr("m3-llvm-fork-native-receipt/../escape", "injected")
        r = subprocess.run([sys.executable, "-B", str(SCRIPT), "--archive", str(path)],
                           capture_output=True, text=True, check=False)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("FAIL:", r.stderr)

    def test_archive_duplicate_refused(self) -> None:
        import warnings
        path = self.f.archive("duplicate.zip")
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with ZipFile(path, "a") as z:
                z.writestr(V.RECEIPTS_PREFIX + "source-receipt.tsv", "forge")
        r = subprocess.run([sys.executable, "-B", str(SCRIPT), "--archive", str(path)],
                           capture_output=True, text=True, check=False)
        self.assertNotEqual(r.returncode, 0)

    def test_archive_symlink_refused(self) -> None:
        import warnings
        path = self.f.archive("symlink.zip")
        entry = ZipInfo(V.RECEIPTS_PREFIX + "linked.log")
        entry.create_system = 3
        entry.external_attr = (0o120777 << 16)
        with ZipFile(path, "a") as z:
            z.writestr(entry, "target")
        r = subprocess.run([sys.executable, "-B", str(SCRIPT), "--archive", str(path)],
                           capture_output=True, text=True, check=False)
        self.assertNotEqual(r.returncode, 0)

    def test_archive_refuses_unrecognized_receipt(self) -> None:
        path = self.f.archive("unowned.zip")
        with ZipFile(path, "a", ZIP_DEFLATED) as z:
            z.writestr(V.RECEIPTS_PREFIX + "unowned-proof.txt", "not a registered source-owner file")
        result = subprocess.run([sys.executable, "-B", str(SCRIPT), "--archive", str(path)],
                                capture_output=True, text=True, check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unrecognized", result.stderr)

    def test_archive_refuses_noncanonical_separator(self) -> None:
        path = self.f.archive("noncanonical.zip")
        with ZipFile(path, "a", ZIP_DEFLATED) as z:
            z.writestr(V.RECEIPTS_PREFIX + "/configure.log", "alternate filename")
        result = subprocess.run([sys.executable, "-B", str(SCRIPT), "--archive", str(path)],
                                capture_output=True, text=True, check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("noncanonical", result.stderr)

    def test_known_original_maven_retry_log_is_admitted(self) -> None:
        self.f.write("maven-test-compile-attempt-1.log", "[INFO] BUILD SUCCESS\n")
        result = self.f.cli(archived=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_maven_failure_cannot_be_masked_by_success(self) -> None:
        self.f.write("maven-jdk17-test-compile.log", "[ERROR] BUILD FAILURE\n[INFO] BUILD SUCCESS\n")
        self.refuses("contradictory Maven terminal evidence")
        self.refuses("contradictory archived Maven terminal evidence", archived=True)

    def test_native_failure_cannot_be_masked_by_pass(self) -> None:
        path = self.f.evidence / "llvm-lld-jdk21-native.log"
        path.write_text(path.read_text() + "FAIL: native linker failed\n", encoding="utf-8")
        self.refuses("contradictory LLD PASS/FAIL")

    def test_uncaught_compiler_exception_cannot_be_masked_by_pass(self) -> None:
        path = self.f.evidence / "maven-native-run.log"
        path.write_text(path.read_text() + 'Exception in thread "main" java.lang.Error: abort\n',
                        encoding="utf-8")
        self.refuses("compiler uncaught exception")

    def test_bare_compiler_marker_cannot_substitute_for_pass(self) -> None:
        self.f.change("maven-native-run.log", "PASS: 46 LLVM native integration assertions",
                      "LLVM native integration assertions")
        self.refuses("compiler lacked final PASS line")

    def test_bare_ir_marker_cannot_substitute_for_pass(self) -> None:
        self.f.change("llvm-ir-jdk17-native.log", "PASS: 44 ", "")
        self.refuses("IR lacked final PASS line")

    def test_caught_compiler_diagnostic_and_fortran_skip_remain_admissible(self) -> None:
        path = self.f.evidence / "maven-native-run.log"
        path.write_text(path.read_text() + "source.c:1: error: deliberate negative test\n"
                        + "NOT_RUN: Fortran compile/link/run (flang unavailable)\n", encoding="utf-8")
        result = self.f.cli()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_prior_successful_jdk17_archive_does_not_prove_lld(self) -> None:
        # Always present on all CI hosts; never depends on a local user archive.
        self.f.change("source-receipt.tsv", "source_built_llvm_lld_wasm_coff_native_jdk21\tPASS\n", "")
        self.f.change("source-receipt.tsv", "source_built_llvm_lld_wasm_coff_native_jdk17\tPASS\n", "")
        self.refuses("previous compiler+IR results cannot authorize LLD", archived=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
