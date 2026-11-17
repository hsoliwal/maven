// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Which Java sources the regex spec covers ({@code make/data/m3regex/REGEX_SCOPE.tsv}). Atom: one
 * repository-relative path. Pattern: first matching rule; no regular expression.
 *
 * <p>Each row is {@code glob TAB scope TAB owner}. In a glob, {@code *} matches a run of
 * characters other than {@code /} and {@code **} as a whole segment matches any number of
 * segments, none included. The first glob that matches a path gives its scope:
 * {@code gated} (the check {@code regex_spec} refuses a new site without a row) or a
 * {@code baseline-} scope (rows are recorded, new sites are listed and do not fail). A path no
 * glob matches is outside the spec.
 */
final class RegexScope {
    static final String GATED = "gated";
    private static final String BASELINE = "baseline-";

    private record Rule(List<String> segments, String scope) { }

    private final List<Rule> rules;

    private RegexScope(List<Rule> rules) {
        this.rules = rules;
    }

    /** Rules of a scope table; comment lines, blank lines and the header are skipped. */
    static RegexScope parse(List<String> lines) {
        List<Rule> rules = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#") || line.startsWith("glob\t")) continue;
            String[] cell = line.split("\t", -1);
            if (cell.length < 2 || !(cell[1].equals(GATED) || cell[1].startsWith(BASELINE))) {
                throw new IllegalArgumentException("scope row needs glob, scope: " + line);
            }
            rules.add(new Rule(Arrays.asList(cell[0].split("/", -1)), cell[1]));
        }
        return new RegexScope(List.copyOf(rules));
    }

    /** Scope of {@code path}, or empty when no rule covers it. */
    String of(String path) {
        List<String> segments = Arrays.asList(path.split("/", -1));
        for (Rule rule : rules) if (matches(rule.segments(), segments)) return rule.scope();
        return "";
    }

    static boolean gated(String scope) {
        return scope.equals(GATED);
    }

    private static boolean matches(List<String> glob, List<String> path) {
        if (glob.isEmpty()) return path.isEmpty();
        List<String> rest = glob.subList(1, glob.size());
        if (glob.get(0).equals("**")) {
            for (int skip = 0; skip <= path.size(); skip++) {
                if (matches(rest, path.subList(skip, path.size()))) return true;
            }
            return false;
        }
        return !path.isEmpty() && segment(glob.get(0), path.get(0))
                && matches(rest, path.subList(1, path.size()));
    }

    /** Whether one glob segment, where {@code *} is any run of characters, matches a name. */
    static boolean segment(String glob, String name) {
        int star = glob.indexOf('*');
        if (star < 0) return glob.equals(name);
        if (!name.startsWith(glob.substring(0, star))) return false;
        int at = star;
        int from = star + 1;
        for (int next = glob.indexOf('*', from); next >= 0; next = glob.indexOf('*', from)) {
            int found = name.indexOf(glob.substring(from, next), at);
            if (found < 0) return false;
            at = found + next - from;
            from = next + 1;
        }
        String last = glob.substring(from);
        return name.length() - at >= last.length() && name.endsWith(last);
    }
}
