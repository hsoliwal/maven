// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Offline phases for the regex spec (owner decision 46), run by {@link CanonGateTest} on its
 * throw-away repository after its other phases: the site scanner, the content key, the literal
 * cells, the table validation, the generator {@code regex_sites} and the built-in check
 * {@code regex_spec}. Every case fails when the law it names is no longer enforced.
 */
final class RegexSpecTest {
    static final String CODE = "src/Rx.java";
    static final String VENDOR = "lib/vendor/V.java";
    static final String HEX = "[0-9a-f]{64}";
    static final String DYNAMIC_CALL = "Pattern.compile(regex)";
    private static final String E_ACUTE = String.valueOf((char) 0xe9);
    private static final String SMILE = new String(Character.toChars(0x1f600));
    private static final String SCOPE = "# fixture scope\nglob\tscope\towner\n"
            + "lib/vendor/**\tbaseline-provider\tvendored\nsrc/**\tgated\tproduct\n";
    private static final String RX = String.join("\n",
            "final class Rx {",
            "    boolean hex(String s) { return s.matches(\"" + HEX + "\"); }",
            "    String[] cells(String s) { return s.split(\"\\t\", -1); }",
            "    Object any(String regex) { return java.util.regex.Pattern.compile(regex); }",
            "}", "");
    private static final String VENDOR_TEXT =
            "final class V { boolean b(String s) { return s.matches(\"b+\"); } }\n";
    private static final String AZ =
            "class Az { boolean a(String s) { return s.matches(\"[a-z]+\"); } }\n";

    private RegexSpecTest() {
    }

    static void run() throws Exception {
        scanner();
        contentKey();
        literals();
        validation();
        generator();
        rowsAndSites();
        movedLines();
        notSites();
        tableAndUntracked();
        inPacket();
    }

    private static void scanner() {
        String text = String.join("\n",
                "final class S {",
                "    boolean hex(String s) { return s.matches(\"" + HEX + "\"); }",
                "    // s.matches(\"[a-z]+\") is a comment",
                "    /** {@code s.split(\"x\")} */",
                "    String text = \"s.split(\\\"x\\\")\";",
                "    String m(java.util.regex.Matcher m) { return m.replaceAll(\"x\"); }",
                "    String w(String s) { return s.replaceAll(\"\\\\s+\", \" \"); }",
                "    String[] t(String s) { return s.split(\"\\t\", -1); }",
                "    String[] u(String s) { return s.split(\"\\\\t\", -1); }",
                "    Object f() { return java.util.regex.Pattern.compile(\"a\" + \"b\",",
                "            java.util.regex.Pattern.CASE_INSENSITIVE | Pattern.MULTILINE); }",
                "    Object d(String regex) { return java.util.regex.Pattern.compile(regex); }",
                "    Object g(String x) { return Pattern.compile(x, flags()); }",
                "    boolean p(String s) { return Pattern.matches(\"a+\", s); }",
                "}");
        List<RegexSites.Site> sites = RegexSites.scan(text);
        same(List.of(2, 7, 8, 9, 10, 12, 13, 14),
                sites.stream().map(RegexSites.Site::line).toList(),
                "site lines: comments, literals and Matcher replacements are not sites");
        same(List.of("regex", "regex", "single-char-split", "literal-only", "literal-only",
                "dynamic", "dynamic", "regex"), sites.stream().map(RegexSites.Site::kind).toList(),
                "kinds");
        RegexSites.Site flagged = sites.get(4);
        same(RegexSites.contentKey("ab", 10), flagged.id(), "concatenated literal with flags");
        same(11, flagged.lastLine(), "a call over two lines ends on the second");
        same("\"ab\"", flagged.pattern(), "pattern as a Java literal");
        same(RegexSites.dynamicId(DYNAMIC_CALL), sites.get(5).id(), "dynamic key is the call text");
        same("Pattern.matches", sites.get(7).call(), "Pattern.matches call");
        same("\"\\t\"", sites.get(2).pattern(), "the tab character");
        same("\"\\\\t\"", sites.get(3).pattern(), "the escaped t");
        same(List.of(true, true, false, false, false), List.of(RegexSites.fastpath(","),
                RegexSites.fastpath("\\."), RegexSites.fastpath("\\t"), RegexSites.fastpath("."),
                RegexSites.fastpath("ab")), "split fastpath condition");
    }

