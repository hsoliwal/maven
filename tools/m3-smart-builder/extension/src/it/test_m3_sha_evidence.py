# SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
"""Deterministic no-Maven regression for the real lifecycle SHA debug contract."""
import unittest

from m3_sha_evidence import scan_evidence, qualify_cold_and_warm


COLD = """
[DEBUG] M3DB SHA-256 before org.apache.maven.plugins:maven-compiler-plugin:compile: 8 files, 3840 payload bytes
[DEBUG] M3DB SHA-256 outputs before org.apache.maven.plugins:maven-compiler-plugin:compile: skipped (no matching input receipt)
[DEBUG] M3DB SHA-256 after org.apache.maven.plugins:maven-compiler-plugin:compile: 9 files, 3950 payload bytes
[DEBUG] M3DB SHA-256 outputs after org.apache.maven.plugins:maven-compiler-plugin:compile: 2 files, 2048 payload bytes
"""
WARM = """
[DEBUG] M3DB SHA-256 before org.apache.maven.plugins:maven-compiler-plugin:compile: 9 files, 3950 payload bytes
[DEBUG] M3DB SHA-256 outputs before org.apache.maven.plugins:maven-compiler-plugin:compile: 2 files, 2048 payload bytes
M3DB UP-TO-DATE maven-compiler-plugin:compile (library)
"""


class ScanEvidenceTest(unittest.TestCase):
    def test_full_hash_evidence(self):
        found = scan_evidence(COLD)
        self.assertEqual(found["old_output_preflight_skipped"], 1)
        self.assertEqual(found["sha256_content_scans"]["before"],
                         {"scans": 1, "files_hashed": 8, "payload_bytes_read": 3840})
        self.assertEqual(found["sha256_content_scans"]["outputs before"]["scans"], 0)
        self.assertEqual(found["sha256_content_scans"]["outputs after"]["payload_bytes_read"], 2048)

    def test_warm_output_bytes_and_no_source_skip_claim(self):
        observed = qualify_cold_and_warm(scan_evidence(COLD), scan_evidence(WARM))
        self.assertTrue(observed["original_warm_output_sha_verified"])
        self.assertFalse(observed["source_unchanged_rehash_skipped"])
        self.assertEqual(observed["warm"]["sha256_content_scans"]["before"]["payload_bytes_read"], 3950)

    def test_absence_fails_closed(self):
        with self.assertRaises(AssertionError):
            qualify_cold_and_warm(scan_evidence(""), scan_evidence(WARM))
        with self.assertRaises(AssertionError):
            qualify_cold_and_warm(scan_evidence(COLD), scan_evidence(""))

    def test_no_skipped_warm_output_attestation(self):
        warm = WARM.replace("2 files, 2048 payload bytes",
                            "skipped (no matching input receipt)")
        with self.assertRaises(AssertionError):
            qualify_cold_and_warm(scan_evidence(COLD), scan_evidence(warm))

    def test_unrelated_and_truncated_text_is_not_evidence(self):
        found = scan_evidence(
            "M3DB SHA-256 before goal: 4 files, -1 payload bytes\\n"
            "M3DB SHA-256 outputs before goal: 2 files, 30 bytes\\n"
            "timestamp-only no hash\\n")
        self.assertEqual(sum(v["scans"] for v in found["sha256_content_scans"].values()), 0)
        self.assertEqual(found["old_output_preflight_skipped"], 0)


if __name__ == "__main__":
    unittest.main()
