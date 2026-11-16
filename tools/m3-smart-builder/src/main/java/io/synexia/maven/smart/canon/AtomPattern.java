// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Atomization and patternization proof (coding-agent laws 3, 4 and 14). Atom: one row of {@code ATOM_PATTERN.tsv}.
 * Pattern: guard. The {@code atom_pattern} stage writes the table with the columns {@code path, sha256, atom,
 * pattern, role}: every row must match the file as it is now, every pattern must be admitted by the packet's
 * {@code pattern_catalog} or be a typed residue written {@code RESIDUE:<reason>} ({@code UNKNOWN} is never
 * admitted), and every changed file needs at least one row.
 */
final class AtomPattern {
    static final String TABLE = "ATOM_PATTERN.tsv";
    static final String HEADER = "path\tsha256\tatom\tpattern\trole";

    private AtomPattern() {
    }

    /** Checks the table against the changed paths and returns a one-line summary for the report. */
    static String check(Path root, Path runDir, Set<String> changed, Set<String> admitted, List<String> violations)
            throws IOException {
        Path table = runDir.resolve(TABLE);
        if (!Files.isRegularFile(table)) return "no " + TABLE;
        List<String> lines = Files.readAllLines(table, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals(HEADER)) {
            violations.add("ATOM_PATTERN_SCHEMA header must be " + HEADER.replace('\t', ','));
            return "invalid " + TABLE;
        }
        Map<String, String> current = new TreeMap<>();
        Set<String> covered = new TreeSet<>();
        int residue = 0;
        for (int i = 1; i < lines.size(); i++) {
            String[] cell = lines.get(i).split("\t", -1);
            if (cell.length != 5 || cell[0].isEmpty() || !cell[1].matches("[0-9a-f]{64}") || cell[2].isBlank()
                    || cell[3].isBlank() || cell[4].isBlank()) {
                violations.add("ATOM_PATTERN_SCHEMA line " + (i + 1));
                continue;
            }
            String now = current.containsKey(cell[0]) ? current.get(cell[0]) : hash(root, cell[0]);
            current.put(cell[0], now);
            if (!cell[1].equals(now)) violations.add("ATOM_STALE " + cell[0] + " " + cell[2]);
            else if (cell[3].matches("(?i)UNKNOWN|RESIDUE:\\s*")) violations.add("PATTERN_UNKNOWN " + cell[0] + " " + cell[2]);
            else if (!cell[3].startsWith("RESIDUE:") && !admitted.contains(cell[3])) {
                violations.add("PATTERN_NOT_ADMITTED " + cell[0] + " " + cell[2] + " " + cell[3]);
            } else {
                covered.add(cell[0]);
                if (cell[3].startsWith("RESIDUE:")) residue++;
            }
        }
        changed.stream().filter(p -> Files.isRegularFile(root.resolve(p)) && !covered.contains(p))
                .forEach(p -> violations.add("ATOM_UNCOVERED " + p));
        return (lines.size() - 1) + " atom row(s) over " + covered.size() + " file(s), " + residue + " typed residue";
    }

    private static String hash(Path root, String path) throws IOException {
        Path file = root.resolve(path).normalize();
        return file.startsWith(root) && Files.isRegularFile(file) ? Ledger.sha256(file) : "-";
    }
}
