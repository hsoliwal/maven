#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
"""Read-only admission of the exact pinned-fork LLVM LLD dual-JDK receipts.

An archive proves consistency of recorded evidence, NOT compiler-source ancestry
or executable bytes absent from that archive. Runner mode also hashes binaries.
"""
from __future__ import annotations

import argparse
import hashlib
import re
import stat
from pathlib import Path, PurePosixPath
from zipfile import BadZipFile, ZipFile

PIN = "591f3d1edf0a9ee718d17a0ee70dd013b3c01908"
TOOLS = ("clang", "clang++", "llc", "opt", "llvm-as", "llvm-link", "ld.lld", "wasm-ld", "lld-link")
RECEIPTS_PREFIX = "m3-llvm-fork-native-receipt/"
BUILD_PREFIX = "m3-llvm-original-build/"
MAX_SIZE = 24 * 1024 * 1024
MAX_ENTRIES = 90
DIGEST = re.compile(r"[0-9a-f]{64}\Z")
HASH_ROW = re.compile(r"([0-9a-f]{64})  (/[^\r\n]+)\Z")
SOURCE_FILES = (
    "llvm/CMakeLists.txt",
    "clang/tools/driver/CMakeLists.txt",
    "cmake/Modules/LLVMVersion.cmake",
    "lld/CMakeLists.txt",
    "lld/tools/lld/CMakeLists.txt",
)
# Exact filenames emitted by the existing pinned LLVM source-build owners.
# The retry sequence is separately bounded by the original Maven Central 429 gate.
KNOWN_RECEIPTS = frozenset({
    "source-receipt.tsv", "source-file-sha256.txt", "binary-receipt.txt",
    "configure.log", "native-build.log", "maven-test-compile.log",
    "maven-native-run.log", "java17-version.log", "maven17-version.log",
    "jdk17-binaries-before.sha256", "jdk17-binaries-after.sha256",
    "maven-jdk17-test-compile.log", "maven-native-jdk17.log",
} | {
    f"llvm-{kind}-{prefix}{lane}{suffix}"
    for kind in ("ir", "lld") for lane in (17, 21)
    for prefix, suffix in (
        ("java", "-version.log"), ("maven", "-version.log"),
        ("jdk", "-compile.log"), ("jdk", "-native.log"),
        ("jdk", "-binaries-before.sha256"),
        ("jdk", "-binaries-after.sha256"),
    )
})
RETRY_LOG = re.compile(r"maven-test-compile-attempt-[123]\.log\Z")


def known_evidence_member(name: str) -> bool:
    """Recognize only the exact published evidence file vocabulary."""
    if name == BUILD_PREFIX + "CMakeCache.txt":
        return True
    if not name.startswith(RECEIPTS_PREFIX):
        return False
    leaf = name[len(RECEIPTS_PREFIX):]
    return leaf in KNOWN_RECEIPTS or RETRY_LOG.fullmatch(leaf) is not None


STAGES = {
    "jdk17": TOOLS[:3],
    "llvm-ir-jdk21": TOOLS[:6],
    "llvm-ir-jdk17": TOOLS[:6],
    "llvm-lld-jdk21": TOOLS,
    "llvm-lld-jdk17": TOOLS,
}
COMPILER_MARKERS = (
    "PASS: LLVM C17/C++20 compile/link/run",
    "PASS: LLVM IR llc object, clang link and native run",
    "PASS: Objective-C and both Objective-C++ spellings compile",
    "LLVM native integration assertions",
)
IR_MARKER = "LLVM opt -> llvm-as -> llvm-link -> opt host-native assertions"
LLD_MARKER = "LLVM WASM and PE/COFF native output, invalidation and cache-exclusion assertions"


def require(ok: bool, why: str) -> None:
    if not ok:
        raise ValueError(why)


class Reader:
    def get(self, name: str) -> bytes:
        raise NotImplementedError