    /** Vectors computed by jdk.m3.compiletime.M3ContentKey of M3jdk21 master. */
    private static void contentKey() {
        same("31c5bf53a928ef81:b3c19844893ccc93:12:0", RegexSites.contentKey(HEX, 0), "hex key");
        same("cbf29ce484222325:9ca066f1a4ab2eea:0:0", RegexSites.contentKey("", 0), "empty key");
        same("af64644c8602d3a5:c8635ef25443ec39:1:0", RegexSites.contentKey(E_ACUTE, 0),
                "e acute key");
        same("e5e45a0a241b88da:ddd460f21e3d2893:2:0", RegexSites.contentKey(SMILE, 0),
                "surrogate pair key");
        same("08be3c07b562713a:bcb8263badef8f52:2:10", RegexSites.contentKey("ab", 10),
                "flags key");
    }

    private static void literals() {
        String text = "\t\n\r\\\"" + E_ACUTE + SMILE + (char) 1 + "'";
        String u = "\\" + "u";
        same("\"\\t\\n\\r\\\\\\\"" + u + "00e9" + u + "d83d" + u + "de00" + u + "0001'\"",
                RegexSites.quote(text), "quote");
        same(List.of(text), RegexSites.literals(RegexSites.quote(text)), "quote round trip");
        same(List.of("a", "b, c"), RegexSites.literals("\"a\", \"b, c\""), "two literals");
        same(List.of(), RegexSites.literals("TODO"), "a cell that is not a literal");
        same("CASE_INSENSITIVE|MULTILINE", RegexSites.flagNames(10), "flag names");
        same(10, RegexSites.flagValue("CASE_INSENSITIVE|MULTILINE"), "flag value");
    }

    private static void validation() {
        String good = row(HEX, "-", RegexSpec.MATCHES, quote64(), "\"0\"");
        String key = good.replace(RegexSites.contentKey(HEX, 0), RegexSites.contentKey("x", 0));
        String oracle = row("a+", "-", RegexSpec.FIND, "\"b\"", "\"c\"");
        String empty = row("b+", "-", RegexSpec.FIND, "\"b\"", "");
        String text = String.join("\n", RegexSpec.SPEC_HEADER, good, key, oracle, empty,
                dynamicRow(), good, "");
        RegexSpec spec = RegexSpec.parse(text, RegexSpec.SITES_HEADER + "\n", SCOPE);
        same(List.of("KEY 3", "ORACLE 4", "INCOMPLETE 5", "INCOMPLETE 5", "DUPLICATE 7"),
                spec.problems.stream().map(p -> p.code() + " " + p.line()).toList(),
                "wrong key, failing example, missing examples and a repeated row");
        same(5, spec.rows.size(), "rows read");
        same("gated", spec.scope.of(CODE), "scope of a product file");
        same("baseline-provider", spec.scope.of(VENDOR), "scope of a vendored file");
        same("", spec.scope.of("other/X.java"), "a file outside the spec");
    }

