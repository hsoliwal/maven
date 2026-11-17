// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The regex spec tables of {@code make/data/m3regex} (owner decision 46). Atom: one spec row.
 * Pattern: table reader and validator; {@code java.util.regex} is the oracle and runs in
 * process.
 *
 * <p>{@value #SPEC} has one row per distinct pattern and flags, keyed by its content key
 * ({@link RegexSites#contentKey}), and one row per dynamic call text, keyed {@code dyn-}.
 * Patterns and examples are Java string literals. {@value #SITES} is generated: one row per
 * file, call and row id with the number of sites. {@value #SCOPE} says which files are gated.
 */
final class RegexSpec {
    static final String DIR = "make/data/m3regex/";
    static final String SPEC = DIR + "REGEX_SPEC.tsv";
    static final String SITES = DIR + "REGEX_SITES.tsv";
    static final String SCOPE = DIR + "REGEX_SCOPE.tsv";
    static final String SPEC_HEADER = "id\tpattern\tflags\tflavour\tlanguage_version\toracle"
            + "\tpositive_examples\tnegative_examples\tsource\tlicence";
    static final String SITES_HEADER = "path\tcall\tid\tcount\tkind\tscope";
    static final String FLAVOUR = "java.util.regex";
    static final String LANGUAGE = "Java SE 21";
    static final String FIND = "java.util.regex.Matcher#find";
    static final String MATCHES = "java.util.regex.Matcher#matches";
    static final String TODO = "TODO(dynamic)";
    private static final String[] FIELDS = SPEC_HEADER.split("\t");
    private static final int PATTERN = 1;
    private static final int FLAGS = 2;
    private static final int FLAVOUR_COLUMN = 3;
    private static final int LANGUAGE_COLUMN = 4;
    private static final int ORACLE = 5;
    private static final int POSITIVE = 6;
    private static final int NEGATIVE = 7;

    /** One spec row and its line in {@value #SPEC}. */
    record Row(int line, String[] cells) {
        String id() {
            return cells[0];
        }

        boolean dynamic() {
            return id().startsWith("dyn-");
        }
    }

    /** One problem of a row: its code ({@code INCOMPLETE}, {@code KEY}, ...), line and detail. */
    record Problem(String code, int line, String id, String detail) { }

    final Map<String, Row> rows;
    final List<Problem> problems;
    final Map<String, Integer> listed;
    final RegexScope scope;

    private RegexSpec(Map<String, Row> rows, List<Problem> problems, Map<String, Integer> listed,
            RegexScope scope) {
        this.rows = rows;
        this.problems = problems;
        this.listed = listed;
        this.scope = scope;
    }

    /** Reads the three tables from their text; every row is validated. */
    static RegexSpec parse(String spec, String sites, String scope) {
        Map<String, Row> rows = new LinkedHashMap<>();
        List<Problem> problems = new ArrayList<>();
        List<String> lines = spec.lines().toList();
        if (lines.isEmpty() || !lines.get(0).equals(SPEC_HEADER)) {
            problems.add(new Problem("INCOMPLETE", 1, "-", "header must be " + SPEC_HEADER));
        }
        for (int i = 1; i < lines.size(); i++) {
            Row row = new Row(i + 1, lines.get(i).split("\t", -1));
            if (row.cells().length != FIELDS.length) {
                problems.add(new Problem("INCOMPLETE", row.line(), row.cells()[0], "columns"));
            } else if (rows.putIfAbsent(row.id(), row) != null) {
                problems.add(new Problem("DUPLICATE", row.line(), row.id(), "id"));
            } else {
                validate(row, problems);
            }
        }
        RegexScope globs = RegexScope.parse(scope.lines().toList());
        return new RegexSpec(rows, problems, listed(sites), globs);
    }

    /** Listed site counts by {@link #siteKey}. */
    private static Map<String, Integer> listed(String sites) {
        Map<String, Integer> out = new TreeMap<>();
        sites.lines().skip(1).map(row -> row.split("\t", -1)).filter(cell -> cell.length >= 4)
                .forEach(cell -> out.merge(siteKey(cell[0], cell[1], cell[2]),
                        Integer.parseInt(cell[3]), Integer::sum));
        return out;
    }

    static String siteKey(String path, String call, String id) {
        return path + "\t" + call + "\t" + id;
    }

    int listed(String path, String call, String id) {
        return listed.getOrDefault(siteKey(path, call, id), 0);
    }

    /** Every field present; a literal row keyed by its content and its examples agreeing. */
    private static void validate(Row row, List<Problem> problems) {
        String[] cell = row.cells();
        for (int i = 0; i < FIELDS.length; i++) {
            if (cell[i].isBlank()) incomplete(row, FIELDS[i], problems);
        }
        if (!cell[FLAVOUR_COLUMN].equals(FLAVOUR)) {
            incomplete(row, FIELDS[FLAVOUR_COLUMN], problems);
        }
        if (!cell[LANGUAGE_COLUMN].equals(LANGUAGE)) {
            incomplete(row, FIELDS[LANGUAGE_COLUMN], problems);
        }
        if (row.dynamic()) {
            if (!cell[PATTERN].startsWith(TODO)) incomplete(row, "dynamic pattern", problems);
            return;
        }
        List<String> pattern;
        try {
            pattern = RegexSites.literals(cell[PATTERN]);
        } catch (RuntimeException malformed) {
            pattern = List.of();
        }
        int flags = RegexSites.flagValue(cell[FLAGS]);
        if (pattern.size() != 1 || flags < 0
                || !row.id().equals(RegexSites.contentKey(pattern.get(0), flags))) {
            problems.add(new Problem("KEY", row.line(), row.id(), "id is not the content key"));
            return;
        }
        oracle(row, pattern.get(0), flags, problems);
    }

    private static void incomplete(Row row, String field, List<Problem> problems) {
        problems.add(new Problem("INCOMPLETE", row.line(), row.id(), field));
    }

    /** Positive examples must satisfy the row's oracle and negative examples must not. */
    private static void oracle(Row row, String regex, int flags, List<Problem> problems) {
        String[] cell = row.cells();
        boolean whole = cell[ORACLE].equals(MATCHES);
        if (!whole && !cell[ORACLE].equals(FIND)) {
            problems.add(new Problem("INCOMPLETE", row.line(), row.id(), FIELDS[ORACLE]));
            return;
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex, flags);
        } catch (PatternSyntaxException invalid) {
            problems.add(new Problem("ORACLE", row.line(), row.id(), "pattern does not compile"));
            return;
        }
        examples(row, pattern, whole, POSITIVE, true, problems);
        examples(row, pattern, whole, NEGATIVE, false, problems);
    }

    private static void examples(Row row, Pattern pattern, boolean whole, int column,
            boolean expected, List<Problem> problems) {
        List<String> examples;
        try {
            examples = RegexSites.literals(row.cells()[column]);
        } catch (RuntimeException malformed) {
            examples = List.of();
        }
        if (examples.isEmpty()) incomplete(row, FIELDS[column], problems);
        for (String example : examples) {
            boolean hit = whole ? pattern.matcher(example).matches()
                    : pattern.matcher(example).find();
            if (hit != expected) {
                problems.add(new Problem("ORACLE", row.line(), row.id(),
                        FIELDS[column] + " " + RegexSites.quote(example)));
            }
        }
    }
}