class DirectoryReader(Reader):
    def __init__(self, evidence: Path, build: Path):
        self.evidence, self.build = evidence, build
        for root in (evidence, build):
            require(root.is_dir() and not root.is_symlink(), "evidence/build root absent or aliased")
        self.evidence = evidence.resolve(strict=True)
        self.build = build.resolve(strict=True)
        entries = list(self.evidence.iterdir())
        require(len(entries) <= MAX_ENTRIES, "oversized runner evidence entry count")
        for leaf in entries:
            member = RECEIPTS_PREFIX + leaf.name
            require(known_evidence_member(member) and leaf.is_file() and not leaf.is_symlink(),
                    "unrecognized or aliased runner evidence member: " + member)

    def get(self, name: str) -> bytes:
        if name.startswith(RECEIPTS_PREFIX):
            parent, sub = self.evidence, name[len(RECEIPTS_PREFIX):]
        elif name == BUILD_PREFIX + "CMakeCache.txt":
            parent, sub = self.build, "CMakeCache.txt"
        else:
            raise ValueError("unknown evidence member: " + name)
        require(bool(sub) and "/" not in sub and sub not in (".", ".."), "noncanonical evidence member")
        leaf = parent / sub
        require(leaf.is_file() and not leaf.is_symlink(), "missing or aliased evidence: " + name)
        require(leaf.stat().st_size <= MAX_SIZE, "oversized evidence: " + name)
        return leaf.read_bytes()

    def check_binaries(self, expected: dict[str, tuple[str, str]]) -> None:
        base = (self.build / "bin").resolve(strict=True)
        for tool, (digest, path) in expected.items():
            actual = base / tool
            require(actual.is_file() and actual.exists(), "source-built executable absent: " + tool)
            require(actual.resolve(strict=True).is_relative_to(base), "source-built executable escapes bin: " + tool)
            require(str(actual) == path, "runner build path mismatches receipt: " + tool)
            require(hashlib.sha256(actual.read_bytes()).hexdigest() == digest,
                    "source-built executable SHA-256 mismatch: " + tool)


class ArchiveReader(Reader):
    def __init__(self, archive: Path):
        require(archive.is_file() and not archive.is_symlink(), "archive absent or aliased")
        self.archive = ZipFile(archive)
        self.entries: dict[str, object] = {}
        total = 0
        require(len(self.archive.infolist()) <= MAX_ENTRIES, "oversized evidence entry count")
        for item in self.archive.infolist():
            name = item.filename
            if item.is_dir():
                require(name in (RECEIPTS_PREFIX, BUILD_PREFIX), "unexpected archive directory")
                continue
            path = PurePosixPath(name)
            require(name not in self.entries, "duplicate ZIP member: " + name)
            require(known_evidence_member(name) and str(path) == name
                    and len(path.parts) == 2 and all(p not in (".", "..") for p in path.parts)
                    and not name.startswith("/") and "\\" not in name,
                    "unexpected ZIP path: " + name)
            require(not stat.S_ISLNK(item.external_attr >> 16), "symlink ZIP member: " + name)
            require(item.file_size <= MAX_SIZE, "oversized ZIP member: " + name)
            total += item.file_size
            require(total <= MAX_SIZE, "oversized ZIP evidence payload")
            self.entries[name] = item

    def get(self, name: str) -> bytes:
        require(name in self.entries, "missing ZIP evidence: " + name)
        return self.archive.read(self.entries[name])

    def close(self) -> None:
        self.archive.close()


def text(reader: Reader, name: str) -> str:
    try:
        return reader.get(name).decode("utf-8")
    except UnicodeDecodeError as err:
        raise ValueError("evidence is not UTF-8: " + name) from err


def rows(source: str, name: str) -> dict[str, str]:
    lines = source.splitlines()
    require(bool(lines) and lines[0] == "key\tvalue", "bad receipt magic: " + name)
    result: dict[str, str] = {}
    for line in lines[1:]:
        pair = line.split("\t")
        require(len(pair) == 2 and bool(pair[0]) and bool(pair[1]), "invalid TSV row: " + name)
        require(pair[0] not in result, "duplicate receipt identity: " + pair[0])
        result[pair[0]] = pair[1]
    return result