    private static void generator() throws Exception {
        CanonGateTest.write(RegexSpec.SCOPE, SCOPE);
        CanonGateTest.write(CODE, RX);
        CanonGateTest.write(VENDOR, VENDOR_TEXT);
        CanonGateTest.write(RegexSpec.SPEC, spec(true));
        CanonGateTest.sh("git add -A && " + CanonGateTest.commit("regex"));
        Path out = CanonGateTest.base.resolve("regex-sites");
        CanonGateTest.expect(0, sites(out), "every site has a valid row");
        String table = Files.readString(out.resolve(RegexSiteTable.SITES_OUT));
        same(sitesTable(), table, "generated site table");
        Path atRev = CanonGateTest.base.resolve("regex-sites-rev");
        CanonGateTest.expect(0, sites(atRev, "HEAD"), "the same tree read from the commit");
        same(table, Files.readString(atRev.resolve(RegexSiteTable.SITES_OUT)),
                "commit and tree agree");
        CanonGateTest.write(RegexSpec.SPEC, spec(false));
        CanonGateTest.expect(1, sites(out), "a site without a row");
        CanonGateTest.contains(out.resolve(RegexSiteTable.TODO_ROWS), "\"b+\"", "skeleton row");
        String orphan = row("zz", "-", RegexSpec.FIND, "\"zz\"", "\"z\"");
        CanonGateTest.write(RegexSpec.SPEC, spec(true) + orphan + "\n");
        CanonGateTest.expect(1, sites(out), "a row no site uses");
        CanonGateTest.contains(out.resolve(RegexSiteTable.ORPHANS), RegexSites.contentKey("zz", 0),
                "orphan listed");
        CanonGateTest.write(RegexSpec.SPEC, spec(true).replace("\"bb\"", "\"ab\""));
        CanonGateTest.expect(1, sites(out), "an example the oracle refuses");
        CanonGateTest.contains(out.resolve(RegexSiteTable.PROBLEMS), "ORACLE\t", "oracle finding");
        CanonGateTest.restore();
        CanonGateTest.write(RegexSpec.SITES, table);
        CanonGateTest.sh("git add -A && " + CanonGateTest.commit("regex-sites"));
    }

    /** A new site needs a spec row and a listed site; with both it passes. */
    private static void rowsAndSites() throws Exception {
        String key = RegexSites.contentKey("[a-z]+", 0);
        String at = CODE + "\t6\tmatches\t" + key;
        CanonGateTest.append(CODE, AZ);
        CanonGateTest.expect(1, gate(), "a new regex without a row");
        CanonGateTest.contains(gateTable(), "NO_ROW\t" + at, "row missing");
        String row = row("[a-z]+", "-", RegexSpec.MATCHES, "\"az\"", "\"A\"");
        CanonGateTest.append(RegexSpec.SPEC, row + "\n");
        CanonGateTest.expect(1, gate(), "a row but no listed site");
        CanonGateTest.contains(gateTable(), "NO_SITE\t" + at, "site missing");
        CanonGateTest.append(RegexSpec.SITES, CODE + "\tmatches\t" + key + "\t1\tregex\tgated\n");
        CanonGateTest.expect(0, gate(), "a row and a listed site");
        CanonGateTest.contains(gateTable(), "LISTED\t" + at, "site listed");
        CanonGateTest.append(CODE, AZ.replace("Az", "Bz"));
        CanonGateTest.expect(1, gate(), "a second site of the same pattern is not listed");
        CanonGateTest.restore();
        Path dynamic = CanonGateTest.base.resolve("dynamic-out");
        CanonGateTest.append(CODE, "class D { Object d(String o) { return Pattern.compile(o); } }"
                + "\n");
        CanonGateTest.expect(1, gate(dynamic), "a new dynamic site without a row");
        CanonGateTest.contains(dynamic.resolve(RegexSpecCheck.TABLE), "NO_ROW\t" + CODE
                + "\t6\tPattern.compile\t" + RegexSites.dynamicId("Pattern.compile(o)"),
                "dynamic site keyed by its call text");
        CanonGateTest.restore();
    }

