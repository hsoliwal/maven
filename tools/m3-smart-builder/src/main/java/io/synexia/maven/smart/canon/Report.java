// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Run artifacts (M3-SCALE v3.0 sections 5 and 13). Atom: one artifact text. Pattern: template; every artifact is
 * rendered from recorded evidence only, so the final report can name nothing that was not executed.
 */
final class Report {
    /** Artifacts the gate writes after the output-contract check. */
    static final Set<String> GATE_WRITTEN = Set.of("VERIFY_CONTRACT.tsv", "FINAL_REPORT.md", "OUTPUT_CONTRACT.tsv");

    private Report() {
    }

    static String runContext(Packet packet, RunState state, Path root, Path runDir) {
        Map<String, String> rows = new TreeMap<>(packet.values);
        rows.put("canon.packet_identity", packet.identity);
        rows.put("canon.run_signature", state.signature);
        rows.put("canon.head", state.head);
        rows.put("canon.base", state.base);
        rows.put("canon.root", root.toString());
        rows.put("canon.run_dir", runDir.toString());
        rows.put("canon.shard_files", Integer.toString(state.shard.size()));
        rows.put("canon.cpus", Integer.toString(Runtime.getRuntime().availableProcessors()));
        rows.put("canon.os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        rows.put("canon.numa", "not-used");
        return Ledger.keyValues(rows);
    }

    static String provenance(Packet packet, RunState state, Path root, String headNow, String postDigest, String idempotence)
            throws IOException {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("gate_version", CanonGate.VERSION);
        rows.put("packet", packet.file.toString());
        rows.put("packet_sha256", Ledger.sha256(packet.bytes));
        rows.put("packet_identity", packet.identity);
        rows.put("source_doc", packet.sourceDoc.toString());
        rows.put("source_doc_sha256", Ledger.sha256(packet.sourceDoc));
        String resources = packet.value("compute_resources");
        if (!resources.isEmpty()) rows.put("compute_resources_sha256", Ledger.sha256(packet.resolve(resources)));
        rows.put("root", root.toString());
        rows.put("git_head_pre", state.head);
        rows.put("git_head_post", headNow);
        rows.put("git_base", state.base);
        rows.put("run_signature", state.signature);
        rows.put("pre_hash_digest", state.preDigest);
        rows.put("post_hash_digest", postDigest);
        rows.put("idempotence", idempotence);
        rows.put("atoms", state.atoms);
        if (!packet.checks.isEmpty()) rows.put("checks", state.checks);
        rows.put("java", Runtime.version().toString());
        return Ledger.keyValues(rows);
    }

    static String verifyContract(Packet packet, RunState state, Map<String, int[]> counts, List<String> violations) {
        StringBuilder out = new StringBuilder("kind\tname\tdeclared\tstate\texit\tevidence\terrors\twarnings\n");
        for (String stage : Packet.ORDER) {
            RunState.StageRun run = state.stages.stream().filter(s -> s.stage().equals(stage)).reduce((a, b) -> b).orElse(null);
            int[] count = counts.getOrDefault(stage, new int[2]);
            boolean declared = packet.verify.contains(stage);
            out.append("stage\t").append(stage).append('\t').append(declared ? Ledger.clean(packet.value("verify." + stage)) : "-")
                    .append('\t').append(run != null ? run.result() : declared ? "NOT_RUN" : "NOT_DECLARED").append('\t')
                    .append(run != null ? Integer.toString(run.exit()) : "-").append('\t').append(run != null ? run.log() : "-")
                    .append('\t').append(count[0]).append('\t').append(count[1]).append('\n');
        }
        for (Map.Entry<String, String> lock : Packet.BASELINE_LOCKS.entrySet()) {
            String prefix = switch (lock.getKey()) {
                case "DISABLED_PATHS" -> "DISABLED_PATH ";
                case "EXCLUDED_PATHS" -> "EXCLUDED_PATH ";
                case "BUILD_SCRIPTS" -> "BUILD_SCRIPT_LOCKED ";
                default -> "";
            };
            row(out, "lock", lock.getKey(), lock.getValue(), prefix.isEmpty() ? "DELEGATED_TO_DECLARED_STAGES"
                    : verdict(violations, prefix), prefix.isEmpty() ? "verify stages" : "canon-gate scope check");
        }
        row(out, "lock", "SCOPE", packet.dryRun ? "NO_CHANGES" : "TARGETS_ONLY",
                verdict(violations, "OUT_OF_SCOPE ", "DRY_RUN_MUTATION ", "HEAD_MOVED "), "canon-gate scope check");
        row(out, "atoms", "ATOM_PATTERN", "every changed file atomized and patternized",
                verdict(violations, "ATOM_", "PATTERN_"), AtomPattern.TABLE);
        packet.checks.forEach(id -> row(out, "check", id, "no finding",
                verdict(violations, Checks.PREFIXES.get(id)), "canon-gate built-in check"));
        return out.toString();
    }

    static String finalReport(Packet packet, RunState state, Path runDir, Set<String> delta, Set<String> mutated,
            Map<String, int[]> counts, List<String> violations) {
        StringBuilder out = new StringBuilder("# Canon run report\n\nRun signature `").append(state.signature)
                .append("`, packet `").append(packet.file).append("`, mode `").append(packet.value("run.mode"))
                .append("`, verdict `").append(violations.isEmpty() ? "PASS" : "FAIL").append("`.\n\n## Files changed\n\n");
        TreeSet<String> listed = new TreeSet<>(delta);
        listed.addAll(mutated);
        if (listed.isEmpty()) out.append("None.\n");
        listed.forEach(p -> out.append("- `").append(p).append("` ").append(delta.contains(p) ? "differs from base" : "restored to base")
                .append(mutated.contains(p) ? ", mutated during the run" : "").append(packet.inScope(p) ? ", in scope" : ", outside scope")
                .append('\n'));
        long inScope = delta.stream().filter(packet::inScope).count();
        out.append("\n## Exact delta summary\n\n").append(delta.size()).append(" path(s) differ from base `").append(state.base)
                .append("`, ").append(inScope).append(" in scope, ").append(mutated.size()).append(" mutated during the run; ")
                .append(state.atoms).append("; ").append(state.shard.size())
                .append(" shard file(s) hashed before and after; HEAD `").append(state.head).append("`.\n\n")
                .append("## Exact verification executed\n\n");
        if (state.stages.isEmpty()) out.append("None.\n");
        state.stages.forEach(s -> out.append("- `").append(s.stage()).append("` ").append(s.result()).append(", exit ")
                .append(s.exit()).append(", ").append(counts.getOrDefault(s.stage(), new int[2])[0]).append(" error(s), ")
                .append(counts.getOrDefault(s.stage(), new int[2])[1]).append(" warning(s); command `")
                .append(packet.value("verify." + s.stage())).append("`; log `").append(s.log()).append("`\n"));
        packet.verify.stream().filter(v -> state.stages.stream().noneMatch(s -> s.stage().equals(v)))
                .forEach(v -> out.append("- `").append(v).append("` NOT_RUN\n"));
        out.append("\n## Exact blockers/failures\n\n");
        if (violations.isEmpty()) out.append("None.\n");
        violations.forEach(v -> out.append("- ").append(v).append('\n'));
        out.append("\n## Artifact paths\n\n");
        packet.artifacts.forEach(a -> out.append("- `").append(runDir.resolve(a)).append("`\n"));
        return out.toString();
    }

    static String outputContract(Packet packet, Path runDir) throws IOException {
        StringBuilder out = new StringBuilder("artifact\tstate\tsha256\n");
        for (String artifact : packet.artifacts) {
            Path file = runDir.resolve(artifact);
            boolean self = artifact.equals("OUTPUT_CONTRACT.tsv");
            String hash = self ? "SELF" : artifact.equals("STATUS.tsv") ? "APPEND_ONLY"
                    : Files.isRegularFile(file) ? Ledger.sha256(file) : "-";
            out.append(artifact).append('\t').append(self || Files.isRegularFile(file) ? "PRESENT" : "MISSING").append('\t')
                    .append(hash).append('\n');
        }
        return out.toString();
    }

    private static String verdict(List<String> violations, String... prefixes) {
        for (String prefix : prefixes) if (violations.stream().anyMatch(v -> v.startsWith(prefix))) return "FAIL";
        return "PASS";
    }

    private static void row(StringBuilder out, String kind, String name, String declared, String state, String evidence) {
        out.append(kind).append('\t').append(name).append('\t').append(declared).append('\t').append(state).append("\t-\t")
                .append(evidence).append("\t-\t-\n");
    }
}