def receipt(reader: Reader) -> None:
    record = rows(text(reader, RECEIPTS_PREFIX + "source-receipt.tsv"), "source-receipt.tsv")
    for key, expected in {
        "source_repository": "hsoliwal/llvm-project",
        "source_revision": PIN,
        "source_validation": "EXACT_COMMIT_CLEAN_WORKTREE",
        "native_build": "MUST_COMPILE_ORIGINAL_CLANG_AND_LLC",
        "fortran_source_build": "NOT_RUN_THIS_GATE",
        "source_built_clang_llc_maven_native": "PASS",
        "source_built_clang_llc_maven_native_jdk17": "PASS",
        "source_built_llvm_ir_tools_native_jdk21": "PASS",
        "source_built_llvm_ir_tools_native_jdk17": "PASS",
        "source_built_llvm_lld_wasm_coff_native_jdk21": "PASS",
        "source_built_llvm_lld_wasm_coff_native_jdk17": "PASS",
    }.items():
        require(record.get(key) == expected, "missing/invalid terminal receipt: " + key)
    require(len(record) == 11, "unexpected source receipt fields")


def source_digests(reader: Reader) -> None:
    lines = text(reader, RECEIPTS_PREFIX + "source-file-sha256.txt").splitlines()
    require(len(lines) == len(SOURCE_FILES), "source closure hash count mismatch")
    for line, expected_name in zip(lines, SOURCE_FILES):
        match = re.fullmatch(r"([0-9a-f]{64})  ([^\r\n]+)", line)
        require(match is not None and match.group(2) == expected_name,
                "source closure identity missing: " + expected_name)


def binary_receipt(reader: Reader) -> dict[str, tuple[str, str]]:
    lines = text(reader, RECEIPTS_PREFIX + "binary-receipt.txt").splitlines()
    require(len(lines) == 1 + 3 * len(TOOLS), "source-built nine-executable receipt shape invalid")
    require(lines[0] == "source_revision\t" + PIN, "source-build executable receipt revision mismatch")
    result: dict[str, tuple[str, str]] = {}
    parent = None
    for i, tool in enumerate(TOOLS):
        name, sha, version = lines[1 + i * 3:4 + i * 3]
        require(name == "tool\t" + tool, "source-build tool order/identity mismatch: " + tool)
        match = HASH_ROW.fullmatch(sha)
        require(match is not None, "source-build executable digest invalid: " + tool)
        digest, path = match.groups()
        p = PurePosixPath(path)
        require(p.is_absolute() and p.name == tool and p.parent.name == "bin" and ".." not in p.parts,
                "source-build executable path malformed: " + tool)
        current_parent = str(p.parent)
        if parent is None:
            parent = current_parent
        require(current_parent == parent, "mixed source-built toolchain roots")
        require(bool(version.strip()) and "\t" not in version, "missing native executable version: " + tool)
        result[tool] = (digest, path)
    require(result["ld.lld"][0] == result["wasm-ld"][0] == result["lld-link"][0],
            "pinned LLD symlink dispatchers have inconsistent executable bytes")
    return result


def hashes(reader: Reader, name: str, tools: tuple[str, ...], original: dict[str, tuple[str, str]]) -> bytes:
    file = RECEIPTS_PREFIX + name + "-binaries-"
    found = reader.get(file + "before.sha256")
    after = reader.get(file + "after.sha256")
    require(found == after, "same-lane executable bytes drift: " + name)
    try:
        lines = found.decode("ascii").splitlines()
    except UnicodeDecodeError as err:
        raise ValueError("non-ASCII hash receipt: " + name) from err
    require(len(lines) == len(tools), "wrong source-built driver coverage: " + name)
    for line, tool in zip(lines, tools):
        digest, path = original[tool]
        require(line == digest + "  " + path, "source-built driver identity drift: " + name + "/" + tool)
    return found


def cmake(reader: Reader) -> None:
    lines = text(reader, BUILD_PREFIX + "CMakeCache.txt").splitlines()
    for key, expected in {
        "LLVM_ENABLE_PROJECTS": "clang;lld",
        "LLVM_TARGETS_TO_BUILD": "X86;WebAssembly",
        "CLANG_ENABLE_CIR": "OFF",
    }.items():
        found = [line.split("=", 1)[1] for line in lines
                 if line.startswith(key + ":") and "=" in line]
        require(len(found) == 1 and found[0] == expected,
                "CMake source-built configuration mismatch: " + key)