    /** A site moved to another file passes with its row; the same line copied fails. */
    private static void movedLines() throws Exception {
        String moved = "src/Moved.java";
        String hexLine = "    boolean hex(String s) { return s.matches(\"" + HEX + "\"); }\n";
        CanonGateTest.write(moved, "final class Moved {\n" + hexLine + "}\n");
        CanonGateTest.expect(1, gate(), "a copied site in a file the site table does not list");
        CanonGateTest.contains(gateTable(), "NO_SITE\t" + moved + "\t2\tmatches",
                "a copy is not a move");
        CanonGateTest.write(CODE, RX.replace(hexLine, ""));
        CanonGateTest.expect(0, gate(), "a moved site with its row");
        CanonGateTest.contains(gateTable(), "MOVED\t" + moved + "\t2\tmatches\t"
                + RegexSites.contentKey(HEX, 0), "moved site listed");
        CanonGateTest.restore();
    }

    /** Comments, literals and Matcher replacements add no site; baseline files never fail. */
    private static void notSites() throws Exception {
        CanonGateTest.append(CODE, "// s.matches(\"[a-z]+\") in a comment\n"
                + "class Q { String t = \"s.matches(\\\"[a-z]+\\\")\";\n"
                + "    String m(java.util.regex.Matcher m) { return m.replaceAll(\"x\"); } }\n");
        CanonGateTest.expect(0, gate(), "comment, string and Matcher replacement");
        same(1, Files.readAllLines(gateTable()).size(), "no site judged");
        CanonGateTest.restore();
        CanonGateTest.append(VENDOR, AZ);
        CanonGateTest.expect(0, gate(), "a new site in a vendored file");
        CanonGateTest.contains(gateTable(), "BASELINE\t" + VENDOR + "\t2\tmatches",
                "baseline site listed");
        CanonGateTest.restore();
    }

    /** Every row is validated, and an untracked file is all added lines. */
    private static void tableAndUntracked() throws Exception {
        CanonGateTest.write(RegexSpec.SPEC, spec(true).replace("\"a\\tb\"", "\"ab\""));
        CanonGateTest.expect(1, gate(), "an example the oracle refuses");
        CanonGateTest.contains(gateTable(), "ORACLE\t" + RegexSpec.SPEC + "\t3\t-\t"
                + RegexSites.contentKey("\t", 0), "row problem with its table line");
        CanonGateTest.restore();
        CanonGateTest.write("src/New.java", "final class New {\n"
                + "    boolean n(String s) { return s.matches(\"x+y\"); }\n}\n");
        CanonGateTest.expect(1, gate(), "an untracked file with a site without a row");
        CanonGateTest.contains(gateTable(), "NO_ROW\tsrc/New.java\t2\tmatches", "untracked site");
        CanonGateTest.restore();
        String repo = CanonGateTest.repo.toString();
        String out = CanonGateTest.base.resolve("regex-spec").toString();
        CanonGateTest.expect(2, CanonGate.execute(new String[] {"regex_spec", repo, "HEAD"},
                CanonGateTest.quiet()), "missing output directory");
        String[] names = {"regex_spec", repo, "HEAD", out, "x"};
        CanonGateTest.expect(2, CanonGate.execute(names, CanonGateTest.quiet()),
                "names given to a check that takes none");
    }

    /** Listed in a packet, the check fails lint and its finding is a build log row. */
    private static void inPacket() throws Exception {
        CanonGateTest.append(CODE, AZ);
        Map<String, String> p = packet(CODE);
        p.put("verify.compile", "touch \"$CANON_RUN_DIR/compiled\"");
        CanonGateTest.expect(1, CanonGateTest.run(p, "regex-no-row"),
                "packet with a site without a row");
        Path dir = CanonGateTest.base.resolve("runs/regex-no-row");
        String key = RegexSites.contentKey("[a-z]+", 0);
        CanonGateTest.contains(dir.resolve("FINAL_REPORT.md"),
                "REGEX_SPEC_NO_ROW " + CODE + ":6 " + key, "violation in the final report");
        CanonGateTest.contains(dir.resolve("BUILD_LOG.tsv"),
                "lint\t" + CODE + "\t6\t-\terror\tREGEX_SPEC_NO_ROW " + key,
                "finding is a build log row");
        CanonGateTest.contains(dir.resolve("STATUS.tsv"), "\tlint\tFAIL\t", "finding fails lint");
        if (Files.exists(dir.resolve("compiled"))) {
            throw new AssertionError("compile ran after a regex spec finding");
        }
        CanonGateTest.restore();
        CanonGateTest.append(CODE, "// a comment only\n");
        CanonGateTest.expect(0, CanonGateTest.run(packet(CODE), "regex-clean"),
                "packet with no new site");
        CanonGateTest.contains(CanonGateTest.base.resolve("runs/regex-clean/VERIFY_CONTRACT.tsv"),
                "check\tregex_spec\tno finding\tPASS", "check row in the verify contract");
        CanonGateTest.restore();
    }

