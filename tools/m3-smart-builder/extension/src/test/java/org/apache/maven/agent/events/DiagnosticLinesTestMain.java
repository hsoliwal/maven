/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.agent.events;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;

/**
 * Located diagnostics in compiler, Checkstyle, javac and gcc output and in mojo failure messages; U+2028, U+2029 and
 * U+0085 end a line like LF, and adversarial 1 MB lines of every format parse within a time bound; under {@code -T}
 * a diagnostic takes the mojo of its own module's last header.
 */
public final class DiagnosticLinesTestMain {
    /**
     * Time bound of one parse of a 1 MB adversarial line (fastest of three tries). Linear parsing takes about 20 ms;
     * the bound is generous for a loaded machine. The patterns this replaces needed over 60 s for such a line.
     */
    static final long BOUND_MILLIS = 1000;
    private static final int MB = 1 << 20;
    /** One unit of each line format, repeated to a megabyte before a line separator and a last character. */
    private static final Map<String, String> SHAPES = Map.of("maven", "a.b:[1,2] x ", "javac", "a.java:1: error: x ",
            "gcc", "a.c:1:2: warning: x ", "checkstyle", "a.b:1: ");
    private static final List<String> ENDS = List.of(" x", " x", " x", "\u0085x");

    private DiagnosticLinesTestMain() {}

    public static void main(String[] args) {
        formats();
        failureMessage();
        logContext();
        notDiagnostics();
        separators();
        interleaved();
        werror();
        SHAPES.forEach((name, unit) -> ENDS.forEach(end -> bounded(name, unit, end)));
        bounded("werror", "a.java: warnings found and -Werror specified ", " x");
        manyLines();
        Check.done("DiagnosticLinesTestMain");
    }

    /** javac's {@code <file>: warnings found and -Werror specified} names a file and no line: a file-only diagnostic. */
    private static void werror() {
        List<DiagnosticLines.Raw> found = DiagnosticLines.parse("Compilation failure\n/C:/r/prof/src/main/java/fx/Uses"
                + ".java: warnings found and -Werror specified\nC:\\r\\b\\B.java: warnings found and -Werror specified",
                DiagnosticLines.ERROR, "compiler:compile", "prof");
        Check.equal(List.of(new DiagnosticLines.Raw("ERROR", "C:/r/prof/src/main/java/fx/Uses.java", 0, 0, "",
                "warnings found and -Werror specified", "compiler:compile", "prof"), new DiagnosticLines.Raw("ERROR",
                "C:/r/b/B.java", 0, 0, "", "warnings found and -Werror specified", "compiler:compile", "prof")), found,
                "file-only -Werror diagnostics, line and column unknown");
        Check.equal(List.of(), DiagnosticLines.parse("Uses.java: something else", DiagnosticLines.ERROR, "", ""),
                "no other file-only line is a diagnostic");
    }

    /** U+2028, U+2029 and U+0085 end a line, so two diagnostics joined by one are two diagnostics. */
    private static void separators() {
        List.of(" ", " ", "\u0085").forEach(separator -> {
            List<DiagnosticLines.Raw> found = DiagnosticLines.parse("[ERROR] /p/A.java:[1,2] first" + separator
                    + "[WARNING] /p/B.java:[3,4] second", DiagnosticLines.ERROR, "", "");
            Check.equal(List.of("first", "second"), found.stream().map(DiagnosticLines.Raw::message)
                    .collect(java.util.stream.Collectors.toList()), "split at U+" + Integer.toHexString(separator.charAt(0)));
        });
    }