def native_logs(reader: Reader) -> None:
    for name in ("maven-native-run.log", "maven-native-jdk17.log"):
        log = text(reader, RECEIPTS_PREFIX + name)
        require(all(marker in log for marker in COMPILER_MARKERS), "missing original Clang/llc native witness: " + name)
    for lane in (21, 17):
        for category, marker in (("ir", IR_MARKER), ("lld", LLD_MARKER)):
            name = f"llvm-{category}-jdk{lane}-native.log"
            log = text(reader, RECEIPTS_PREFIX + name)
            require(marker in log and "PASS: " in log and "NOT_RUN:" not in log,
                    "native provider result missing or skipped: " + name)
    for name in ("maven-test-compile.log", "maven-jdk17-test-compile.log"):
        require("BUILD SUCCESS" in text(reader, RECEIPTS_PREFIX + name),
                "Maven consumer compilation not proven: " + name)


def audit(reader: Reader, *, check_runner_binary_bytes: bool) -> dict[str, str]:
    receipt(reader)
    source_digests(reader)
    original = binary_receipt(reader)
    cmake(reader)
    native_logs(reader)
    for stage, tools in STAGES.items():
        hashes(reader, stage, tools, original)
    for typ in ("llvm-ir", "llvm-lld"):
        first = reader.get(RECEIPTS_PREFIX + typ + "-jdk21-binaries-after.sha256")
        second = reader.get(RECEIPTS_PREFIX + typ + "-jdk17-binaries-before.sha256")
        require(first == second, "cross-JDK source-built tool identities differ: " + typ)
    if check_runner_binary_bytes:
        require(isinstance(reader, DirectoryReader), "runtime bytes require original build root")
        reader.check_binaries(original)
    return {"repository": "hsoliwal/llvm-project", "source_sha": PIN,
            "binary_count": str(len(original)), "jdk_lanes": "21,17",
            "binary_bytes": "ACTUAL_HASHED" if check_runner_binary_bytes else "NOT_PRESENT_ARCHIVE"}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--archive", type=Path, help="downloaded GitHub Actions ZIP; structural audit only")
    group.add_argument("--evidence", type=Path, help="runner receipt directory; requires --build")
    parser.add_argument("--build", type=Path, help="source-built native build directory")
    parser.add_argument("--expected-archive-sha256", type=str,
                        help="GitHub artifact digest; archive mode only")
    args = parser.parse_args()
    if args.expected_archive_sha256 is not None:
        require(args.archive is not None and DIGEST.fullmatch(args.expected_archive_sha256) is not None,
                "expected ZIP SHA-256 requires archive mode and a lowercase 64-hex digest")
        require(args.archive.stat().st_size <= 128 * 1024 * 1024, "ZIP exceeds download size budget")
        observed = hashlib.sha256()
        with args.archive.open("rb") as zip_bytes:
            for chunk in iter(lambda: zip_bytes.read(1 << 20), b""):
                observed.update(chunk)
        require(observed.hexdigest() == args.expected_archive_sha256, "GitHub ZIP artifact digest mismatch")
    require((args.evidence is None and args.build is None) or
            (args.evidence is not None and args.build is not None),
            "--evidence requires --build and --archive refuses --build")
    reader: Reader = (ArchiveReader(args.archive) if args.archive is not None
                      else DirectoryReader(args.evidence, args.build))
    try:
        result = audit(reader, check_runner_binary_bytes=args.build is not None)
    finally:
        if isinstance(reader, ArchiveReader):
            reader.close()
    print("M3-LLVM-FORK-LLD-RECEIPT-AUDIT-V1")
    for key in ("repository", "source_sha", "binary_count", "jdk_lanes", "binary_bytes"):
        print(f"{key}\t{result[key]}")
    print("PASS: nine source-built tools, six native receipts, JDK 21/17 transcript identity")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, BadZipFile) as error:
        raise SystemExit("FAIL: " + str(error)) from error
