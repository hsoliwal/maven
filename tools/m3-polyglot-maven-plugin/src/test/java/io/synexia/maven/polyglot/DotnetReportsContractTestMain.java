/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * .NET through Maven phases without a .NET SDK: TRX results (a real xUnit run, user and folder names neutralised)
 * become surefire reports; outcomes, durations and escaping; the report provider's argument rules; the test wrapper
 * removes stale TRX files, keeps the tool's exit status and still writes reports for failing tests; NuGet package
 * identities for the attach goal.
 */
public final class DotnetReportsContractTestMain {
    private static final String NS = TrxReport.NAMESPACE;
    private static final String NUSPEC = "<?xml version=\"1.0\"?><package xmlns=\"http://schemas.microsoft.com/packaging/"
            + "2013/05/nuspec.xsd\"><metadata><id>Synexia.Probe.Calc</id><version>1.2.3</version>"
            + "<dependencies><dependency id=\"Other\" version=\"9.0.0\"/></dependencies></metadata></package>";
    private static final String XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite xmlns:xsi="
            + "\"http://www.w3.org/2001/XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"https://maven.apache.org/"
            + "surefire/maven-surefire-plugin/xsd/surefire-test-report.xsd\" name=\"A.B\"";
    private static int passed;

    private DotnetReportsContractTestMain() { }

    /** A stand-in for {@code dotnet test}: writes one TRX file into its first argument, exits with the second. */
    public static final class FakeDotnetTest {
        private FakeDotnetTest() { }

