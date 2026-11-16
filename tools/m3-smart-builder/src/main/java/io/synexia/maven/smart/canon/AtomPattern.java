// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Atomization and patternization proof (coding-agent laws 3, 4 and 14). Atom: one row of {@code ATOM_PATTERN.tsv}.
 * Pattern: guard. The {@code atom_pattern} stage writes the table with the columns {@code path, sha256, atom,
 * pattern, role, exact_hash, logic_hash, structure_hash, sim_hash}. Every row must match the file as it is now;
 * every pattern must be admitted by the packet's {@code pattern_catalog} or be a typed residue written
 * {@code RESIDUE:<reason>} ({@code UNKNOWN} is never admitted); every changed file needs at least one row; and every
 * atom of a Java source carries all four hash planes (64 hex digits for exact, logic and structure, 16 for the
 * SimHash), while other files may write {@code -}. Atoms that share a plane value are listed in
 * {@code ATOM_CLUSTERS.tsv} as proposals only.
 */
final class AtomPattern {
    static final String TABLE = "ATOM_PATTERN.tsv";
    static final String CLUSTERS = "ATOM_CLUSTERS.tsv";
    static final String HEADER = "path\tsha256\tatom\tpattern\trole\texact_hash\tlogic_hash\tstructure_hash\tsim_hash";
    static final String SIMILAR = "These atoms may be similar. Please confirm via hash or diff.";
    private static final String[] PLANES = {"EXACT", "LOGIC", "STRUCTURE", "SIMHASH"};
    private static final String[] FORMATS = {"[0-9a-f]{64}", "[0-9a-f]{64}", "[0-9a-f]{64}", "[0-9a-f]{16}"};

    private AtomPattern() {
    }

    /** Checks the table against the changed paths and returns a one-line summary for the report. */
    static String check(Path root, Path runDir, Set<String> changed, Set<String> admitted, List<String> violations)
            throws IOException {
        Path table = runDir.resolve(TABLE);
        if (!Files.isRegularFile(table, LinkOption.NOFOLLOW_LINKS)) {
            violations.add("ATOM_PATTERN_MISSING " + TABLE);
            return "no " + TABLE;
        }
        List<String> lines = Files.readAllLines(table, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals(HEADER)) {
            violations.add("ATOM_PATTERN_SCHEMA header must be " + HEADER.replace('\t', ','));
            return "invalid " + TABLE;
        }
        Map<String, String> current = new TreeMap<>();
        Map<String, List<String>> planes = new TreeMap<>();
        Set<String> covered = new TreeSet<>();
        int residue = 0;
        int hashed = 0;
        for (int i = 1; i < lines.size(); i++) {
            String[] cell = lines.get(i).split("\t", -1);
            if (cell.length != 9 || !canonicalPath(cell[0]) || !cell[1].matches("[0-9a-f]{64}") || cell[2].isBlank()
                    || cell[3].isBlank() || cell[4].isBlank() || !planesWellFormed(cell)) {
                violations.add("ATOM_PATTERN_SCHEMA line " + (i + 1));
                continue;
            }
            if (!ordinarySource(root, cell[0])) {
                violations.add("ATOM_NONREGULAR_ROW " + cell[0] + " " + cell[2]);
                continue;
            }
            String now = current.containsKey(cell[0]) ? current.get(cell[0]) : hash(root, cell[0]);
            current.put(cell[0], now);
            boolean complete = planeCount(cell) == PLANES.length;
            if (!cell[1].equals(now)) violations.add("ATOM_STALE " + cell[0] + " " + cell[2]);
            else if (cell[3].matches("(?i)UNKNOWN|RESIDUE:\\s*")) violations.add("PATTERN_UNKNOWN " + cell[0] + " " + cell[2]);
            else if (!cell[3].startsWith("RESIDUE:") && !admitted.contains(cell[3])) {
                violations.add("PATTERN_NOT_ADMITTED " + cell[0] + " " + cell[2] + " " + cell[3]);
            } else if (cell[0].endsWith(".java") && !complete) {
                violations.add("ATOM_PLANES_MISSING " + cell[0] + " " + cell[2]);
            } else {
                covered.add(cell[0]);
                if (cell[3].startsWith("RESIDUE:")) residue++;
                if (complete) hashed++;
                for (int p = 0; p < PLANES.length; p++) {
                    if (!cell[5 + p].equals("-")) {
                        planes.computeIfAbsent(PLANES[p] + "\t" + cell[5 + p], k -> new ArrayList<>()).add(cell[0] + "\t" + cell[2]);
                    }
                }
            }
        }
        // A deleted source, symlink, gitlink or directory has no admissible atom
        // representation in this version of the nine-column table. Refuse it
        // rather than silently treating the absence as complete coverage.
        for (String path : changed) {
            if (!ordinarySource(root, path)) {
                violations.add("ATOM_NONREGULAR_CHANGE " + path);
            } else if (!covered.contains(path)) {
                violations.add("ATOM_UNCOVERED " + path);
            }
        }
        int clusters = clusters(runDir, planes);
        return (lines.size() - 1) + " atom row(s) over " + covered.size() + " file(s), " + hashed
                + " with all hash planes, " + clusters + " plane cluster(s), " + residue + " typed residue";
    }

    private static boolean canonicalPath(String value) {
        if (value.isBlank() || value.length() > 4096 || value.startsWith("/")
                || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0
                || value.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        for (String component : value.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) return false;
        }
        return true;
    }

    /** Reject links at every component, not just a final symlinked file. */
    private static boolean ordinarySource(Path root, String path) {
        if (!canonicalPath(path) || Files.isSymbolicLink(root)) return false;
        Path current = root;
        for (String component : path.split("/")) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) return false;
        }
        return Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean planesWellFormed(String[] cell) {
        for (int p = 0; p < PLANES.length; p++) {
            if (!cell[5 + p].equals("-") && !cell[5 + p].matches(FORMATS[p])) return false;
        }
        return true;
    }

    private static int planeCount(String[] cell) {
        int present = 0;
        for (int p = 0; p < PLANES.length; p++) if (!cell[5 + p].equals("-")) present++;
        return present;
    }

    private static int clusters(Path runDir, Map<String, List<String>> planes) throws IOException {
        StringBuilder out = new StringBuilder("plane\thash\tmembers\tpath\tatom\tnote\n");
        int clusters = 0;
        for (Map.Entry<String, List<String>> e : planes.entrySet()) {
            if (e.getValue().size() < 2) continue;
            clusters++;
            e.getValue().forEach(member -> out.append(e.getKey()).append('\t').append(e.getValue().size()).append('\t')
                    .append(member).append('\t').append(SIMILAR).append('\n'));
        }
        Ledger.write(runDir, CLUSTERS, out.toString());
        return clusters;
    }

    private static String hash(Path root, String path) throws IOException {
        return ordinarySource(root, path) ? Ledger.sha256(root.resolve(path)) : "-";
    }
}
