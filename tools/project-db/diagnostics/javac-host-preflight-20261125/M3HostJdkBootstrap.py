#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only JDK environment/link census before Maven artifact resolution.

Canonical source owner: hsoliwal/com.synexia, issue #19317.  This is
DIAGNOSTIC ONLY: never interpret its output as a qualified cache input.
No files are changed under an inspected JDK; environment values are redacted.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

MAX_ENTRIES = 262_144
MAX_LINKS = 4_096
MAX_LINK_BYTES = 64 << 20
MAX_TOTAL_BYTES = 256 << 20
SCOPES = ("bin", "lib", "conf")
VARIABLES = frozenset(("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS",
                       "JDK_JAVAC_OPTIONS", "LD_PRELOAD", "LD_LIBRARY_PATH",
                       "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH", "DYLD_FALLBACK_LIBRARY_PATH"))
SAFE_LABEL = re.compile(r"^[a-zA-Z0-9._/-]{1,200}$")
HOME_PATTERN = re.compile(r"(?m)^\s*java\.home = (.+?)\s*$")
MAJOR_PATTERN = re.compile(r"(?m)^\s*java\.specification\.version = ([0-9]+)\s*$")


def sha(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def redact_label(label: str) -> str:
    """Reject control/terminal escapes and potentially sensitive path names."""
    return label if SAFE_LABEL.fullmatch(label) else "HASH-" + sha(label.encode("utf-8", "surrogateescape"))


def injected_names(environment: dict[str, str]) -> str:
    return ",".join(sorted(key for key, value in environment.items()
                           if value and (key in VARIABLES or key.startswith("LD_") or key.startswith("DYLD_"))))


def java_properties(java: str | Path) -> tuple[Path, int]:
    """Ask the actual selected executable, never guess from JAVA_HOME alone."""
    process = subprocess.run([str(java), "-XshowSettings:properties", "-version"],
                             capture_output=True, text=True, timeout=30, check=False)
    if process.returncode:
        raise RuntimeError("Java executable failed before JDK inventory")
    output = process.stderr + "\n" + process.stdout
    home = HOME_PATTERN.search(output)
    major = MAJOR_PATTERN.search(output)
    if not home or not major:
        raise RuntimeError("Could not resolve effective java.home and major")
    path = Path(home.group(1)).absolute()
    if not path.is_absolute() or not path.is_dir():
        raise RuntimeError("Effective Java home is unavailable")
    return path, int(major.group(1))


def hash_link_target(link: Path, budget: list[int]) -> str:
    """Advisory only: bounded file bytes plus stable stat, not a safe cache key."""
    try:
        before = link.stat()
        if not stat.S_ISREG(before.st_mode):
            return "NOT_HASHED_NOT_REGULAR"
        if before.st_size > MAX_LINK_BYTES or budget[0] + before.st_size > MAX_TOTAL_BYTES:
            return "NOT_HASHED_BUDGET"
        digest = hashlib.sha256()
        total = 0
        with link.open("rb") as src:
            while True:
                block = src.read(65536)
                if not block:
                    break
                total += len(block)
                if total > MAX_LINK_BYTES or budget[0] + total > MAX_TOTAL_BYTES:
                    return "NOT_HASHED_BUDGET"
                digest.update(block)
        after = link.stat()
        if total != before.st_size or (before.st_dev, before.st_ino, before.st_mtime_ns, before.st_size) != (
                after.st_dev, after.st_ino, after.st_mtime_ns, after.st_size):
            return "NOT_HASHED_CHANGED"
        budget[0] += total
        return digest.hexdigest()
    except OSError:
        return "NOT_HASHED_UNAVAILABLE"


def inspect_tree(home: Path, *, entry_limit: int = MAX_ENTRIES) -> list[str]:
    """Do not traverse links. Report a bounded link topology, never raw targets."""
    root = home.absolute()
    if root.is_symlink() or not root.is_dir():
        raise RuntimeError("JDK root is symlinked or missing")
    observations: list[str] = []
    links = 0
    count = 0
    budget = [0]
    for scope in SCOPES:
        base = root / scope
        if base.is_symlink() or not base.is_dir():
            raise RuntimeError("JDK scope is symlinked or missing: " + scope)
        stack = [base]
        while stack:
            directory = stack.pop()
            with os.scandir(directory) as entries:
                items = sorted(entries, key=lambda entry: entry.name)
            for item in reversed(items):
                count += 1
                if count > entry_limit:
                    raise RuntimeError("entry limit exceeded")
                path = Path(item.path)
                if item.is_symlink():
                    links += 1
                    if links > MAX_LINKS:
                        raise RuntimeError("link limit exceeded")
                    name = redact_label(path.relative_to(root).as_posix())
                    label = sha(os.readlink(path).encode("utf-8", "surrogateescape"))
                    try:
                        effective = path.resolve(strict=True)
                        scope_label = "inside-jdk" if effective.is_relative_to(root) else "external"
                        if effective.is_file():
                            kind = "regular"
                            digest = hash_link_target(path, budget)
                        elif effective.is_dir():
                            kind, digest = "directory-not-followed", "NOT_HASHED"
                        else:
                            kind, digest = "special-refused", "NOT_HASHED"
                    except (OSError, RuntimeError):
                        kind, scope_label, digest = "dangling-or-cycle", "unresolved", "NOT_HASHED"
                    observations.append(f"link={name} kind={kind} targetScope={scope_label} linkTextSha256={label} effectiveSha256={digest}")
                elif item.is_dir(follow_symlinks=False):
                    stack.append(path)
                elif not item.is_file(follow_symlinks=False):
                    observations.append("special=" + redact_label(path.relative_to(root).as_posix()))
    lines = ["M3_PREMAVEN_HOST_JDK_V1", "homeSha256=" + sha(str(root).encode()),
             "cacertsIsLink=" + str((root / "lib/security/cacerts").is_symlink()).lower(),
             "regularModules=" + str((root / "lib/modules").is_file()).lower(),
             "linkCount=" + str(links), "scannedEntries=" + str(count),
             "linkedBytesRead=" + str(budget[0])]
    return lines + sorted(observations)


def host_observation(expected_major: int, environment: dict[str, str]) -> list[str]:
    home_env = environment.get("JAVA_HOME")
    path_java = shutil.which("java", path=environment.get("PATH"))
    if not home_env or not path_java:
        raise RuntimeError("Missing selected Java runtime or PATH java")
    selected = Path(home_env).absolute()
    selected_java = selected / "bin/java"
    if not selected_java.is_file():
        raise RuntimeError("Selected JDK java executable missing")
    selected_root, selected_major = java_properties(selected_java)
    path_root, path_major = java_properties(path_java)
    if selected_major != expected_major or path_major != expected_major:
        raise RuntimeError("Expected Java version differs from actual runtime")
    lines = ["M3_PREMAVEN_SELECTED_RUNTIME_V1",
             "jdkMajor=" + str(expected_major),
             "selectedJavaHomeSha256=" + sha(str(selected).encode()),
             "selectedRuntimeHomeSha256=" + sha(str(selected_root).encode()),
             "pathRuntimeHomeSha256=" + sha(str(path_root).encode()),
             "setupEqualsSelectedRuntime=" + str(selected == selected_root).lower(),
             "setupEqualsPathRuntime=" + str(selected == path_root).lower(),
             "selectedRuntimeEqualsPathRuntime=" + str(selected_root == path_root).lower(),
             "injectionVariableNames=" + injected_names(environment)]
    roots = [("selected-runtime", selected_root)]
    if selected_root != path_root:
        roots.append(("path-runtime", path_root))
    for label, root in roots:
        lines.append("beginScope=" + label)
        lines.extend(inspect_tree(root))
        lines.append("endScope=" + label)
    return lines


class BootstrapTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="m3-host-boot-")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.jdk = self.base / "jdk"
        for name in ("bin", "lib/security", "conf"):
            (self.jdk / name).mkdir(parents=True, exist_ok=True)
        (self.jdk / "bin/java").write_bytes(b"JAVA")
        (self.jdk / "lib/modules").write_bytes(b"MODULES")
        (self.jdk / "lib/security/cacerts").write_bytes(b"AAAA")
        (self.jdk / "release").write_bytes(b"JAVA_VERSION=21")

    def observed(self):
        return "\n".join(inspect_tree(self.jdk))

    def test_regular(self):
        self.assertIn("linkCount=0", self.observed())

    def test_external_regular_link(self):
        out = self.base / "trust"
        out.write_bytes(b"AAAA")
        (self.jdk / "lib/security/cacerts").unlink()
        (self.jdk / "lib/security/cacerts").symlink_to(out)
        s = self.observed()
        self.assertIn("cacertsIsLink=true", s)
        self.assertIn("targetScope=external", s)
        self.assertIn("effectiveSha256=" + sha(b"AAAA"), s)

    def test_internal_regular_link(self):
        cert = self.jdk / "lib/security/cacerts"
        cert.unlink()
        (self.jdk / "lib/security/private-target").write_bytes(b"AAAA")
        cert.symlink_to("private-target")
        self.assertIn("targetScope=inside-jdk", self.observed())

    def test_dangling_link(self):
        cert = self.jdk / "lib/security/cacerts"
        cert.unlink()
        cert.symlink_to("absent")
        self.assertIn("kind=dangling-or-cycle", self.observed())

    def test_cycle(self):
        cert = self.jdk / "lib/security/cacerts"
        cert.unlink()
        cert.symlink_to("cacerts")
        self.assertIn("kind=dangling-or-cycle", self.observed())

    def test_directory_link_no_traversal(self):
        external = self.base / "outside"
        external.mkdir()
        (external / "private-file").write_bytes(b"NO-WALK")
        (self.jdk / "lib/linked-directory").symlink_to(external, target_is_directory=True)
        result = self.observed()
        self.assertIn("directory-not-followed", result)
        self.assertNotIn("private-file", result)

    def test_same_size_time_change_rehash(self):
        external = self.base / "external"
        external.write_bytes(b"AAAA")
        cert = self.jdk / "lib/security/cacerts"
        cert.unlink()
        cert.symlink_to(external)
        before = self.observed()
        stamp = external.stat()
        external.write_bytes(b"BBBB")
        os.utime(external, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        self.assertNotEqual(before, self.observed())
        self.assertIn("effectiveSha256=" + sha(b"BBBB"), self.observed())

    def test_redact_injection_values(self):
        observed = injected_names({"LD_LIBRARY_PATH": "/secret/value", "DYLD_LIBRARY_PATH": "", "HOME": "/home/safe"})
        self.assertEqual("LD_LIBRARY_PATH", observed)
        self.assertNotIn("secret", observed)

    def test_entry_bound(self):
        with self.assertRaisesRegex(RuntimeError, "entry limit exceeded"):
            inspect_tree(self.jdk, entry_limit=1)

    def test_path_label_redaction(self):
        value = redact_label("danger\nvalue")
        self.assertTrue(value.startswith("HASH-"))
        self.assertNotIn("\n", value)

    def test_no_jdk_modification(self):
        before = {p.relative_to(self.jdk).as_posix(): (p.stat().st_size, p.stat().st_mtime_ns)
                  for p in self.jdk.rglob("*") if p.is_file()}
        self.observed()
        after = {p.relative_to(self.jdk).as_posix(): (p.stat().st_size, p.stat().st_mtime_ns)
                 for p in self.jdk.rglob("*") if p.is_file()}
        self.assertEqual(before, after)

    def test_java_properties_parse(self):
        text = "Property settings:\n    java.home = /opt/jdk21\n    java.specification.version = 21\n"
        self.assertEqual(HOME_PATTERN.search(text).group(1), "/opt/jdk21")
        self.assertEqual(MAJOR_PATTERN.search(text).group(1), "21")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    choices = parser.add_mutually_exclusive_group(required=True)
    choices.add_argument("--self-test", action="store_true")
    choices.add_argument("--inspect", action="store_true")
    parser.add_argument("--expected-major", type=int)
    args = parser.parse_args()
    if args.self_test:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(BootstrapTests)
        outcome = unittest.TextTestRunner(stream=sys.stderr, verbosity=1).run(suite)
        if not outcome.wasSuccessful():
            sys.exit(1)
        print("M3_PREMAVEN_HOST_BOOTSTRAP_SELFTEST=PASS " + str(outcome.testsRun) + " controls")
    else:
        if args.expected_major not in (17, 21):
            parser.error("--inspect requires --expected-major 17|21")
        print("\n".join(host_observation(args.expected_major, dict(os.environ))))
