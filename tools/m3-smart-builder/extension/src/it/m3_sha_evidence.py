# SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
"""Parse only original lifecycle SHA-256 DEBUG evidence from real Maven logs.

These observations do not authorize reuse: they measure bytes that Maven has
already hashed, including the preflight scan a cold/dirty receipt can avoid.
No file contents are retained, and absence of evidence fails qualification.
"""
import re

_ANSI_ESCAPE = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
_HASH = re.compile(
    r"M3DB SHA-256 (?P<phase>outputs before|outputs after|before|after) "
    r"(?P<goal>.+?): (?P<files>[0-9]+) files, (?P<bytes>[0-9]+) payload bytes(?:\s|$)")
_SKIPPED = re.compile(
    r"M3DB SHA-256 outputs before (?P<goal>.+?): "
    r"skipped \(no matching input receipt\)")


def scan_evidence(log: str) -> dict:
    phases = {
        phase: {"scans": 0, "files_hashed": 0, "payload_bytes_read": 0}
        for phase in ("before", "after", "outputs before", "outputs after")
    }
    skipped = 0
    for raw_line in log.splitlines():
        line = _ANSI_ESCAPE.sub("", raw_line)
        record = _HASH.search(line)
        if record:
            entry = phases[record.group("phase")]
            entry["scans"] += 1
            entry["files_hashed"] += int(record.group("files"))
            entry["payload_bytes_read"] += int(record.group("bytes"))
            continue
        if _SKIPPED.search(line):
            skipped += 1
    return {"sha256_content_scans": phases, "old_output_preflight_skipped": skipped}


def qualify_cold_and_warm(cold: dict, warm: dict) -> dict:
    cold_phases = cold["sha256_content_scans"]
    warm_phases = warm["sha256_content_scans"]
    if cold_phases["before"]["scans"] < 1 or cold_phases["before"]["payload_bytes_read"] < 1:
        raise AssertionError("cold run did not expose a positive original input SHA-256 payload read")
    if cold_phases["outputs before"]["scans"] != 0:
        raise AssertionError("cold run pointlessly hashed pre-existing output contents")
    if cold["old_output_preflight_skipped"] < 1:
        raise AssertionError("cold run did not document a skipped old-output preflight")
    if warm_phases["before"]["scans"] < 1 or warm_phases["before"]["payload_bytes_read"] < 1:
        raise AssertionError("warm run lacked a positive fresh input SHA-256 payload read")
    if warm_phases["outputs before"]["scans"] < 1:
        raise AssertionError("warm run failed to rehash actual candidate outputs")
    if warm_phases["outputs before"]["payload_bytes_read"] < 1:
        raise AssertionError("warm output proof did not read original artifact payload bytes")
    return {
        "status": "PASSED",
        "cold": cold,
        "warm": warm,
        "old_output_scan_avoided_on_cold": True,
        "original_warm_output_sha_verified": True,
        "source_unchanged_rehash_skipped": False,
    }