    /**
     * Under {@code -T} the headers of two modules interleave: a diagnostic of module a takes a's last mojo, not the
     * header printed last (b's), when its file belongs to a.
     */
    private static void interleaved() {
        DiagnosticLines reader = new DiagnosticLines(DiagnosticLines.ERROR, "", "",
                file -> file.startsWith("/r/a/") ? Optional.of("a") : Optional.empty());
        List.of("[INFO] --- compiler:3.13.0:compile (default-compile) @ a ---",
                "[INFO] --- surefire:3.2.5:test (default-test) @ b ---",
                "[WARNING] /r/a/src/main/java/A.java:[3,29] old() in fix.Core has been deprecated",
                "[WARNING] /r/x/X.java:[1,1] unowned").forEach(reader::accept);
        List<DiagnosticLines.Raw> found = reader.found();
        Check.equal(List.of("compiler:compile", "a"), List.of(found.get(0).mojo(), found.get(0).artifactId()),
                "the module's own last header");
        Check.equal(List.of("surefire:test", "b"), List.of(found.get(1).mojo(), found.get(1).artifactId()),
                "a file of no module keeps the last header");
    }

    /** A 1 MB line of {@code unit}, then {@code end}, parses within {@link #BOUND_MILLIS}. */
    private static void bounded(String name, String unit, String end) {
        String line = (name.equals("checkstyle") ? "[WARN] " : "") + unit.repeat(MB / unit.length()) + end;
        long millis = LongStream.range(0, 3).map(i -> millis(() -> DiagnosticLines.parse(line, DiagnosticLines.ERROR,
                "", ""))).min().orElseThrow();
        Check.that(millis < BOUND_MILLIS, name + " + U+" + Integer.toHexString(end.charAt(0)) + ": " + millis
                + " ms (bound " + BOUND_MILLIS + " ms)");
    }

    /** A log of 128 adversarial 8 KB lines, the length real lines are clipped to, within the same bound. */
    private static void manyLines() {
        String log = String.join("\n", Collections.nCopies(128, "a.b:[1,2] x ".repeat(680) + " x"));
        long millis = LongStream.range(0, 3).map(i -> millis(() -> DiagnosticLines.parse(log, DiagnosticLines.ERROR,
                "", ""))).min().orElseThrow();
        Check.that(millis < BOUND_MILLIS, "128 lines of 8 KB: " + millis + " ms (bound " + BOUND_MILLIS + " ms)");
    }

    private static long millis(Runnable work) {
        long start = System.nanoTime();
        work.run();
        return (System.nanoTime() - start) / 1_000_000L;
    }

    private static DiagnosticLines.Raw one(String line) {
        List<DiagnosticLines.Raw> found = DiagnosticLines.parse(line, DiagnosticLines.ERROR, "", "");
        Check.equal(1, found.size(), "one diagnostic in: " + line);
        return found.get(0);
    }

    private static void formats() {
        DiagnosticLines.Raw maven = one("[ERROR] /p/File.java:[12,5] msg");
        Check.equal(List.of("ERROR", "/p/File.java", 12, 5, "msg"),
                List.of(maven.severity(), maven.file(), maven.line(), maven.column(), maven.message()), "maven compiler");
        DiagnosticLines.Raw checkstyle = one("[WARN] File.java:3:1: Missing a Javadoc comment. [MissingJavadocMethod]");
        Check.equal(List.of("WARNING", "File.java", 3, 1, "MissingJavadocMethod", "Missing a Javadoc comment."),
                List.of(checkstyle.severity(), checkstyle.file(), checkstyle.line(), checkstyle.column(), checkstyle.rule(),
                        checkstyle.message()), "checkstyle console with rule");
        DiagnosticLines.Raw javac = one("src/main/java/a/Foo.java:12: error: cannot find symbol");
        Check.equal(List.of("ERROR", 12, 0), List.of(javac.severity(), javac.line(), javac.column()), "javac");
        DiagnosticLines.Raw gcc = one("native/main.c:10:5: warning: unused variable 'x'");
        Check.equal(List.of("WARNING", 10, 5), List.of(gcc.severity(), gcc.line(), gcc.column()), "gcc/clang");
        Check.equal("INFO", one("native/main.c:1:1: note: declared here").severity(), "a note is INFO");
        Check.equal("rawtypes", one("[WARNING] /p/C.java:[8,19] found raw type: java.util.List").rule(), "lint rawtypes");
        Check.equal("removal", one("[WARNING] /p/C.java:[5,21] Integer(int) in java.lang.Integer has been deprecated"
                + " and marked for removal").rule(), "lint removal from the message");
        DiagnosticLines.Raw keyed = one("[WARNING] /p/C.java:[5,21] [deprecation] old() in a.B has been deprecated");
        Check.equal(List.of("deprecation", "old() in a.B has been deprecated"), List.of(keyed.rule(), keyed.message()),
                "a [key] prefix becomes the rule");
        Check.equal("C:/Users/u/r/a/Broken.java", one("[ERROR] /C:/Users/u/r/a/Broken.java:[1,2] x").file(),
                "the leading slash of a Windows path is removed");
        Check.equal("C:/r/a/B.java", one("[ERROR] C:\\r\\a\\B.java:[1,2] x").file(), "backslashes become slashes");
    }

