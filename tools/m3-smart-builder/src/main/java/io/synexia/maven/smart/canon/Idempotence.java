// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Repeat-run comparison (M3-SCALE v3.0 section 6.4). Atom: a baseline run's provenance. Pattern: comparator; a run
 * with the same packet identity, HEAD and pre-hash digest as its baseline is a repeat and must end with the same
 * post-hash digest.
 */
final class Idempotence {
    private Idempotence() {
    }

    static String check(Packet packet, RunState state, String postDigest, List<String> violations) throws IOException {
        if (packet.value("baseline_run").isEmpty()) return "NO_BASELINE";
        Path baseline = packet.resolve(packet.value("baseline_run")).resolve("PROVENANCE.tsv");
        if (!Files.isRegularFile(baseline)) {
            violations.add("BASELINE_UNREADABLE " + baseline);
            return "BASELINE_UNREADABLE";
        }
        Map<String, String> before = Ledger.table(baseline);
        boolean repeat = packet.identity.equals(before.get("packet_identity")) && state.head.equals(before.get("git_head_pre"))
                && state.preDigest.equals(before.get("pre_hash_digest"));
        if (!repeat) return "NOT_A_REPEAT";
        if (postDigest.equals(before.get("post_hash_digest"))) return "REPEAT_MATCH";
        violations.add("NON_IDEMPOTENT post_hash_digest differs from " + baseline);
        return "REPEAT_MISMATCH";
    }
}
