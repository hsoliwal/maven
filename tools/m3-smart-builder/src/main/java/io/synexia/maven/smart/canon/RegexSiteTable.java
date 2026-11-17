// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Generator of {@code make/data/m3regex/REGEX_SITES.tsv} and full reconciliation of the regex
 * spec (owner decision 46). Atom: one tree. Pattern: census; every Java file that
 * {@code REGEX_SCOPE.tsv} covers is scanned by {@link RegexSites}.
 *
 * <p>Usage: {@code regex_sites <root> <out-dir> [<rev>]}. Without {@code <rev>} it reads the
 * working tree (tracked and untracked files); with it, the tree of that commit through one
 * {@code git cat-file --batch} process, so nothing is checked out or fetched. It writes to
 * {@code <out-dir>}: {@value #SITES_OUT} (the generated table, to be copied to
 * {@code make/data/m3regex}), {@value #CENSUS} (one row per site with its line),
 * {@value #TODO_ROWS} (skeleton spec rows for ids without a row), {@value #ORPHANS} (rows no site
 * uses) and {@value #PROBLEMS} (rows that fail validation). Exit status 0 when every site has a
 * valid row and no row is orphaned, 1 otherwise, 2 for a wrong invocation or a read failure.
 */
final class RegexSiteTable {
    static final String SITES_OUT = "REGEX_SITES.tsv";
    static final String CENSUS = "REGEX_CENSUS.tsv";
    static final String TODO_ROWS = "REGEX_SPEC_TODO.tsv";
    static final String ORPHANS = "REGEX_SPEC_ORPHANS.tsv";
    static final String PROBLEMS = "REGEX_SPEC_PROBLEMS.tsv";
    static final String COMMAND = "regex_sites";
    static final String USAGE = COMMAND + " <root> <out-dir> [<rev>]";
    private static final int LICENCE_LINES = 40;
    private static final String GPL_CPE = "GPL-2.0-only WITH Classpath-exception-2.0";
    private static final String SPDX = "SPDX-License-Identifier:";

    private RegexSiteTable() {
    }

    /** One site of the census with the scope and licence of its file. */
    record Found(String path, String scope, String licence, RegexSites.Site site) { }

    static int execute(String[] args, PrintStream log) throws InterruptedException {
        if (args.length < 3 || args.length > 4 || !Files.isDirectory(Path.of(args[1]))) {
            log.println("usage: CanonGate " + USAGE);
            return 2;
        }
        try {
            Path root = Path.of(args[1]).toAbsolutePath().normalize();
            Git git = new Git(root);
            String rev = args.length == 4 ? git.commit(args[3]) : "";
            Set<String> files = rev.isEmpty() ? worktree(git, root) : git.tree(rev);
            RegexSpec spec = RegexSpec.parse(table(git, root, rev, files, RegexSpec.SPEC),
                    table(git, root, rev, files, RegexSpec.SITES),
                    table(git, root, rev, files, RegexSpec.SCOPE));
            List<Found> found = census(git, root, rev, files, spec.scope);
            Path out = Files.createDirectories(Path.of(args[2]));
            return report(found, spec, out, log);
        } catch (IOException | RuntimeException failure) {
            log.println("canon-gate: CHECK_FAILED " + failure.getMessage());
            return 2;
        }
    }

    private static Set<String> worktree(Git git, Path root)
            throws IOException, InterruptedException {
        TreeSet<String> files = new TreeSet<>(git.files(List.of(), List.of(".")));
        files.addAll(git.untracked());
        files.removeIf(path -> !Files.isRegularFile(root.resolve(path)));
        return files;
    }

    /** A table of the tree, or the empty text when the tree does not have it. */
    private static String table(Git git, Path root, String rev, Set<String> files, String path)
            throws IOException, InterruptedException {
        if (!files.contains(path)) return "";
        return rev.isEmpty() ? Files.readString(root.resolve(path)) : git.show(rev, path);
    }

    /** Every site of every Java file in scope, by path and line. */
    static List<Found> census(Git git, Path root, String rev, Set<String> files, RegexScope scope)
            throws IOException, InterruptedException {
        List<String> java = files.stream().filter(p -> p.endsWith(".java"))
                .filter(p -> !scope.of(p).isEmpty()).toList();
        List<Found> out = new ArrayList<>();
        Git.BlobReader reader = (path, bytes) -> {
            String text = new String(bytes, StandardCharsets.UTF_8);
            String licence = licence(text);
            String within = scope.of(path);
            RegexSites.scan(text).forEach(site -> out.add(new Found(path, within, licence, site)));
        };
        if (!rev.isEmpty()) {
            git.blobs(rev, java, reader);
        } else {
            for (String path : java) reader.read(path, Files.readAllBytes(root.resolve(path)));
        }
        return out;
    }

    /** The SPDX identifier of the file header, a known licence notice, or UNDETERMINED. */
    static String licence(String text) {
        String head = String.join("\n", text.lines().limit(LICENCE_LINES).toList());
        int spdx = head.indexOf(SPDX);
        if (spdx >= 0) {
            int end = head.indexOf('\n', spdx);
            String value = head.substring(spdx + SPDX.length(), end < 0 ? head.length() : end);
            value = value.replace("*/", " ").strip();
            if (!value.isEmpty()) return value;
        }
        boolean gpl = head.contains("GNU General Public License") && head.contains("Classpath");
        if (gpl || head.contains("GPLv2 with the Classpath exception")) return GPL_CPE;
        boolean apache = head.contains("Apache License")
                || head.contains("Apache Software Foundation");
        return apache ? "Apache-2.0" : "UNDETERMINED";
    }

    private static int report(List<Found> found, RegexSpec spec, Path out, PrintStream log)
            throws IOException {
        Map<String, Integer> sites = new TreeMap<>();
        Map<String, Found> first = new TreeMap<>();
        Map<String, Boolean> whole = new TreeMap<>();
        StringBuilder census = new StringBuilder("path\tline\tcall\tkind\tid\tscope\tlicence"
                + "\tpattern\texpression\n");
        for (Found f : found) {
            RegexSites.Site s = f.site();
            sites.merge(RegexSpec.siteKey(f.path(), s.call(), s.id()) + "\t" + s.kind() + "\t"
                    + f.scope(), 1, Integer::sum);
            first.putIfAbsent(s.id(), f);
            whole.merge(s.id(), s.call().endsWith("matches"), Boolean::logicalAnd);
            census.append(f.path()).append('\t').append(s.line()).append('\t').append(s.call())
                    .append('\t').append(s.kind()).append('\t').append(s.id()).append('\t')
                    .append(f.scope()).append('\t').append(f.licence()).append('\t')
                    .append(s.pattern()).append('\t').append(Ledger.clean(s.expression()))
                    .append('\n');
        }
        Ledger.write(out, CENSUS, census.toString());
        Ledger.write(out, SITES_OUT, sitesTable(sites));
        int missing = todo(first, whole, spec, out);
        int orphans = orphans(first, spec, out);
        problems(spec, out);
        log.println("canon-gate: " + summary(found, first, missing, orphans, spec));
        return missing + orphans + spec.problems.size() == 0 ? 0 : 1;
    }

    private static String sitesTable(Map<String, Integer> sites) {
        StringBuilder table = new StringBuilder(RegexSpec.SITES_HEADER + "\n");
        sites.forEach((key, count) -> {
            String[] cell = key.split("\t", -1);
            table.append(cell[0]).append('\t').append(cell[1]).append('\t').append(cell[2])
                    .append('\t').append(count).append('\t').append(cell[3]).append('\t')
                    .append(cell[4]).append('\n');
        });
        return table.toString();
    }

    /** Writes a skeleton row for every id without a spec row; returns their number. */
    private static int todo(Map<String, Found> first, Map<String, Boolean> whole, RegexSpec spec,
            Path out) throws IOException {
        StringBuilder rows = new StringBuilder(RegexSpec.SPEC_HEADER + "\n");
        int missing = 0;
        for (Found f : first.values()) {
            RegexSites.Site s = f.site();
            if (spec.rows.containsKey(s.id())) continue;
            missing++;
            String todo = s.dynamic() ? RegexSpec.TODO : "TODO";
            String oracle = s.dynamic() ? RegexSpec.TODO
                    : whole.get(s.id()) ? RegexSpec.MATCHES : RegexSpec.FIND;
            String pattern = s.dynamic() ? RegexSpec.TODO + " " + Ledger.clean(s.expression())
                    : s.pattern();
            rows.append(String.join("\t", s.id(), pattern, RegexSites.flagNames(s.flags()),
                    RegexSpec.FLAVOUR, RegexSpec.LANGUAGE, oracle, todo, todo, f.path(),
                    f.licence())).append('\n');
        }
        Ledger.write(out, TODO_ROWS, rows.toString());
        return missing;
    }

    private static int orphans(Map<String, Found> first, RegexSpec spec, Path out)
            throws IOException {
        StringBuilder rows = new StringBuilder("id\tline\n");
        int orphans = 0;
        for (RegexSpec.Row row : spec.rows.values()) {
            if (first.containsKey(row.id())) continue;
            orphans++;
            rows.append(row.id()).append('\t').append(row.line()).append('\n');
        }
        Ledger.write(out, ORPHANS, rows.toString());
        return orphans;
    }

    private static void problems(RegexSpec spec, Path out) throws IOException {
        StringBuilder rows = new StringBuilder("code\tline\tid\tdetail\n");
        spec.problems.forEach(p -> rows.append(p.code()).append('\t').append(p.line()).append('\t')
                .append(p.id()).append('\t').append(Ledger.clean(p.detail())).append('\n'));
        Ledger.write(out, PROBLEMS, rows.toString());
    }

    private static String summary(List<Found> found, Map<String, Found> first, int missing,
            int orphans, RegexSpec spec) {
        Map<String, Integer> kinds = new TreeMap<>();
        Map<String, Integer> scopes = new TreeMap<>();
        found.forEach(f -> kinds.merge(f.site().kind(), 1, Integer::sum));
        found.forEach(f -> scopes.merge(f.scope(), 1, Integer::sum));
        long files = found.stream().map(Found::path).distinct().count();
        long dynamic = first.values().stream().filter(f -> f.site().dynamic()).count();
        return "regex sites: " + found.size() + " site(s) in " + files + " file(s) " + kinds
                + " " + scopes + ", " + first.size() + " row id(s) of which " + dynamic
                + " dynamic (TODO), " + missing + " without a row, " + orphans
                + " orphan row(s), " + spec.problems.size() + " row problem(s)";
    }
}