    private static void failureMessage() {
        String message = "Compilation failure\n/C:/r/a/src/main/java/fix/Broken.java:[11,16] cannot find symbol\n"
                + "  symbol:   variable missing\n  location: class fix.Broken\nBroken.java:[11,16] cannot find symbol\n";
        List<DiagnosticLines.Raw> found = DiagnosticLines.parse(message, DiagnosticLines.ERROR, "compiler:compile", "a");
        Check.equal(2, found.size(), "the unlevelled lines of a failure message, the probe line included");
        Check.equal("cannot find symbol; symbol: variable missing; location: class fix.Broken", found.get(0).message(),
                "symbol and location lines continue the diagnostic");
        Check.equal(List.of("ERROR", "compiler:compile", "a", 11, 16), List.of(found.get(0).severity(), found.get(0).mojo(),
                found.get(0).artifactId(), found.get(0).line(), found.get(0).column()), "fallback severity and context");
        Check.equal("Broken.java", found.get(1).file(), "the short probe line Broken.java:[11,16]");
    }

    private static void logContext() {
        String log = "[INFO] --- compiler:3.13.0:compile (default-compile) @ c ---\n"
                + "[WARNING] /r/c/src/main/java/fix/C.java:[5,21] found raw type: java.util.List\n"
                + "  missing type arguments for generic class java.util.List<E>\n"
                + "[INFO] --- maven-checkstyle-plugin:3.6.0:check (default) @ d ---\n"
                + "[ERROR] \u001B[1;31m/r/d/D.java:[3,1] boom\u001B[m\n";
        List<DiagnosticLines.Raw> found = DiagnosticLines.parse(log, DiagnosticLines.ERROR, "", "");
        Check.equal(2, found.size(), "two diagnostics in the log");
        Check.equal(List.of("compiler:compile", "c", "WARNING"), List.of(found.get(0).mojo(), found.get(0).artifactId(),
                found.get(0).severity()), "the mojo header sets mojo and project");
        Check.equal("found raw type: java.util.List", found.get(0).message(), "an indented detail line is not appended");
        Check.equal(List.of("maven-checkstyle-plugin:check", "d", "boom"), List.of(found.get(1).mojo(),
                found.get(1).artifactId(), found.get(1).message()), "a Maven 3.8 header; colours removed");
    }

    private static void notDiagnostics() {
        String noise = "[INFO] /p/C.java: Some input files use unchecked or unsafe operations.\n"
                + "[DEBUG] /p/C.java:[1,2] debug output\n"
                + "[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.01 s <<< FAILURE! -- in a.BTest\n"
                + "[ERROR]   BTest.adds:9 expected:<3> but was:<4>\n"
                + "  symbol:   variable orphan\n"
                + "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile"
                + " (default-compile) on project a: Compilation failure\n";
        Check.equal(List.of(), DiagnosticLines.parse(noise, DiagnosticLines.ERROR, "", ""),
                "notes, debug output, test summaries, orphan details and goal failures are not diagnostics");
        Check.equal("", DiagnosticLines.lintKey("cannot find symbol"), "an error message has no lint key");
    }
}