    private static Map<String, String> packet(String... changed) throws IOException {
        Map<String, String> p = CanonGateTest.apply(CanonGateTest.atoms("VALUE_OBJECT", changed));
        p.put("targets.modules", "src,lib,make");
        p.put("checks", "regex_spec");
        return p;
    }

    private static int gate(Path... out) {
        Path dir = out.length == 0 ? CanonGateTest.base.resolve("regex-spec") : out[0];
        return CanonGate.execute(new String[] {"regex_spec", CanonGateTest.repo.toString(), "HEAD",
                dir.toString()}, CanonGateTest.quiet());
    }

    private static Path gateTable() {
        return CanonGateTest.base.resolve("regex-spec").resolve(RegexSpecCheck.TABLE);
    }

    private static int sites(Path out, String... rev) {
        String repo = CanonGateTest.repo.toString();
        String[] args = rev.length == 0
                ? new String[] {RegexSiteTable.COMMAND, repo, out.toString()}
                : new String[] {RegexSiteTable.COMMAND, repo, out.toString(), rev[0]};
        return CanonGate.execute(args, CanonGateTest.quiet());
    }

    /** The fixture's spec table, with or without the vendored row. */
    static String spec(boolean vendored) {
        return RegexSpec.SPEC_HEADER + "\n"
                + row(HEX, "-", RegexSpec.MATCHES, quote64(), "\"\", \"0\"") + "\n"
                + row("\t", "-", RegexSpec.FIND, "\"a\\tb\"", "\"ab\"") + "\n"
                + dynamicRow() + "\n"
                + (vendored ? row("b+", "-", RegexSpec.MATCHES, "\"bb\"", "\"a\"") + "\n" : "");
    }

    static String row(String regex, String flags, String oracle, String positive, String negative) {
        return String.join("\t", RegexSites.contentKey(regex, RegexSites.flagValue(flags)),
                RegexSites.quote(regex), flags, RegexSpec.FLAVOUR, RegexSpec.LANGUAGE, oracle,
                positive, negative, CODE, "Apache-2.0");
    }

    private static String quote64() {
        return "\"" + "0".repeat(64) + "\"";
    }

    private static String dynamicRow() {
        String todo = RegexSpec.TODO;
        return String.join("\t", RegexSites.dynamicId(DYNAMIC_CALL), todo + " " + DYNAMIC_CALL, "-",
                RegexSpec.FLAVOUR, RegexSpec.LANGUAGE, todo, todo, todo, CODE, "Apache-2.0");
    }

    private static String sitesTable() {
        return RegexSpec.SITES_HEADER + "\n"
                + VENDOR + "\tmatches\t" + RegexSites.contentKey("b+", 0)
                + "\t1\tregex\tbaseline-provider\n"
                + CODE + "\tPattern.compile\t" + RegexSites.dynamicId(DYNAMIC_CALL)
                + "\t1\tdynamic\tgated\n"
                + CODE + "\tmatches\t" + RegexSites.contentKey(HEX, 0) + "\t1\tregex\tgated\n"
                + CODE + "\tsplit\t" + RegexSites.contentKey("\t", 0)
                + "\t1\tsingle-char-split\tgated\n";
    }

    static void same(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + ": expected [" + expected + "] but was [" + actual
                    + "]");
        }
        CanonGateTest.checks++;
    }
}
