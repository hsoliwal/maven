/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

/** Hermetic Java 17 contract suite: runs without Maven, PMD or Checkstyle binaries. */
public final class AnalyzerXmlNormalizerContractTestMain {
    private AnalyzerXmlNormalizerContractTestMain() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("m3-xml-adapter-");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("target"));
        try {
            checkstyle(root);
            pmd(root);
            spotbugs(root);
            refusal(root);
            cli(root);
            System.out.println("M3_ANALYZER_XML_ADAPTER_CONTRACT_PASS");
        } finally {
            Files.walk(root)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    });
        }
    }

    private static void checkstyle(Path root) throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <checkstyle version="10.0">
                  <file name="src/B.java">
                    <error line="2" severity="warning" message="Unused import"
                           source="com.puppycrawl.tools.checkstyle.checks.imports.UnusedImportsCheck"/>
                  </file>
                  <file name="src/A.java">
                    <error line="1" column="2" severity="error"
                           source="EmptyBlock" message="Missing &amp; required block"/>
                  </file>
                </checkstyle>
                """;
        var a = convert(root, AnalyzerXmlNormalizer.Format.CHECKSTYLE,
                AnalyzerXmlNormalizer.Status.COMPLETE, xml);
        var b = convert(root, AnalyzerXmlNormalizer.Format.CHECKSTYLE,
                AnalyzerXmlNormalizer.Status.COMPLETE, xml.replace(
                        "<file name=\"src/B.java\">", "<file name=\"src/B.java\">"));
        check(a.count() == 2 && a.sha256().equals(b.sha256()), "stable Checkstyle bytes");
        String report = new String(a.bytes(), StandardCharsets.UTF_8);
        check(report.startsWith("M3-STATIC-ANALYSIS-V1\nstatus\tCOMPLETE\n"), "v1 header");
        check(report.indexOf("src/A.java") < report.indexOf("src/B.java"), "canonical numeric/path order");
        check(report.contains("EmptyBlock\tERROR\tsrc/A.java\t1\t2\t"), "Checkstyle ERROR");
        check(report.contains("com.puppycrawl.tools.checkstyle.checks.imports.UnusedImportsCheck"),
                "original Checkstyle rule name preserved");
        check(report.contains(Base64.getUrlEncoder().withoutPadding().encodeToString(
                "Missing & required block".getBytes(StandardCharsets.UTF_8))), "message encoding");
        check(a.bytes() != a.bytes(), "defensively copied result bytes");
    }

    private static void pmd(Path root) throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <pmd xmlns="http://pmd.sourceforge.net/report/2.0.0" version="7.0.0">
                  <file name="src/Z.java">
                    <violation beginline="23" begincolumn="9" rule="UnusedImports"
                               ruleset="Best Practices" priority="3">
                        Unused import statement.
                    </violation>
                  </file>
                  <file name="src/A.java">
                    <violation beginline="3" begincolumn="1" rule="CloseResource"
                               ruleset="Error Prone" priority="2">Close resource.</violation>
                  </file>
                  <suppressedviolation filename="src/C.java" suppressiontype="@SuppressWarnings"/>
                </pmd>
                """;
        var r = convert(root, AnalyzerXmlNormalizer.Format.PMD,
                AnalyzerXmlNormalizer.Status.COMPLETE, xml);
        String report = new String(r.bytes(), StandardCharsets.UTF_8);
        check(r.count() == 2, "PMD omitted suppressed results");
        check(report.indexOf("src/A.java") < report.indexOf("src/Z.java"), "PMD path sorting");
        check(report.contains("Error Prone.CloseResource\tERROR\tsrc/A.java\t3\t1"), "PMD priority 2");
        check(report.contains("Best Practices.UnusedImports\tWARNING\tsrc/Z.java\t23\t9"), "PMD priority 3");

        String failed = """
                <pmd xmlns="http://pmd.sourceforge.net/report/2.0.0">
                  <error filename="src/A.java" msg="parser failed"/>
                  <configerror rule="BadConfig" msg="invalid rules"/>
                </pmd>
                """;
        refusal(root, AnalyzerXmlNormalizer.Format.PMD,
                AnalyzerXmlNormalizer.Status.COMPLETE, failed);
        var partial = convert(root, AnalyzerXmlNormalizer.Format.PMD,
                AnalyzerXmlNormalizer.Status.PARTIAL, failed);
        check(partial.count() == 2, "PMD configuration and processing errors retained");
        String out = new String(partial.bytes(), StandardCharsets.UTF_8);
        check(out.contains("status\tPARTIAL"), "original PMD status preserved");
        check(out.contains("PMD.ConfigError\tERROR\t\t0\t0"), "PMD project-wide config finding");
        check(out.contains("PMD.ProcessingError\tERROR\t\t0\t0"), "PMD project-wide parse finding");
    }

    private static void spotbugs(Path root) throws Exception {
        String original = """
                <?xml version="1.0" encoding="UTF-8"?>
                <BugCollection version="4.9.0">
                  <Project projectName="example">
                    <Jar>target/classes</Jar>
                    <SrcDir>src/main/java</SrcDir>
                  </Project>
                  <BugInstance type="NP_NULL_ON_SOME_PATH" priority="1" abbrev="NP" category="CORRECTNESS">
                    <ShortMessage>Possible null value</ShortMessage>
                    <LongMessage>Possible null dereference on the selected path</LongMessage>
                    <Class classname="example.A">
                      <SourceLine sourcepath="src/main/java/example/A.java" start="4" end="35"/>
                    </Class>
                    <Method classname="example.A" name="go" signature="()V" isStatic="false">
                      <SourceLine sourcepath="src/main/java/example/A.java" start="12"/>
                    </Method>
                    <SourceLine sourcepath="src/main/java/example/A.java" start="18" primary="true"/>
                  </BugInstance>
                  <BugInstance type="RV_RETURN_VALUE_IGNORED" priority="2" abbrev="RV" category="BAD_PRACTICE">
                    <UserAnnotation designation="MOSTLY_HARMLESS" user="reviewer" timestamp="1"/>
                    <Class classname="example.B">
                      <SourceLine sourcepath="src/main/java/example/B.java" start="22"/>
                    </Class>
                  </BugInstance>
                  <BugInstance type="DLS_DEAD_LOCAL_STORE" priority="3" abbrev="DLS" category="STYLE"/>
                  <Errors errors="0" missingClasses="0"/>
                </BugCollection>
                """;
        var report = convert(root, AnalyzerXmlNormalizer.Format.SPOTBUGS,
                AnalyzerXmlNormalizer.Status.COMPLETE, original);
        String text = new String(report.bytes(), StandardCharsets.UTF_8);
        check(report.count() == 3, "original SpotBugs bug count retained");
        check(text.contains("NP_NULL_ON_SOME_PATH\tERROR\tsrc/main/java/example/A.java\t18\t0\t"),
                "primary SpotBugs source line selected without guessing");
        check(text.contains("RV_RETURN_VALUE_IGNORED\tWARNING\tsrc/main/java/example/B.java\t22\t0\t"),
                "review-annotated original warnings remain present");
        check(text.contains("DLS_DEAD_LOCAL_STORE\tINFO\t\t0\t0\t"),
                "unlocated bug remains an explicit project-wide finding");
        check(text.contains(Base64.getUrlEncoder().withoutPadding().encodeToString(
                "Possible null dereference on the selected path".getBytes(StandardCharsets.UTF_8))),
                "original LongMessage preserved");
        check(text.indexOf("DLS_DEAD_LOCAL_STORE") < text.indexOf("NP_NULL_ON_SOME_PATH"),
                "canonical v1 path ordering retained");
        var repeated = convert(root, AnalyzerXmlNormalizer.Format.SPOTBUGS,
                AnalyzerXmlNormalizer.Status.COMPLETE, original);
        check(report.sha256().equals(repeated.sha256()), "SpotBugs report fingerprint is deterministic");

        String withErrors = """
                <BugCollection version="4.9.0">
                  <Errors errors="1" missingClasses="1"><MissingClass>example.ThirdParty</MissingClass></Errors>
                </BugCollection>
                """;
        refusal(root, AnalyzerXmlNormalizer.Format.SPOTBUGS,
                AnalyzerXmlNormalizer.Status.COMPLETE, withErrors);
        var partial = convert(root, AnalyzerXmlNormalizer.Format.SPOTBUGS,
                AnalyzerXmlNormalizer.Status.PARTIAL, withErrors);
        check(partial.count() == 1, "missing-class SpotBugs summary cannot disappear");
        check(new String(partial.bytes(), StandardCharsets.UTF_8)
                .contains("SpotBugs.AnalysisError\tERROR\t\t0\t0\t"), "project-wide analysis error retained");

        refusal(root, AnalyzerXmlNormalizer.Format.SPOTBUGS,
                AnalyzerXmlNormalizer.Status.COMPLETE,
                "<BugCollection><BugInstance type=\"NP_NULL\" priority=\"5\"/></BugCollection>");
        refusal(root, AnalyzerXmlNormalizer.Format.SPOTBUGS,
                AnalyzerXmlNormalizer.Status.COMPLETE,
                "<BugCollection><BugInstance type=\"NP_NULL\"/></BugCollection>");
        refusal(root, AnalyzerXmlNormalizer.Format.SPOTBUGS,
                AnalyzerXmlNormalizer.Status.COMPLETE,
                "<BugCollection><BugInstance type=\"NP_NULL\" priority=\"1\">"
                        + "<SourceLine sourcepath=\"../outside/A.java\" start=\"2\"/>"
                        + "</BugInstance></BugCollection>");
        refusal(root, AnalyzerXmlNormalizer.Format.SPOTBUGS,
                AnalyzerXmlNormalizer.Status.COMPLETE,
                "<!DOCTYPE BugCollection [<!ENTITY leak SYSTEM \"file:///etc/passwd\">]>"
                        + "<BugCollection><BugInstance type=\"NP_NULL\" priority=\"1\">"
                        + "<LongMessage>&leak;</LongMessage></BugInstance></BugCollection>");
        refusal(root, AnalyzerXmlNormalizer.Format.SPOTBUGS,
                AnalyzerXmlNormalizer.Status.COMPLETE,
                "<BugCollection><UnknownV2/></BugCollection>");
        refusal(root, AnalyzerXmlNormalizer.Format.SPOTBUGS,
                AnalyzerXmlNormalizer.Status.COMPLETE,
                "<BugCollection><BugInstance type=\"NP_NULL\" priority=\"1\"/>"
                        + "<BugInstance type=\"NP_NULL\" priority=\"1\"/></BugCollection>");
    }

    private static void refusal(Path root) throws Exception {
        refusal(root, AnalyzerXmlNormalizer.Format.CHECKSTYLE,
                AnalyzerXmlNormalizer.Status.COMPLETE,
                "<!DOCTYPE checkstyle [<!ENTITY secret SYSTEM \"file:///etc/passwd\">]>"
                        + "<checkstyle><file name=\"src/A.java\">"
                        + "<error line=\"1\" severity=\"error\" source=\"Rule\" message=\"&secret;\"/>"
                        + "</file></checkstyle>");
        refusal(root, AnalyzerXmlNormalizer.Format.CHECKSTYLE,
                AnalyzerXmlNormalizer.Status.COMPLETE,
                "<checkstyle><file name=\"../outside.java\">"
                        + "<error line=\"1\" severity=\"error\" source=\"Rule\" message=\"x\"/>"
                        + "</file></checkstyle>");
        refusal(root, AnalyzerXmlNormalizer.Format.CHECKSTYLE,
                AnalyzerXmlNormalizer.Status.COMPLETE,
                "<checkstyle><file name=\"src/A.java\">"
                        + "<error line=\"1\" severity=\"unknown\" source=\"Rule\" message=\"x\"/>"
                        + "</file></checkstyle>");
        refusal(root, AnalyzerXmlNormalizer.Format.PMD,
                AnalyzerXmlNormalizer.Status.COMPLETE,
                "<pmd><file name=\"src/A.java\"><violation beginline=\"1\" priority=\"9\""
                        + " rule=\"R\">x</violation></file></pmd>");
        refusal(root, AnalyzerXmlNormalizer.Format.CHECKSTYLE,
                AnalyzerXmlNormalizer.Status.COMPLETE,
                "<checkstyle><file name=\"src/A.java\">"
                        + "<error line=\"1\" severity=\"error\" source=\"Rule\" message=\"x\"/>"
                        + "<error line=\"1\" severity=\"error\" source=\"Rule\" message=\"x\"/>"
                        + "</file></checkstyle>");
        refusal(root, AnalyzerXmlNormalizer.Format.CHECKSTYLE,
                AnalyzerXmlNormalizer.Status.COMPLETE, "<checkstyle><unknown/></checkstyle>");
        byte[] invalidUtf8 = {(byte) 0xc3, (byte) 0x28};
        try {
            AnalyzerXmlNormalizer.normalize(
                    AnalyzerXmlNormalizer.Format.CHECKSTYLE,
                    AnalyzerXmlNormalizer.Status.COMPLETE, root, invalidUtf8);
            throw new AssertionError("malformed UTF-8 accepted");
        } catch (IOException expected) {
            // Invalid XML must not generate a report.
        }
        byte[] tooLarge = new byte[AnalyzerXmlNormalizer.MAX_BYTES + 1];
        Arrays.fill(tooLarge, (byte) ' ');
        try {
            AnalyzerXmlNormalizer.normalize(
                    AnalyzerXmlNormalizer.Format.PMD,
                    AnalyzerXmlNormalizer.Status.COMPLETE, root, tooLarge);
            throw new AssertionError("input budget bypassed");
        } catch (IOException expected) {
            // Before parse.
        }
    }

    private static void cli(Path root) throws Exception {
        Path input = root.resolve("target/original.xml");
        Path report = root.resolve("target/result.tsv");
        Path digest = root.resolve("target/result.sha256");
        Files.writeString(input, "<checkstyle><file name=\"src/A.java\">"
                + "<error line=\"1\" severity=\"info\" source=\"Rule\" message=\"Message\"/>"
                + "</file></checkstyle>");
        NormalizeCli.main(new String[] {
                "CHECKSTYLE", "COMPLETE", root.toString(),
                input.toString(), report.toString(), digest.toString()
        });
        String result = Files.readString(report);
        String hash = Files.readString(digest).trim();
        check(result.contains("Rule\tINFO\tsrc/A.java\t1\t0\t"), "CLI status and location");
        check(AnalyzerXmlNormalizer.normalize(
                AnalyzerXmlNormalizer.Format.CHECKSTYLE, AnalyzerXmlNormalizer.Status.COMPLETE,
                root, Files.readAllBytes(input)).sha256().equals(hash), "output digest sidecar");

        try {
            NormalizeCli.main(new String[] {
                    "CHECKSTYLE", "COMPLETE", root.toString(),
                    input.toString(), root.resolve("../outside.tsv").toString(), digest.toString()
            });
            throw new AssertionError("outside root output accepted");
        } catch (IOException expected) {
            // No writes outside the project root.
        }
    }

    private static AnalyzerXmlNormalizer.Result convert(
            Path root, AnalyzerXmlNormalizer.Format format, AnalyzerXmlNormalizer.Status status, String xml)
            throws IOException {
        return AnalyzerXmlNormalizer.normalize(format, status, root, xml.getBytes(StandardCharsets.UTF_8));
    }

    private static void refusal(
            Path root, AnalyzerXmlNormalizer.Format format, AnalyzerXmlNormalizer.Status status, String xml)
            throws IOException {
        try {
            convert(root, format, status, xml);
            throw new AssertionError("unsafe report accepted: " + xml.substring(0, Math.min(70, xml.length())));
        } catch (IOException expected) {
            // Deliberate refusal.
        }
    }

    private static void check(boolean condition, String reason) {
        if (!condition) {
            throw new AssertionError(reason);
        }
    }
}
