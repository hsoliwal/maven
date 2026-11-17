// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Built-in check {@code regex_spec} (owner decision 46: every regex carries a spec row, and a
 * regex without one fails the gate). Atom: one working-tree delta. Pattern: guard.
 *
 * <p>It reads the tables of {@code make/data/m3regex} from the working tree and validates every
 * spec row ({@link RegexSpec}). Then, for each Java file that the patch from the base adds to or
 * that is untracked, and that {@code REGEX_SCOPE.tsv} gates, it scans the file as it is now
 * ({@link RegexSites}) and judges every site with a line on the added side, one row per site in
 * {@value #TABLE}: {@code NO_ROW} when no spec row has the site's id; {@code MOVED} when the same
 * call text is on the removed side of the patch and the row exists; {@code NO_SITE} when the
 * file now has more sites of that call and id than {@code REGEX_SITES.tsv} lists for it;
 * {@code LISTED} otherwise. {@code NO_ROW}, {@code NO_SITE} and every row problem are
 * violations. Sites added to a baseline file are listed as {@code BASELINE} and do not fail.
 * Comments and literals are never sites. It reads the working tree and the base commit only.
 */
final class RegexSpecCheck {
    static final String TABLE = "REGEX_SPEC_GATE.tsv";
    static final String PREFIX = "REGEX_SPEC_";
    static final Set<String> FAILING = Set.of("NO_ROW", "NO_SITE", "INCOMPLETE", "KEY", "ORACLE",
            "DUPLICATE");
    private static final String HEADER = "finding\tpath\tline\tcall\tid\tdetail\n";

    private RegexSpecCheck() {
    }

    /** Added lines by new path, removed lines by old path, and the untracked files. */
    record Patch(Map<String, Set<Integer>> added, Map<String, Set<Integer>> removed,
            Set<String> untracked) { }

    static String check(Git git, Path root, Path out, String base, List<String> violations)
            throws IOException, InterruptedException {
        RegexSpec spec = RegexSpec.parse(table(root, RegexSpec.SPEC), table(root, RegexSpec.SITES),
                table(root, RegexSpec.SCOPE));
        List<String[]> rows = new ArrayList<>();
        spec.problems.forEach(p -> rows.add(new String[] {p.code(), RegexSpec.SPEC,
                Integer.toString(p.line()), "-", p.id(), p.detail()}));
        Patch patch = patch(git, base);
        Map<String, Integer> moved = removedCalls(git, base, patch.removed());
        TreeSet<String> changed = new TreeSet<>(patch.added().keySet());
        changed.addAll(patch.untracked());
        for (String path : changed) {
            String scope = spec.scope.of(path);
            Path file = root.resolve(path);
            if (!path.endsWith(".java") || scope.isEmpty() || !Files.isRegularFile(file)) continue;
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Set<Integer> lines = patch.untracked().contains(path) ? null : patch.added().get(path);
            judge(path, scope, RegexSites.scan(text), lines, spec, moved, rows);
        }
        rows.stream().filter(cell -> FAILING.contains(cell[0]))
                .forEach(cell -> violations.add(violation(cell)));
        Ledger.write(out, TABLE, tabbed(rows));
        return summary(rows, spec);
    }

    private static String table(Path root, String path) throws IOException {
        Path file = root.resolve(path);
        return Files.isRegularFile(file) ? Files.readString(file) : "";
    }

    /** One row for every site of {@code path} with a line in {@code lines} (all when null). */
    private static void judge(String path, String scope, List<RegexSites.Site> sites,
            Set<Integer> lines, RegexSpec spec, Map<String, Integer> moved, List<String[]> rows) {
        Map<String, Integer> present = new HashMap<>();
        sites.forEach(s -> present.merge(s.call() + "\t" + s.id(), 1, Integer::sum));
        for (RegexSites.Site site : sites) {
            if (lines != null && !touches(site, lines)) continue;
            boolean row = spec.rows.containsKey(site.id());
            boolean move = take(moved, site.expression());
            String finding;
            if (!RegexScope.gated(scope)) finding = "BASELINE";
            else if (!row) finding = "NO_ROW";
            else if (move) finding = "MOVED";
            else if (present.get(site.call() + "\t" + site.id())
                    > spec.listed(path, site.call(), site.id())) finding = "NO_SITE";
            else finding = "LISTED";
            rows.add(new String[] {finding, path, Integer.toString(site.line()), site.call(),
                    site.id(), scope + (row ? " row" : " no row")});
        }
    }

    private static boolean touches(RegexSites.Site site, Set<Integer> lines) {
        for (int line = site.line(); line <= site.lastLine(); line++) {
            if (lines.contains(line)) return true;
        }
        return false;
    }

    private static boolean take(Map<String, Integer> moved, String expression) {
        Integer left = moved.get(expression);
        if (left == null || left == 0) return false;
        moved.put(expression, left - 1);
        return true;
    }

    /** Call texts of the base's sites that have a line on the removed side, with their counts. */
    private static Map<String, Integer> removedCalls(Git git, String base,
            Map<String, Set<Integer>> removed) throws IOException, InterruptedException {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, Set<Integer>> file : removed.entrySet()) {
            if (!file.getKey().endsWith(".java")) continue;
            for (RegexSites.Site site : RegexSites.scan(git.show(base, file.getKey()))) {
                if (touches(site, file.getValue())) out.merge(site.expression(), 1, Integer::sum);
            }
        }
        return out;
    }

    /** The line numbers of the patch from {@code base} to the working tree, both sides. */
    static Patch patch(Git git, String base) throws IOException, InterruptedException {
        Map<String, Set<Integer>> added = new TreeMap<>();
        Map<String, Set<Integer>> removed = new TreeMap<>();
        String oldPath = null;
        String newPath = null;
        int[] at = {0, 0};
        boolean header = false;
        for (String line : git.patch(base).split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                header = true;
                oldPath = null;
                newPath = null;
            } else if (header && line.startsWith("--- ")) {
                oldPath = side(line, "--- a/");
            } else if (header && line.startsWith("+++ ")) {
                newPath = side(line, "+++ b/");
            } else if (line.startsWith("@@ ")) {
                header = false;
                at = hunk(line);
            } else if (!header && line.startsWith("+") && newPath != null) {
                added.computeIfAbsent(newPath, p -> new TreeSet<>()).add(at[1]++);
            } else if (!header && line.startsWith("-") && oldPath != null) {
                removed.computeIfAbsent(oldPath, p -> new TreeSet<>()).add(at[0]++);
            }
        }
        return new Patch(added, removed, git.untracked());
    }

    private static String side(String line, String prefix) {
        return line.startsWith(prefix) ? line.substring(prefix.length()) : null;
    }

    /** The first old and new line numbers of a hunk header {@code @@ -a,b +c,d @@}. */
    private static int[] hunk(String line) {
        return new int[] {number(line, line.indexOf('-')), number(line, line.indexOf('+'))};
    }

    private static int number(String line, int sign) {
        int end = sign + 1;
        while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
        return Integer.parseInt(line.substring(sign + 1, end));
    }

    private static String violation(String[] cell) {
        return PREFIX + cell[0] + " " + cell[1] + ":" + cell[2] + " " + cell[4]
                + (cell[3].equals("-") ? " " + cell[5] : "");
    }

    private static String tabbed(List<String[]> rows) {
        StringBuilder out = new StringBuilder(HEADER);
        for (String[] cell : rows) {
            for (int i = 0; i < cell.length; i++) {
                out.append(i == 0 ? "" : "\t").append(Ledger.clean(cell[i]));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String summary(List<String[]> rows, RegexSpec spec) {
        Map<String, Integer> counts = new TreeMap<>();
        rows.forEach(cell -> counts.merge(cell[0], 1, Integer::sum));
        return "regex spec: " + spec.rows.size() + " row(s) read, findings " + counts;
    }
}
