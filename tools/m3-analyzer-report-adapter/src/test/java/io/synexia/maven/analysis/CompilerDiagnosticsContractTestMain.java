/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * ECJ's XML log and javac's diagnostics normalised the same way. Fixtures are real: ECJ 3.45.0 and javac 21.0.9 on
 * the same two sources (src/demo/Bad.java, src/demo/Warn.java), with license header lines added and this machine's
 * folders neutralised ({@code @ROOT@} stands for the project root in the ECJ log). Both give two errors and five
 * warnings on the same
 * lines; ECJ rows carry column 0, javac rows the caret's column.
 */
public final class CompilerDiagnosticsContractTestMain {
    private static int passed;

    private CompilerDiagnosticsContractTestMain() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("m3-compiler-diagnostics-");
        try {
            ecjLog(root);
            javacLog(root);
            sameLinesFromBothCompilers(root);
            refusesUnsafeOrPartialLogs(root);
            System.out.println("PASS: " + passed + " ECJ/javac diagnostics contract cases");
        } finally {
            erase(root);
        }
    }

    private static void ecjLog(Path root) throws Exception {
        List<String> rows = rows(convert(root, AnalyzerXmlNormalizer.Format.ECJ, ecjFixture(root)));
        require(rows.equals(List.of(
                "ecj.TypeMismatch\tERROR\tsrc/demo/Bad.java\t6\t0\tType mismatch: cannot convert from String to int",
                "ecj.UndefinedMethod\tERROR\tsrc/demo/Bad.java\t7\t0\tThe method missing(int) is undefined for the type Bad",
                "ecj.RawTypeReference\tWARNING\tsrc/demo/Warn.java\t13\t0\tList is a raw type. References to generic"
                        + " type List<E> should be parameterized",
                "ecj.RawTypeReference\tWARNING\tsrc/demo/Warn.java\t14\t0\tArrayList is a raw type. References to"
                        + " generic type ArrayList<E> should be parameterized",
                "ecj.RawTypeReference\tWARNING\tsrc/demo/Warn.java\t14\t0\tList is a raw type. References to generic"
                        + " type List<E> should be parameterized",
                "ecj.UnsafeRawMethodInvocation\tWARNING\tsrc/demo/Warn.java\t15\t0\tType safety: The method add(Object)"
                        + " belongs to the raw type List. References to generic type List<E> should be parameterized",
                "ecj.UsingDeprecatedMethod\tWARNING\tsrc/demo/Warn.java\t22\t0\tThe method old() from the type Warn is"
                        + " deprecated")), "ECJ rows " + rows);
        passed++;
    }

    private static void javacLog(Path root) throws Exception {
        List<String> rows = rows(convert(root, AnalyzerXmlNormalizer.Format.JAVAC, fixture("javac-21.txt")));
        require(rows.equals(List.of(
                "javac.error\tERROR\tsrc/demo/Bad.java\t6\t17\tincompatible types: String cannot be converted to int",
                "javac.error\tERROR\tsrc/demo/Bad.java\t7\t16\tcannot find symbol symbol: method missing(int)"
                        + " location: class Bad",
                "javac.rawtypes\tWARNING\tsrc/demo/Warn.java\t13\t5\tfound raw type: List missing type arguments for"
                        + " generic class List<E> where E is a type-variable: E extends Object declared in interface List",
                "javac.rawtypes\tWARNING\tsrc/demo/Warn.java\t14\t9\tfound raw type: List missing type arguments for"
                        + " generic class List<E> where E is a type-variable: E extends Object declared in interface List",
                "javac.rawtypes\tWARNING\tsrc/demo/Warn.java\t14\t24\tfound raw type: ArrayList missing type arguments"
                        + " for generic class ArrayList<E> where E is a type-variable: E extends Object declared in class"
                        + " ArrayList",
                "javac.unchecked\tWARNING\tsrc/demo/Warn.java\t15\t16\tunchecked call to add(E) as a member of the raw"
                        + " type List where E is a type-variable: E extends Object declared in interface List",
                "javac.deprecation\tWARNING\tsrc/demo/Warn.java\t22\t13\told() in Warn has been deprecated")),
                "javac rows " + rows);
        String global = "warning: [options] system modules path not set in conjunction with -source 17\n"
                + "Note: src/demo/Warn.java uses or overrides a deprecated API.\n1 warning\n";
        require(rows(convert(root, AnalyzerXmlNormalizer.Format.JAVAC, global)).equals(List.of(
                "javac.note\tINFO\t\t0\t0\tsrc/demo/Warn.java uses or overrides a deprecated API.",
                "javac.options\tWARNING\t\t0\t0\tsystem modules path not set in conjunction with -source 17")),
                "project-wide javac diagnostics");
        passed++;
    }

    /** The two compilers agree on which file and line each problem is on, and on its severity. */
    private static void sameLinesFromBothCompilers(Path root) throws Exception {
        List<String> ecj = located(convert(root, AnalyzerXmlNormalizer.Format.ECJ, ecjFixture(root)));
        List<String> javac = located(convert(root, AnalyzerXmlNormalizer.Format.JAVAC, fixture("javac-21.txt")));
        require(ecj.equals(javac), "ECJ " + ecj + " javac " + javac);
        var first = convert(root, AnalyzerXmlNormalizer.Format.ECJ, ecjFixture(root));
        require(first.sha256().equals(convert(root, AnalyzerXmlNormalizer.Format.ECJ, ecjFixture(root)).sha256()),
                "repeatable digest");
        passed++;
    }

    private static void refusesUnsafeOrPartialLogs(Path root) throws Exception {
        String ecj = ecjFixture(root);
        refuses(root, AnalyzerXmlNormalizer.Format.ECJ, AnalyzerXmlNormalizer.Status.COMPLETE,
                ecj.replace("compiler_32_006.dtd\">", "compiler_32_006.dtd\" [<!ENTITY x \"y\">]>"), "internal subset");
        refuses(root, AnalyzerXmlNormalizer.Format.ECJ, AnalyzerXmlNormalizer.Status.COMPLETE,
                "<!DOCTYPE checkstyle SYSTEM \"file:///etc/passwd\"><compiler/>", "foreign DOCTYPE");
        refuses(root, AnalyzerXmlNormalizer.Format.ECJ, AnalyzerXmlNormalizer.Status.COMPLETE,
                ecj.replace(root.toString() + "/src/demo/Bad.java", "../outside/Bad.java"), "path outside the root");
        refuses(root, AnalyzerXmlNormalizer.Format.ECJ, AnalyzerXmlNormalizer.Status.COMPLETE,
                ecj.replace("severity=\"ERROR\"", "severity=\"FATAL\""), "unknown severity");
        refuses(root, AnalyzerXmlNormalizer.Format.ECJ, AnalyzerXmlNormalizer.Status.COMPLETE,
                "<checkstyle/>", "not an ECJ log");
        String crashed = "<compiler><exception class=\"java.lang.Error\" message=\"internal\"/></compiler>";
        refuses(root, AnalyzerXmlNormalizer.Format.ECJ, AnalyzerXmlNormalizer.Status.COMPLETE, crashed, "exception");
        require(rows(AnalyzerXmlNormalizer.normalize(AnalyzerXmlNormalizer.Format.ECJ,
                AnalyzerXmlNormalizer.Status.FAILED, root, crashed.getBytes(StandardCharsets.UTF_8)))
                .equals(List.of("ecj.exception\tERROR\t\t0\t0\tinternal")), "a failed ECJ run is one project-wide row");
        String partial = "src/demo/A.java:1: error: x\nclass A {\n^\n"
                + "only showing the first 1 errors, of 2 total; use -Xmaxerrs if you would like to see more\n";
        refuses(root, AnalyzerXmlNormalizer.Format.JAVAC, AnalyzerXmlNormalizer.Status.COMPLETE, partial, "truncated");
        require(rows(AnalyzerXmlNormalizer.normalize(AnalyzerXmlNormalizer.Format.JAVAC,
                AnalyzerXmlNormalizer.Status.PARTIAL, root, partial.getBytes(StandardCharsets.UTF_8))).size() == 1,
                "a truncated javac log is PARTIAL");
        refuses(root, AnalyzerXmlNormalizer.Format.JAVAC, AnalyzerXmlNormalizer.Status.COMPLETE,
                "../A.java:1: error: x\n", "javac path outside the root");
        passed++;
    }

    private static List<String> located(AnalyzerXmlNormalizer.Result result) {
        return rows(result).stream().map(row -> row.split("\t")).filter(cells -> !cells[2].isEmpty())
                .map(cells -> cells[1] + " " + cells[2] + ":" + cells[3]).distinct().sorted().toList();
    }

    private static AnalyzerXmlNormalizer.Result convert(Path root, AnalyzerXmlNormalizer.Format format, String text)
            throws IOException {
        return AnalyzerXmlNormalizer.normalize(format, AnalyzerXmlNormalizer.Status.COMPLETE, root,
                text.getBytes(StandardCharsets.UTF_8));
    }

    /** Report rows with the message decoded, after the header, status and column lines. */
    private static List<String> rows(AnalyzerXmlNormalizer.Result result) {
        List<String> rows = new ArrayList<>();
        String[] lines = new String(result.bytes(), StandardCharsets.UTF_8).split("\n");
        for (int i = 3; i < lines.length; i++) {
            String[] cells = lines[i].split("\t", -1);
            cells[5] = new String(Base64.getUrlDecoder().decode(cells[5]), StandardCharsets.UTF_8);
            rows.add(String.join("\t", cells));
        }
        return rows;
    }

    private static String ecjFixture(Path root) throws Exception {
        return fixture("ecj-3.45.0.xml").replace("@ROOT@", root.toString());
    }

    private static String fixture(String name) throws Exception {
        var url = CompilerDiagnosticsContractTestMain.class.getResource("/compiler/" + name);
        return Files.readString(Path.of(url.toURI()), StandardCharsets.UTF_8);
    }

    private static void refuses(Path root, AnalyzerXmlNormalizer.Format format, AnalyzerXmlNormalizer.Status status,
            String text, String label) {
        try {
            AnalyzerXmlNormalizer.normalize(format, status, root, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("must refuse " + label);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void erase(Path dir) throws IOException {
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(paths::add);
        }
        for (Path path : paths) Files.deleteIfExists(path);
    }
}
