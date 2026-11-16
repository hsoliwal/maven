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
            cpd(root);
            sarif(root);
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

    private static void cpd(Path root) throws Exception {
        String modern = """
                <?xml version="1.0" encoding="UTF-8"?>
                <pmd-cpd xmlns="https://pmd-code.org/schema/cpd-report"
                         version="1.0.0" pmdVersion="7.17.0">
                  <file path="src/A.java" totalNumberOfTokens="200"/>
                  <duplication lines="8" tokens="44">
                    <file path="src/B.java" line="18" column="3" endline="25"
                          endcolumn="4" begintoken="44" endtoken="87"/>
                    <file path="src/A.java" line="2" column="1" endline="9"
                          endcolumn="3" begintoken="12" endtoken="55"/>
                    <codefragment><![CDATA[duplicate
                      code fragment
                    ]]></codefragment>
                  </duplication>
                </pmd-cpd>
                """;
        var report = convert(root, AnalyzerXmlNormalizer.Format.CPD,
                AnalyzerXmlNormalizer.Status.COMPLETE, modern);
        String output = new String(report.bytes(), StandardCharsets.UTF_8);
        check(report.count() == 2, "original CPD group has both occurrences");
        check(output.contains("PMD.CPD.Duplication\tWARNING\tsrc/A.java\t2\t1\t"),
                "first CPD occurrence preserves original file, line and column");
        check(output.contains("PMD.CPD.Duplication\tWARNING\tsrc/B.java\t18\t3\t"),
                "second CPD occurrence preserves original file, line and column");
        check(output.indexOf("src/A.java") < output.indexOf("src/B.java"),
                "CPD findings have canonical path order");
        check(!output.contains("duplicate\n"), "source code fragments are never copied into findings");
        String originalMessage = output.lines()
                .filter(line -> line.startsWith("PMD.CPD.Duplication"))
                .findFirst().orElseThrow().split("\t", -1)[5];
        String decoded = new String(Base64.getUrlDecoder().decode(originalMessage), StandardCharsets.UTF_8);
        check(decoded.contains("8 lines, 44 tokens; occurrence 1/2"),
                "CPD group sizes and occurrence index retained");
        int first = modern.indexOf("<file path=\"src/B.java\"");
        int second = modern.indexOf("<file path=\"src/A.java\" line=\"2\"");
        int fragment = modern.indexOf("<codefragment", second);
        check(first >= 0 && second > first && fragment > second, "original XML group boundaries");
        String reordered = modern.substring(0, first) + modern.substring(second, fragment)
                + modern.substring(first, second) + modern.substring(fragment);
        check(!modern.equals(reordered), "the fixture must genuinely reverse CPD occurrence order");
        var reorderedReport = convert(root, AnalyzerXmlNormalizer.Format.CPD,
                AnalyzerXmlNormalizer.Status.COMPLETE, reordered);
        check(report.sha256().equals(reorderedReport.sha256()),
                "original CPD occurrence-order permutations retain the same digest");

        String legacy = """
                <pmd-cpd><duplication lines="5" tokens="23">
                  <file path="src/A.java" line="11"/>
                  <file path="src/B.java" line="15"/>
                  <codefragment><![CDATA[original fragment]]></codefragment>
                </duplication></pmd-cpd>
                """;
        check(convert(root, AnalyzerXmlNormalizer.Format.CPD,
                AnalyzerXmlNormalizer.Status.COMPLETE, legacy).count() == 2,
                "legacy unqualified CPD XML is supported without guessed columns");

        String sameFile = """
                <pmd-cpd><duplication lines="3" tokens="18">
                  <file path="src/A.java" line="30"/>
                  <file path="src/A.java" line="12"/>
                  <file path="src/A.java" line="44"/>
                  <codefragment><![CDATA[one original group]]></codefragment>
                </duplication></pmd-cpd>
                """;
        var sameFileResult = convert(root, AnalyzerXmlNormalizer.Format.CPD,
                AnalyzerXmlNormalizer.Status.COMPLETE, sameFile);
        check(sameFileResult.count() == 3,
                "CPD duplication locations within one file are not collapsed");
        String sameFileV1 = new String(sameFileResult.bytes(), StandardCharsets.UTF_8);
        check(sameFileV1.indexOf("src/A.java\t12\t0\t")
                < sameFileV1.indexOf("src/A.java\t30\t0\t"),
                "CPD same-file source lines retain canonical numeric ordering");
        check(sameFileV1.indexOf("src/A.java\t30\t0\t")
                < sameFileV1.indexOf("src/A.java\t44\t0\t"),
                "third original CPD location is retained");


        String error = """
                <pmd-cpd xmlns="https://pmd-code.org/schema/cpd-report">
                  <error filename="src/Bad.java" msg="tokenizer failed"/>
                </pmd-cpd>
                """;
        refusal(root, AnalyzerXmlNormalizer.Format.CPD, AnalyzerXmlNormalizer.Status.COMPLETE, error);
        var partial = convert(root, AnalyzerXmlNormalizer.Format.CPD,
                AnalyzerXmlNormalizer.Status.PARTIAL, error);
        check(partial.count() == 1, "recoverable original CPD processing failure retained");
        check(new String(partial.bytes(), StandardCharsets.UTF_8)
                .contains("PMD.CPD.AnalysisError\tERROR\t\t0\t0\t"),
                "incomplete CPD analysis is not a clean report");

        refusal(root, AnalyzerXmlNormalizer.Format.CPD,
                AnalyzerXmlNormalizer.Status.COMPLETE, modern.replace(
                        "src/A.java", "../outside/A.java"));
        refusal(root, AnalyzerXmlNormalizer.Format.CPD,
                AnalyzerXmlNormalizer.Status.COMPLETE,
                "<pmd-cpd xmlns=\"https://untrusted.example/cpd\"/>");
        refusal(root, AnalyzerXmlNormalizer.Format.CPD,
                AnalyzerXmlNormalizer.Status.COMPLETE,
                "<pmd-cpd><duplication lines=\"4\" tokens=\"23\">"
                        + "<file path=\"src/A.java\" line=\"1\"/>"
                        + "<codefragment>x</codefragment></duplication></pmd-cpd>");
        refusal(root, AnalyzerXmlNormalizer.Format.CPD,
                AnalyzerXmlNormalizer.Status.COMPLETE,
                "<pmd-cpd><duplication lines=\"4\" tokens=\"23\">"
                        + "<file path=\"src/A.java\" line=\"1\"/>"
                        + "<file path=\"src/B.java\" line=\"2\"/></duplication></pmd-cpd>");
        refusal(root, AnalyzerXmlNormalizer.Format.CPD,
                AnalyzerXmlNormalizer.Status.COMPLETE,
                "<pmd-cpd><duplication lines=\"0\" tokens=\"23\">"
                        + "<file path=\"src/A.java\" line=\"1\"/>"
                        + "<file path=\"src/B.java\" line=\"2\"/>"
                        + "<codefragment>x</codefragment></duplication></pmd-cpd>");
        refusal(root, AnalyzerXmlNormalizer.Format.CPD,
                AnalyzerXmlNormalizer.Status.COMPLETE,
                "<pmd-cpd><duplication lines=\"4\" tokens=\"23\">"
                        + "<file path=\"src/A.java\" line=\"1\"/>"
                        + "<file path=\"src/A.java\" line=\"1\"/>"
                        + "<codefragment>x</codefragment></duplication></pmd-cpd>");
        refusal(root, AnalyzerXmlNormalizer.Format.CPD,
                AnalyzerXmlNormalizer.Status.COMPLETE,
                "<!DOCTYPE pmd-cpd [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                        + "<pmd-cpd><duplication lines=\"4\" tokens=\"23\">"
                        + "<file path=\"src/A.java\" line=\"1\"/>"
                        + "<file path=\"src/B.java\" line=\"2\"/>"
                        + "<codefragment>&x;</codefragment></duplication></pmd-cpd>");
    }

    private static void sarif(Path root) throws Exception {
        String json = """
                {
                  "version": "2.1.0",
                  "runs": [{
                    "results": [
                      {
                        "ruleId": "java/sql-injection",
                        "level": "error",
                        "message": { "text": "Untrusted\\nquery input" },
                        "locations": [{
                          "physicalLocation": {
                            "artifactLocation": { "uri": "src/A.java", "uriBaseId": "%%SRCROOT%%" },
                            "region": { "startLine": 22, "startColumn": 3 }
                          }
                        }]
                      },
                      {
                        "ruleId": "java/path-traversal",
                        "level": "warning",
                        "message": { "markdown": "Unsafe access" },
                        "locations": [{
                          "physicalLocation": {
                            "artifactLocation": { "index": 0 },
                            "region": { "startLine": 7 }
                          }
                        }]
                      },
                      { "ruleId": "java/information", "level": "note",
                        "message": { "text": "Project-wide observation" }, "locations": [] },
                      { "kind": "pass" }
                    ],
                    "artifacts": [{ "location": { "uri": "src/B.java", "uriBaseId": "%%SRCROOT%%" } }],
                    "originalUriBaseIds": { "%%SRCROOT%%": { "uri": "%s" } },
                    "invocations": [{ "executionSuccessful": true }],
                    "tool": { "driver": { "name": "CodeQL" } }
                  }]
                }
                """.formatted(root.toUri().toASCIIString());
        var report = convert(root, AnalyzerXmlNormalizer.Format.SARIF,
                AnalyzerXmlNormalizer.Status.COMPLETE, json);
        String output = new String(report.bytes(), StandardCharsets.UTF_8);
        check(report.count() == 3, "original SARIF findings and pass-kind exclusion");
        check(output.contains("CodeQL.java/sql-injection\tERROR\tsrc/A.java\t22\t3\t"),
                "CodeQL original rule and physical location retained");
        check(output.contains("CodeQL.java/path-traversal\tWARNING\tsrc/B.java\t7\t0\t"),
                "CodeQL original indexed artifact resolved after result field");
        check(output.contains("CodeQL.java/information\tINFO\t\t0\t0\t"),
                "SARIF without physical location retained as project-wide");
        check(output.contains(Base64.getUrlEncoder().withoutPadding()
                .encodeToString("Untrusted query input".getBytes(StandardCharsets.UTF_8))),
                "multiline original SARIF message normalized without inventing findings");
        check(report.sha256().equals(convert(root, AnalyzerXmlNormalizer.Format.SARIF,
                AnalyzerXmlNormalizer.Status.COMPLETE, json).sha256()),
                "SARIF normalization deterministic over original bytes");

        String nested = """
                {"version":"2.1.0","runs":[{
                  "tool":{"driver":{"name":"Semgrep"}},
                  "originalUriBaseIds":{
                    "%%PROJECT%%":{"uri":"%s"},
                    "%%SRCROOT%%":{"uri":"src/","uriBaseId":"%%PROJECT%%"}
                  },
                  "results":[{"ruleId":"rules.strict","level":"warning",
                    "message":{"text":"Avoid this call"},
                    "locations":[{"physicalLocation":{
                      "artifactLocation":{"uri":"Nested.java","uriBaseId":"%%SRCROOT%%"},
                      "region":{"startLine":1,"startColumn":1}
                    }}]
                  }]
                }]}
                """.formatted(root.toUri().toASCIIString());
        String mapped = new String(convert(root, AnalyzerXmlNormalizer.Format.SARIF,
                AnalyzerXmlNormalizer.Status.COMPLETE, nested).bytes(), StandardCharsets.UTF_8);
        check(mapped.contains("Semgrep.rules.strict\tWARNING\tsrc/Nested.java\t1\t1\t"),
                "nested original SARIF URI bases resolve without source reads");

        String failing = """
                {"version":"2.1.0","runs":[{"tool":{"driver":{"name":"CodeQL"}},
                   "results":[],"invocations":[{"executionSuccessful":false}]}]}
                """;
        refusal(root, AnalyzerXmlNormalizer.Format.SARIF, AnalyzerXmlNormalizer.Status.COMPLETE, failing);
        String partial = new String(convert(root, AnalyzerXmlNormalizer.Format.SARIF,
                AnalyzerXmlNormalizer.Status.PARTIAL, failing).bytes(), StandardCharsets.UTF_8);
        check(partial.contains("SARIF.ToolExecutionError\tERROR\t\t0\t0\t"),
                "failed source invocation is not interpreted as zero violations");

        refusal(root, AnalyzerXmlNormalizer.Format.SARIF,
                AnalyzerXmlNormalizer.Status.COMPLETE, json.replace("\"2.1.0\"", "\"3.0.0\""));
        refusal(root, AnalyzerXmlNormalizer.Format.SARIF, AnalyzerXmlNormalizer.Status.COMPLETE,
                json.replace("\"%SRCROOT%\": { \"uri\":", "\"OTHER\": { \"uri\":"));
        refusal(root, AnalyzerXmlNormalizer.Format.SARIF, AnalyzerXmlNormalizer.Status.COMPLETE,
                json.replace("src/A.java", "../outside/A.java"));
        refusal(root, AnalyzerXmlNormalizer.Format.SARIF, AnalyzerXmlNormalizer.Status.COMPLETE,
                json.replace("src/A.java", "https://untrusted.invalid/source.java"));
        refusal(root, AnalyzerXmlNormalizer.Format.SARIF, AnalyzerXmlNormalizer.Status.COMPLETE,
                json.replace("\"level\": \"error\"", "\"level\": \"fatal\""));
        refusal(root, AnalyzerXmlNormalizer.Format.SARIF, AnalyzerXmlNormalizer.Status.COMPLETE,
                json.replace("\"name\": \"CodeQL\"", "\"untrusted\": \"CodeQL\""));
        refusal(root, AnalyzerXmlNormalizer.Format.SARIF, AnalyzerXmlNormalizer.Status.COMPLETE,
                json.replace("\"ruleId\": \"java/sql-injection\",", ""));
        refusal(root, AnalyzerXmlNormalizer.Format.SARIF, AnalyzerXmlNormalizer.Status.COMPLETE,
                json.replace("\"version\": \"2.1.0\",", ""));
        refusal(root, AnalyzerXmlNormalizer.Format.SARIF, AnalyzerXmlNormalizer.Status.COMPLETE,
                "{ \"version\": \"2.1.0\", \"runs\": [}");
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