        public static void main(String[] args) throws IOException {
            Files.createDirectories(Path.of(args[0]));
            Files.writeString(Path.of(args[0], "run.trx"), trx(result("t1", "Divides", "Failed", "boom"),
                    definitions("t1", "Lib.Tests.MathTests")), StandardCharsets.UTF_8);
            System.exit(Integer.parseInt(args[1]));
        }
    }

    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("m3-dotnet-contract-");
        try {
            readsTrxFiles(root);
            rendersSurefireXml();
            validatesProviderArguments(root);
            wrapsTheTestToolLikeSurefire(root.resolve("wrap"));
            namesNugetPackages(root.resolve("nuget"));
            refusesBadNugetPackages(root.resolve("nuget"));
            System.out.println("PASS: " + passed + " .NET report contract cases");
        } finally {
            erase(root);
        }
    }

    /** The TRX reader: a real xUnit run, every outcome, durations, unsafe or foreign XML. */
    private static void readsTrxFiles(Path root) throws Exception {
        readsARealXunitRun(root);
        readsXunitCases();
        mapsOutcomesNamesAndDurations(root);
        readsTimeSpanDurations();
        refusesUnsafeOrForeignXml(root);
    }

    private static void readsARealXunitRun(Path root) throws Exception {
        Path results = Files.createDirectories(root.resolve("results"));
        Files.copy(fixture(), results.resolve("xunit-probe.trx"));
        String summary = SurefireReportMain.convert(results, root.resolve("reports"));
        require(summary.equals("M3-SUREFIRE trx=1 tests=5 failures=2 errors=0 skipped=1 reports=1"), summary);
        Document report = xml(root.resolve("reports/TEST-Calc.Tests.CalculatorTests.xml"));
        Element suite = report.getDocumentElement();
        require(suite.getAttribute("name").equals("Calc.Tests.CalculatorTests") && suite.getAttribute("tests").equals("5")
                && suite.getAttribute("failures").equals("2") && suite.getAttribute("skipped").equals("1")
                && suite.getAttribute("errors").equals("0") && suite.getAttribute("time").equals("2.131"), "suite counts");
        require(report.getElementsByTagName("failure").getLength() == 2
                && report.getElementsByTagName("failure").item(0).getTextContent().contains("Expected: 5"), "failure text");
        passed++;
    }

    /** The cases of the xUnit run: the message is the error message, then the stack trace. */
    private static void readsXunitCases() throws Exception {
        List<TestSuiteReport.Case> cases = TrxReport.read(fixture());
        require(cases.get(0).name().equals("Adds") && cases.get(0).output().equals("adding 2 and 3"), "passed + stdout");
        require(cases.get(1).name().equals("AddsCases(a: 2, b: 2, sum: 5)")
                && cases.get(1).status() == TestSuiteReport.Status.FAILED
                && cases.get(1).message().contains("CalculatorTests.cs:line 28"), "theory row failure + stack trace");
        require(cases.get(3).status() == TestSuiteReport.Status.SKIPPED && cases.get(3).message().equals("not ready"),
                "skip");
        passed++;
    }

    /** VSTest outcomes: Completed and Warning pass, NotRunnable is an error, Inconclusive is skipped. */
    private static void mapsOutcomesNamesAndDurations(Path root) throws Exception {
        String results = result("a", "Divides", "Error", "x") + result("b", "Waits", "Timeout", "")
                + result("c", "Maybe", "Inconclusive", "") + result("d", "Late", "PassedButRunAborted", "")
                + result("e", "Orphan", "Passed", "") + result("f", "Done", "Completed", "")
                + result("g", "Warns", "Warning", "") + result("h", "Cannot", "NotRunnable", "");
        String definitions = Stream.of("a", "b", "c", "d", "f", "g", "h")
                .map(id -> definitions(id, "Lib.MathTests")).collect(Collectors.joining());
        Path file = root.resolve("mstest.trx");
        Files.writeString(file, trx(results, definitions), StandardCharsets.UTF_8);
        List<String> seen = TrxReport.read(file).stream().map(c -> c.className() + "#" + c.name() + "=" + c.status()).toList();
        require(seen.equals(List.of("Lib.MathTests#Divides=ERRORED", "Lib.MathTests#Waits=ERRORED",
                "Lib.MathTests#Maybe=SKIPPED", "Lib.MathTests#Late=PASSED", "dotnet#Orphan=PASSED",
                "Lib.MathTests#Done=PASSED", "Lib.MathTests#Warns=PASSED", "Lib.MathTests#Cannot=ERRORED")),
                "outcomes " + seen);
        passed++;
    }

    private static void readsTimeSpanDurations() {
        require(TrxReport.seconds("1.02:03:04.5") == 93_784.5 && TrxReport.seconds("") == 0
                && TrxReport.seconds("00:00:00.0010000") == 0.001, "TimeSpan durations");
        passed++;
    }

    private static void refusesUnsafeOrForeignXml(Path root) throws Exception {
        Path entity = root.resolve("entity.trx");
        Files.writeString(entity, "<?xml version=\"1.0\"?><!DOCTYPE TestRun [<!ENTITY x \"y\">]>"
                + "<TestRun xmlns=\"" + NS + "\"><Results/></TestRun>", StandardCharsets.UTF_8);
        refuses(() -> TrxReport.read(entity), "a document type declaration");
        Path foreign = root.resolve("junit.trx");
        Files.writeString(foreign, "<testsuite name=\"x\"/>", StandardCharsets.UTF_8);
        refuses(() -> TrxReport.read(foreign), "a document that is not a TRX TestRun");
        Path broken = root.resolve("broken.trx");
        Files.writeString(broken, "<TestRun xmlns=\"" + NS + "\">", StandardCharsets.UTF_8);
        refuses(() -> TrxReport.read(broken), "truncated XML");
        passed++;
    }

    /** The one surefire writer: a message with more lines (a stack trace) becomes the failure's text too. */
    private static void rendersSurefireXml() {
        var report = new TestSuiteReport("A.B", List.of(
                new TestSuiteReport.Case("A.B", "ok", 0.0012, TestSuiteReport.Status.PASSED, "", ""),
                new TestSuiteReport.Case("A.B", "bad<1>", 1, TestSuiteReport.Status.FAILED, "x \"y\"\nmore\nat A",
                        "out\u0001")));
        require(SurefireXmlWriter.render(report).equals(XML_HEAD
                + " time=\"1.001\" tests=\"2\" errors=\"0\" skipped=\"0\" failures=\"1\">\n"
                + "  <testcase name=\"ok\" classname=\"A.B\" time=\"0.001\"/>\n"
                + "  <testcase name=\"bad&lt;1&gt;\" classname=\"A.B\" time=\"1.000\">\n"
                + "    <failure message=\"x &quot;y&quot;\" type=\"native\">x &quot;y&quot;\nmore\nat A</failure>\n"
                + "    <system-out>out</system-out>\n  </testcase>\n</testsuite>\n"), "exact surefire XML");
        require(SurefireXmlWriter.fileName("Name Space/Class+Nested").equals("TEST-Name_Space_Class_Nested.xml"),
                "file name");
        passed++;
    }

    private static void validatesProviderArguments(Path root) throws Exception {
        var command = M3ToolProviders.command(new TaskManifest.Task("report", "@m3:surefire:transpiler:trx", ".",
                List.of("target/r", "target/s"), List.of(), List.of("target/r"), List.of("target/s")), root);
        require(command.subList(1, 5).equals(List.of("-cp", SurefireReportProvider.codeSource(),
                SurefireReportMain.class.getName(), "trx")) && command.subList(5, 7).equals(List.of("target/r", "target/s")),
                "converter argv " + command);
        for (List<String> args : List.of(List.of("target/r"), List.of("target/r", "target/s", "dotnet"),
                List.of("../r", "target/s"), List.of(root.toAbsolutePath().toString(), "target/s"))) {
            refuses(() -> M3ToolProviders.command(new TaskManifest.Task("report", "@m3:surefire:transpiler:trx", ".",
                    args, List.of(), List.of("target/r"), List.of("target/s")), root), "transpiler " + args);
        }
        refuses(() -> M3ToolProviders.command(new TaskManifest.Task("test", "@m3:surefire:interpreter:trx", ".",
                List.of("target/r", "target/s"), List.of(), List.of(), List.of()), root), "interpreter without a tool");
        passed++;
    }

    private static void wrapsTheTestToolLikeSurefire(Path root) throws Exception {
        Path stale = Files.createDirectories(root.resolve("target/r")).resolve("stale.trx");
        Files.copy(fixture(), stale);
        String manifest = TaskManifest.MAGIC + "\n" + wrapped("pass", "0") + wrapped("fail", "1");
        Files.writeString(root.resolve("m3-build.tsv"), manifest, StandardCharsets.UTF_8);
        TaskManifest plan = TaskManifest.load(root.resolve("m3-build.tsv"));
        TaskRunner.run(plan, root, "pass", false, Duration.ofSeconds(120), line -> { });
        require(!Files.exists(stale) && Files.isRegularFile(root.resolve("target/r/run.trx")), "stale TRX removed first");
        Path report = root.resolve("target/s/TEST-Lib.Tests.MathTests.xml");
        require(Files.isRegularFile(report) && !Files.exists(root.resolve("target/s/TEST-Calc.Tests.CalculatorTests.xml")),
                "only the new run is reported");
        failingToolStillReports(root, plan, report);
        passed++;
    }

    private static void failingToolStillReports(Path root, TaskManifest plan, Path report) throws Exception {
        Files.delete(report);
        String failure = refuses(() -> TaskRunner.run(plan, root, "fail", false, Duration.ofSeconds(120), line -> { }),
                "failing tests");
        require(failure.contains("polyglot tool failed: fail (exit 1)") && Files.isRegularFile(report),
                "failing tests fail the task and still get their surefire report: " + failure);
        require(SurefireReportMain.run(List.of("trx", "target/none", "target/s2"), root) == 0
                && !Files.exists(root.resolve("target/s2")), "no TRX: nothing to report, no folder");
    }

    private static String wrapped(String name, String exit) throws IOException {
        StringBuilder text = new StringBuilder("task\t" + name + "\t@m3:surefire:interpreter:trx\t.\n");
        for (String arg : List.of("target/r", "target/s", EcjCompilerProvider.java(), "-cp", testClasses(),
                FakeDotnetTest.class.getName(), "target/r", exit)) {
            text.append("arg\t").append(name).append('\t').append(arg).append('\n');
        }
        return text.toString();
    }

    private static void namesNugetPackages(Path root) throws Exception {
        Files.createDirectories(root);
        String nuspec = NUSPEC;
        zip(root.resolve("Synexia.Probe.Calc.1.2.3.nupkg"), "Synexia.Probe.Calc.nuspec", nuspec);
        zip(root.resolve("Synexia.Probe.Calc.1.2.3.snupkg"), "Synexia.Probe.Calc.nuspec", nuspec);
        Files.writeString(root.resolve("notes.txt"), "not a package", StandardCharsets.UTF_8);
        List<String> seen = NugetPackages.unique(NugetPackages.list(root)).stream()
                .map(p -> p.type() + ":" + p.id() + ":" + p.version()).toList();
        require(seen.equals(List.of("nupkg:Synexia.Probe.Calc:1.2.3", "snupkg:Synexia.Probe.Calc:1.2.3")), "ids " + seen);
        passed++;
    }

    /** Two versions of one package, a package without a root .nuspec, an id that is no NuGet id, no folder. */
    private static void refusesBadNugetPackages(Path root) throws Exception {
        String nuspec = NUSPEC;
        zip(root.resolve("Synexia.Probe.Calc.1.2.2.nupkg"), "Synexia.Probe.Calc.nuspec", nuspec.replace("1.2.3", "1.2.2"));
        refuses(() -> NugetPackages.unique(NugetPackages.list(root)), "two versions of one package");
        Path nested = root.resolve("nested.nupkg");
        zip(nested, "lib/x.nuspec", nuspec);
        refuses(() -> NugetPackages.read(nested, "nupkg"), "no root .nuspec");
        Path bad = root.resolve("bad.nupkg");
        zip(bad, "bad.nuspec", nuspec.replace("Synexia.Probe.Calc", "../escape"));
        refuses(() -> NugetPackages.read(bad, "nupkg"), "an id that is not a NuGet id");
        require(NugetPackages.list(root.resolve("missing")).isEmpty(), "no folder, no packages");
        passed++;
    }

    /** A TRX document as the VSTest logger writes it, byte order mark included. */
    static String trx(String results, String definitions) {
        return (char) 0xFEFF + "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<TestRun id=\"r\" name=\"run\" xmlns=\""
                + NS + "\"><Results>" + results + "</Results><TestDefinitions>" + definitions
                + "</TestDefinitions></TestRun>\n";
    }

    static String result(String id, String name, String outcome, String message) {
        String error = message.isEmpty() ? "" : "<Output><ErrorInfo><Message>" + message
                + "</Message></ErrorInfo></Output>";
        return "<UnitTestResult testId=\"" + id + "\" testName=\"" + name + "\" duration=\"00:00:00.5000000\" outcome=\""
                + outcome + "\">" + error + "<InnerResults><UnitTestResult testId=\"" + id + "\" testName=\"row\" "
                + "outcome=\"Passed\"/></InnerResults></UnitTestResult>";
    }

    static String definitions(String id, String className) {
        return "<UnitTest name=\"x\" id=\"" + id + "\"><TestMethod className=\"" + className + "\" name=\"x\"/></UnitTest>";
    }

    private static Path fixture() throws Exception {
        return Path.of(DotnetReportsContractTestMain.class.getResource("/dotnet/xunit-probe.trx").toURI());
    }

    private static String testClasses() throws IOException {
        try {
            return Path.of(FakeDotnetTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException failure) {
            throw new IOException(failure);
        }
    }

    private static void zip(Path file, String entry, String text) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(text.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static Document xml(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(file.toFile());
    }

    @FunctionalInterface private interface Attempt { void run() throws Exception; }

    private static String refuses(Attempt attempt, String label) throws Exception {
        try {
            attempt.run();
        } catch (IOException expected) {
            return String.valueOf(expected.getMessage());
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
