// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Built-in check {@code stamp} (coding-agent law 9, naming): a change must not introduce a product stamp. Atom: one
 * working-tree delta. Pattern: guard.
 *
 * <p>A stamp is the product prefix (the letter m and the digit 3) used as a name: a path segment that is the prefix
 * alone or the prefix followed by a separator or a capital, and an identifier or label that starts with the prefix
 * in capitals followed by a capital letter, or in lower case followed by a hyphen or an underscore. The check
 * compares the working tree, tracked and untracked files, with the base commit and writes one row per finding to
 * {@value #TABLE}: {@code NEW_PATH} for a new path with a stamped segment that the base does not have,
 * {@code NEW_NAME} for an added line with a stamped name that no other line of the tree contains, and
 * {@code CARRIED} for an added line that repeats a name the tree already has. The first two are violations; carried
 * names are listed for a later rename and do not fail. Dotted package names are not names of this kind.
 */
final class StampCheck {
    static final String TABLE = "STAMP_GATE.tsv";
    static final String PREFIX = "STAMP_";
    private static final int EVIDENCE = 160;
    private static final long TEXT_LIMIT = 2L << 20;
    private static final Pattern SEGMENT = Pattern.compile("\\.?m3([-_.].*)?|M3[A-Z0-9].*");
    private static final Pattern NAME =
            Pattern.compile("(?<![A-Za-z0-9_.])(M3[A-Z][A-Za-z0-9_]*|m3[-_][A-Za-z0-9][A-Za-z0-9_-]*)");
    private static final Pattern HUNK = Pattern.compile("^@@ [^+]*\\+(\\d+)");

    private StampCheck() {
    }

    /** One added line: where it is and what it says. */
    private record Line(String path, int number, String text) { }

    static String check(Git git, Path root, Path out, String base, List<String> violations)
            throws IOException, InterruptedException {
        List<String> rows = new ArrayList<>();
        int paths = newPaths(git, root, base, rows, violations);
        int[] names = names(git, added(git, root, base), rows, violations);
        Ledger.write(out, TABLE, "finding\tpath\tline\tstamp\tevidence\n" + String.join("", rows));
        return "stamp: new paths " + paths + ", new names " + names[0] + ", carried " + names[1];
    }

    private static int newPaths(Git git, Path root, String base, List<String> rows, List<String> violations)
            throws IOException, InterruptedException {
        TreeSet<String> before = git.tree(base);
        int found = 0;
        for (String path : git.delta(base)) {
            if (before.contains(path) || !Files.exists(root.resolve(path))) continue;
            String stamped = stampedPrefix(path, before);
            if (stamped.isEmpty()) continue;
            String segment = stamped.substring(stamped.lastIndexOf('/') + 1);
            rows.add(row("NEW_PATH", path, "0", segment, stamped));
            violations.add(PREFIX + "NEW_PATH " + path + " " + segment);
            found++;
        }
        return found;
    }

    /** The shortest prefix of {@code path} that ends in a stamped segment and is not in the base tree, or empty. */
    private static String stampedPrefix(String path, TreeSet<String> before) {
        String[] segments = path.split("/");
        StringBuilder prefix = new StringBuilder();
        for (String segment : segments) {
            prefix.append(prefix.length() == 0 ? "" : "/").append(segment);
            if (SEGMENT.matcher(segment).matches() && !present(before, prefix.toString())) return prefix.toString();
        }
        return "";
    }

    private static boolean present(TreeSet<String> tree, String path) {
        String inside = tree.ceiling(path + "/");
        return tree.contains(path) || (inside != null && inside.startsWith(path + "/"));
    }

    /** Stamped names on added lines, each use once per line; returns the counts of new and carried rows. */
    private static int[] names(Git git, List<Line> added, List<String> rows, List<String> violations)
            throws IOException, InterruptedException {
        Map<String, Set<Line>> uses = new TreeMap<>();
        for (Line line : added) {
            Matcher match = NAME.matcher(line.text());
            while (match.find()) uses.computeIfAbsent(match.group(1), name -> new LinkedHashSet<>()).add(line);
        }
        int[] counts = new int[2];
        for (Map.Entry<String, Set<Line>> use : uses.entrySet()) {
            boolean carried = git.lines(use.getKey()) > use.getValue().size();
            counts[carried ? 1 : 0] += use.getValue().size();
            for (Line line : use.getValue()) {
                rows.add(row(carried ? "CARRIED" : "NEW_NAME", line.path(), Integer.toString(line.number()),
                        use.getKey(), line.text().strip()));
                if (!carried) violations.add(PREFIX + "NEW_NAME " + line.path() + ":" + line.number() + " " + use.getKey());
            }
        }
        return counts;
    }

    /** Lines the working tree adds to {@code base}: the added side of the patch, and every line of an untracked file. */
    private static List<Line> added(Git git, Path root, String base) throws IOException, InterruptedException {
        List<Line> out = new ArrayList<>();
        String path = null;
        String previous = "";
        int number = 0;
        for (String line : git.patch(base).split("\n", -1)) {
            Matcher hunk = HUNK.matcher(line);
            if (line.startsWith("+++ ") && previous.startsWith("--- ")) path = line.startsWith("+++ b/") ? line.substring(6) : null;
            else if (hunk.find()) number = Integer.parseInt(hunk.group(1));
            else if (line.startsWith("+") && path != null) out.add(new Line(path, number++, line.substring(1)));
            previous = line;
        }
        for (String untracked : git.untracked()) out.addAll(whole(root, untracked));
        return out;
    }

    /** Every line of a text file of at most 2 MiB; nothing for a larger, binary or unreadable file. */
    private static List<Line> whole(Path root, String path) throws IOException {
        Path file = root.resolve(path);
        if (!Files.isRegularFile(file) || Files.size(file) > TEXT_LIMIT) return List.of();
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (text.indexOf('\0') >= 0) return List.of();
        List<String> lines = Arrays.asList(text.split("\n", -1));
        return IntStream.range(0, lines.size())
                .mapToObj(i -> new Line(path, i + 1, lines.get(i))).collect(Collectors.toList());
    }

    private static String row(String finding, String path, String line, String stamp, String evidence) {
        String text = Ledger.clean(evidence);
        return finding + "\t" + Ledger.clean(path) + "\t" + line + "\t" + stamp + "\t"
                + text.substring(0, Math.min(EVIDENCE, text.length())) + "\n";
    }
}
