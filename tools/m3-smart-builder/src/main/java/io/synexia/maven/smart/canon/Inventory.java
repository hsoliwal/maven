// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Inventory, catalog and duplicate proposals (M3-SCALE v3.0 section 7). Atom: one inventoried file. Pattern: group by
 * content; exact clusters share a strong hash, normalized clusters share a whitespace-collapsed hash, and every
 * cluster becomes a proposal-only action carrying the required similarity note.
 */
final class Inventory {
    static final String SIMILAR = "These files may be similar. Please confirm via hash or diff.";
    static final Pattern TYPE = Pattern.compile("(?m)^\\s*(?:(?:public|protected|private|abstract|final|sealed"
            + "|non-sealed|static|strictfp)\\s+)*(?:class|interface|enum|record|@interface)\\s+([A-Za-z_$][\\w$]*)");
    private static final int SYMBOL_LIMIT = 8;

    private Inventory() {
    }

    static void write(Path root, Path runDir, List<String> files) throws IOException {
        StringBuilder rows = new StringBuilder("path\tmodule\tsymbols\tmodified\tfast_crc32\tstrong_sha256\tbytes\n");
        Map<String, List<String>> exact = new TreeMap<>();
        Map<String, List<String>> normalized = new TreeMap<>();
        Map<String, String> strongOf = new TreeMap<>();
        long bytes = 0;
        for (String path : files) {
            Path file = root.resolve(path);
            if (!Files.isRegularFile(file)) continue;
            byte[] data = Files.readAllBytes(file);
            bytes += data.length;
            String strong = Ledger.sha256(data);
            CRC32 crc = new CRC32();
            crc.update(data);
            String text = new String(data, StandardCharsets.ISO_8859_1);
            String norm = Ledger.sha256(text.replaceAll("\\s+", " ").strip().getBytes(StandardCharsets.ISO_8859_1));
            rows.append(path).append('\t').append(module(path)).append('\t').append(symbols(path, text)).append('\t')
                    .append(Files.getLastModifiedTime(file).toInstant()).append('\t')
                    .append(String.format("%08x", crc.getValue())).append('\t').append(strong).append('\t')
                    .append(data.length).append('\n');
            exact.computeIfAbsent(strong, k -> new ArrayList<>()).add(path);
            normalized.computeIfAbsent(norm, k -> new ArrayList<>()).add(path);
            strongOf.put(path, strong);
        }
        Ledger.write(runDir, "INVENTORY.tsv", rows.toString());
        clusters(runDir, exact, normalized, strongOf, files.size(), bytes);
    }

    private static void clusters(Path runDir, Map<String, List<String>> exact, Map<String, List<String>> normalized,
            Map<String, String> strongOf, int files, long bytes) throws IOException {
        StringBuilder exactRows = new StringBuilder("cluster\tmembers\tpath\tstrong_sha256\n");
        StringBuilder normRows = new StringBuilder("cluster\tmembers\tpath\tstrong_sha256\n");
        StringBuilder queue = new StringBuilder("priority\tkind\tcluster\tmembers\tnote\n");
        StringBuilder second = new StringBuilder();
        int exactClusters = 0;
        int duplicateFiles = 0;
        int normClusters = 0;
        for (Map.Entry<String, List<String>> e : exact.entrySet()) {
            if (e.getValue().size() < 2) continue;
            exactClusters++;
            duplicateFiles += e.getValue().size();
            cluster(exactRows, queue, "P1\tEXACT\t", "E-" + e.getKey().substring(0, 16), e.getValue(), strongOf);
        }
        for (Map.Entry<String, List<String>> e : normalized.entrySet()) {
            if (e.getValue().stream().map(strongOf::get).distinct().count() < 2) continue;
            normClusters++;
            cluster(normRows, second, "P2\tNORMALIZED\t", "N-" + e.getKey().substring(0, 16), e.getValue(), strongOf);
        }
        Ledger.write(runDir, "CLUSTERS_EXACT.tsv", exactRows.toString());
        Ledger.write(runDir, "CLUSTERS_NORMALIZED.tsv", normRows.toString());
        Ledger.write(runDir, "ACTION_QUEUE.tsv", queue.append(second).toString());
        Ledger.write(runDir, "SUMMARY.tsv", "metric\tvalue\nfiles\t" + files + "\nbytes\t" + bytes + "\nexact_clusters\t"
                + exactClusters + "\nexact_duplicate_files\t" + duplicateFiles + "\nnormalized_clusters\t" + normClusters
                + "\nactions\t" + (exactClusters + normClusters) + "\nmode\tproposal-only\n");
    }

    private static void cluster(StringBuilder rows, StringBuilder queue, String action, String id, List<String> members,
            Map<String, String> strongOf) {
        members.forEach(p -> rows.append(id).append('\t').append(members.size()).append('\t').append(p).append('\t')
                .append(strongOf.get(p)).append('\n'));
        queue.append(action).append(id).append('\t').append(members.size()).append('\t').append(SIMILAR).append('\n');
    }

    private static String module(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "." : path.substring(0, slash);
    }

    private static String symbols(String path, String text) {
        if (!path.endsWith(".java")) return "-";
        Matcher m = TYPE.matcher(text);
        List<String> names = new ArrayList<>();
        while (names.size() < SYMBOL_LIMIT && m.find()) names.add(m.group(1));
        return names.isEmpty() ? "-" : String.join(",", names);
    }
}
