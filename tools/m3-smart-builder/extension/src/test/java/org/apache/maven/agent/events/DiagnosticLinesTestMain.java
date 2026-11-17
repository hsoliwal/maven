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

import java.util.List;

/** Located diagnostics in compiler, Checkstyle, javac and gcc output and in mojo failure messages. */
public final class DiagnosticLinesTestMain {
    private DiagnosticLinesTestMain() {}

    public static void main(String[] args) {
        formats();
        failureMessage();
        logContext();
        notDiagnostics();
        Check.done("DiagnosticLinesTestMain");
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
