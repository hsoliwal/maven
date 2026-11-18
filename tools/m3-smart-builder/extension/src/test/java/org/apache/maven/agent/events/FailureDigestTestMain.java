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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** failure.json and failure.md, reproduce commands, source windows, redaction and the bounded summary. */
public final class FailureDigestTestMain {
    static final String HELP = "https://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException";

    private FailureDigestTestMain() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("failure-digest-test");
        Path source = root.resolve("a/src/main/java/fix/Broken.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package fix;\nclass Broken {\n\tint x = missing;\n}\n", StandardCharsets.UTF_8);
        digest(root);
        markdown(root);
        reproduce();
        windowsAndRedaction(root);
        summary();
        Check.done("FailureDigestTestMain");
    }

    static FailureDigest sample(Path root) {
        Diagnostic error = new Diagnostic("fix:a", "compiler", "ERROR", "a/src/main/java/fix/Broken.java", 3, 9, "",
                "cannot find symbol; symbol: variable missing", "compiler:compile");
        FailureDigest.Item item = new FailureDigest.Item(error, SourceWindow.read(root, error.file(), 3, 1));
        SurefireReports.TestCase test = new SurefireReports.TestCase("fix.BTest", "adds", "failed", 12,
                "java.lang.AssertionError", "expected:<3> but was:<4>", "trace");
        TestResult result = new TestResult("fix:b", test, "expected:<3> but was:<4>",
                List.of("java.lang.AssertionError: expected:<3> but was:<4>", "at fix.BTest.adds(BTest.java:9)"),
                "b/src/test/java/fix/BTest.java", 9);
        return new FailureDigest("failure", List.of("verify"), MavenCommands.Launchers.of("mvn"), List.of(
                new FailureDigest.Failure(1, "fix:a", "a", "compile", "compiler:compile", "Compilation failure", HELP,
                        List.of(item), List.of(), "mvn -pl a -am compile", 0),
                new FailureDigest.Failure(2, "fix:b", "b", "test", "surefire:test", "There are test failures.", HELP,
                        List.of(), List.of(result), "mvn -pl b -am -Dtest=fix.BTest#adds"
                        + " -Dsurefire.failIfNoSpecifiedTests=false test", 2)),
                List.of("fix:c"), FailureDigest.Advice.of("not-requested"), new FailureDigest.Limits(20, 0, 0));
    }

    private static void digest(Path root) {
        String json = sample(root).json();
        Check.equal("{\"v\":1,\"outcome\":\"failure\",\"goals\":[\"verify\"],\"launcher\":{\"sh\":\"mvn\","
                + "\"powershell\":\"mvn\",\"cmd\":\"mvn\"},\"failures\":[{\"project\":\"fix:a\","
                + "\"path\":\"a\",\"kind\":\"compile\",\"mojo\":\"compiler:compile\",\"message\":\"Compilation failure\","
                + "\"helpReference\":\"" + HELP + "\",\"diagnostics\":[{\"file\":\"a/src/main/java/fix/Broken.java\","
                + "\"line\":3,\"column\":9,\"severity\":\"ERROR\",\"rule\":null,\"message\":\"cannot find symbol; symbol: "
                + "variable missing\",\"context\":{\"startLine\":2,\"markLine\":3,\"lines\":[\"class Broken {\","
                + "\"    int x = missing;\",\"}\"]}}],\"tests\":[],\"reproduce\":\"mvn -pl a -am compile\",\"omittedProperties\":0},"
                + "{\"project\":\"fix:b\",\"path\":\"b\",\"kind\":\"test\",\"mojo\":\"surefire:test\",\"message\":"
                + "\"There are test failures.\",\"helpReference\":\"" + HELP + "\",\"diagnostics\":[],\"tests\":["
                + "{\"class\":\"fix.BTest\",\"method\":\"adds\",\"exception\":\"java.lang.AssertionError\",\"message\":"
                + "\"expected:<3> but was:<4>\",\"stack\":[\"java.lang.AssertionError: expected:<3> but was:<4>\","
                + "\"at fix.BTest.adds(BTest.java:9)\"]}],\"reproduce\":\"mvn -pl b -am -Dtest=fix.BTest#adds "
                + "-Dsurefire.failIfNoSpecifiedTests=false test\",\"omittedProperties\":2}],\"skippedProjects\":[\"fix:c\"],\"advice\":"
                + "{\"status\":\"not-requested\",\"source\":null,\"trusted\":false,\"text\":null},\"limits\":"
                + "{\"printed\":20,\"omittedDiagnostics\":0,\"omittedTests\":0}}\n", json, "failure.json, exact bytes");
        Check.equal(json, sample(root).json(), "a rerun on the same failure gives the same bytes");
        Check.that(!json.contains("durationMs") && !json.contains(root.toString()), "no durations, no absolute paths");
        String md = sample(root).markdown();
        Check.equal(md, sample(root).markdown(), "failure.md is deterministic");
        Check.that(md.startsWith("# Build failure\n") && md.contains("## a (fix:a): compile")
                && md.contains("      3>     int x = missing;\n") && md.contains("- `fix.BTest#adds` failed")
                && md.contains("```\nmvn -pl a -am compile\n```") && md.contains("## Skipped projects\n\n- fix:c\n")
                && !md.contains("\r"), "failure.md sections, marked source line, reproduce, LF only:\n" + md);
    }

    /** The failure message in a fence, the left-out properties counted after their command, the advice fenced. */
    private static void markdown(Path root) {
        String md = sample(root).markdown();
        Check.that(md.contains("`compiler:compile` failed:\n\n```\nCompilation failure\n```\n"), "message fenced:\n" + md);
        Check.that(md.contains("test\n```\n\n(2 user properties omitted)\n") && !md.contains("(0 user"),
                "the omitted properties counted after their command:\n" + md);
        advice(root);
    }

    /** Advice text is redacted like a message and fenced, so it can neither leak a credential nor add a section. */
    private static void advice(Path root) {
        FailureDigest.Advice advice = new FailureDigest.Advice("available", "hook", "use https://u:pw@h/x and "
                + root.resolve("a").resolve("B.java") + "\n## Limits\nnone").clean(Redact.Places.of(root));
        String sep = root.getFileSystem().getSeparator();
        Check.equal("use https://***@h/x and a" + sep + "B.java\n## Limits\nnone", advice.text(), "advice redacted");
        FailureDigest digest = new FailureDigest("failure", List.of("verify"), MavenCommands.Launchers.of("mvn"),
                sample(root).failures(), List.of(), advice, new FailureDigest.Limits(20, 0, 0));
        Check.that(digest.markdown().contains("(never trusted).\n\n```\nuse https://***@h/x and a" + sep
                + "B.java\n## Limits\nnone\n```\n"), "advice fenced:\n" + digest.markdown());
    }

    private static void reproduce() {
        SurefireReports.TestCase adds = new SurefireReports.TestCase("fix.BTest", "adds[1]", "failed", 1, "", "", "");
        SurefireReports.TestCase subs = new SurefireReports.TestCase("fix.BTest", "subs(int)[2]", "error", 1, "", "", "");
        SurefireReports.TestCase other = new SurefireReports.TestCase("fix.CTest", "x", "failed", 1, "", "", "");
        Diagnostic testSource = new Diagnostic("fix:a", "compiler", "ERROR", "a/src/test/java/fix/T.java", 1, 1, "", "m", "");
        Check.equal("mvn -pl a -am compile", reproduce("mvn", "compile", "a", List.of(), List.of(), "compile"),
                "compile failure");
        Check.equal("./mvnw -pl a -am test-compile", reproduce("./mvnw", "compile", "a", List.of(testSource), List.of(),
                "compile"), "a compile error in a test source reruns test-compile");
        Check.equal("mvn -pl b -am \"-Dtest=fix.BTest#adds+subs,fix.CTest#x\" \"-Dsurefire.failIfNoSpecifiedTests=false\""
                + " test", reproduce("mvn", "test", "b", List.of(), List.of(adds, subs, other), "test"),
                "failed tests grouped by class, parameter suffixes removed");
        Check.equal("mvn -pl b -am \"-Dit.test=fix.CTest#x\" \"-Dfailsafe.failIfNoSpecifiedTests=false\""
                + " \"-Dtest=NoUnitTestSelected\" \"-Dsurefire.failIfNoSpecifiedTests=false\" verify",
                reproduce("mvn", "integration-test", "b", List.of(), List.of(other), "integration-test"),
                "integration test");
        Check.equal("mvn -pl . -am package", reproduce("mvn", "other", ".", List.of(), List.of(), "package"),
                "another mojo reruns its phase");
    }

    private static String reproduce(String launcher, String kind, String module, List<Diagnostic> errors,
            List<SurefireReports.TestCase> tests, String phase) {
        return ReproduceCommand.of(new ReproduceCommand.Build(launcher, List.of(), List.of("verify")),
                new ReproduceCommand.Failed(kind, module, errors, tests, phase, ""));
    }

    /** A symbolic link inside the root to a file outside it gives no window (needs the right to make links). */
    private static void symlinkOutside(Path root) {
        try {
            Path outside = Files.createTempFile("outside-root", ".java");
            Files.writeString(outside, "secret line\n", StandardCharsets.UTF_8);
            Files.createSymbolicLink(root.resolve("a/link.java"), outside);
            Check.equal(Optional.empty(), SourceWindow.read(root, "a/link.java", 1, 0), "no window through a link out");
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException noLinks) {
            System.out.println("FailureDigestTestMain: symbolic link check NOT RUN: " + noLinks);
        }
    }

    private static void windowsAndRedaction(Path root) {
        Optional<SourceWindow.Window> window = SourceWindow.read(root, "a/src/main/java/fix/Broken.java", 1, 2);
        Check.equal(List.of(1, 1, 3), List.of(window.get().startLine(), window.get().markLine(), window.get().lines().size()),
                "a window at the first line starts at 1");
        Check.equal(Optional.empty(), SourceWindow.read(root, "../outside.java", 1, 2), "no file outside the root");
        Check.equal(Optional.empty(), SourceWindow.read(root, "a/src/main/java/fix/Broken.java", 99, 2), "no line 99");
        symlinkOutside(root);
        Check.equal("get https://***@repo.example/x failed", Redact.secrets("get https://user:s3cret@repo.example/x failed"),
                "credentials of a URL are removed");
        String forward = root.toString().replace('\\', '/');
        Check.equal("a/B.java and a/C.java and b\\D.java", Redact.paths("/" + forward + "/a/B.java and " + forward
                + "/a/C.java and " + root.toString().replace('/', '\\') + "\\b\\D.java", root), "root spellings removed");
    }

    private static void summary() {
        List<String> lines = List.of("BUILD FAILURE", "FAILED a", "x".repeat(100), "files: target/agent/failure.md");
        String full = SummaryPrinter.bounded(lines, 4000, "target/agent/failure.md");
        Check.equal(4, full.split("\n").length, "under the bound every line is kept");
        Check.that(full.lines().allMatch(line -> line.startsWith("[agent] ")), "every line has the prefix");
        String cut = SummaryPrinter.bounded(lines, 120, "target/agent/failure.md");
        Check.that(cut.getBytes(StandardCharsets.UTF_8).length <= 120, "the summary stays within its bytes: " + cut.length());
        Check.that(cut.endsWith("[agent] ... more in target/agent/failure.md\n"), "a cut summary names where the rest is");
    }
}
