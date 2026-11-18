/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * cargo test output to surefire reports: the parser on recorded output (a fixture captured from cargo 1.94 on
 * Windows), crash and old-panic discriminators, the XML, the provider's planned command, and, when cargo is on PATH,
 * a real run of a mavenized crate through the task runner.
 */
public final class CargoTestReportContractTestMain {
    private static int passed;

    private CargoTestReportContractTestMain() {}

    public static void main(String[] ignored) throws Exception {
        Path temp = Files.createTempDirectory("cargo-report-test-");
        try {
            readsRecordedCargoOutput();
            reportsACrashedBinaryAsAnError();
            aPassingCustomHarnessIsOnePassedCase();
            writesSurefireXml(temp.resolve("reports"));
            plansTheReportRunner(temp.resolve("plan"));
            plansTheSurefireLibtestFormat(temp.resolve("libtest"));
            runsAMavenizedCrate(temp.resolve("crate"));
            System.out.println("PASS: " + passed + " cargo test report contract cases");
        } finally {
            erase(temp);
        }
    }

    private static void readsRecordedCargoOutput() throws Exception {
        List<TestSuiteReport> suites = parse(fixture("cargo/cargo-test-windows.txt"));
        require(suites.stream().map(TestSuiteReport::name).toList()
                .equals(List.of("demo src/lib.rs", "outer tests/outer.rs", "demo doc-tests")), "suites: " + suites);
        TestSuiteReport unit = suites.get(0);
        require(unit.summary().equals("Tests run: 4, Failures: 1, Errors: 0, Skipped: 1"), unit.summary());
        TestSuiteReport.Case failed = find(unit, "tests::fails");
        require(failed.status() == TestSuiteReport.Status.FAILED
                && failed.message().equals("assertion `left == right` failed: one plus one"), "message: " + failed);
        require(failed.output().contains("left: 2") && failed.output().contains("right: 3")
                && !failed.output().contains("failures:"), "failure section only: " + failed.output());
        require(find(unit, "tests::slow").message().equals("slow"), "ignore reason kept");
        require(find(unit, "tests::adds").output().isEmpty(), "a passing case has no output");
        require(suites.get(2).cases().get(0).name().equals("src\\lib.rs - add (line 2)"), "doc test name kept");
        passed++;
    }

    /** A failing binary with a pre-1.73 panic line, then a binary that crashes after its first test. */
    private static final List<String> CRASHED_RUN = List.of(
                "     Running unittests src/lib.rs (target/debug/deps/my_crate-0123456789abcdef)", "",
                "running 2 tests", "test a::ok ... ok", "test a::old ... FAILED", "", "failures:", "",
                "---- a::old stdout ----", "thread 'a::old' panicked at 'old style', src/lib.rs:3:5", "",
                "failures:", "    a::old", "",
                "test result: FAILED. 1 passed; 1 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.01s",
                "     Running tests/it.rs (target/debug/deps/it-fedcba9876543210)", "", "running 3 tests",
                "test first ... ok", "error: test failed, to rerun pass `--test it`", "",
                "Caused by:", "  process didn't exit successfully: (signal: 11, SIGSEGV: invalid memory reference)");

    /** A [[test]] harness = false binary prints no libtest lines; when it passes it is one passed case. */
    private static void aPassingCustomHarnessIsOnePassedCase() {
        List<String> run = List.of("     Running tests/custom.rs (target/debug/deps/custom-0123456789abcdef)",
                "custom harness ran");
        TestSuiteReport custom = parse(run).get(0);
        require(custom.name().equals("custom tests/custom.rs")
                && custom.summary().equals("Tests run: 1, Failures: 0, Errors: 0, Skipped: 0"),
                "a passing custom harness is no error: " + custom.summary());
        require(parse(run, 101).get(0).count(TestSuiteReport.Status.ERRORED) == 1,
                "cargo failing without naming a binary: the binary without a result line is an error");
        passed++;
    }

    private static void reportsACrashedBinaryAsAnError() {
        List<TestSuiteReport> suites = parse(CRASHED_RUN);
        require(suites.get(0).name().equals("my_crate src/lib.rs")
                && find(suites.get(0), "a::old").message().equals("old style"), "pre-1.73 panic message: " + suites);
        TestSuiteReport crashed = suites.get(1);
        require(crashed.name().equals("it tests/it.rs")
                && crashed.summary().equals("Tests run: 2, Failures: 0, Errors: 1, Skipped: 0"),
                "a binary without a result line is an error, not one passing test: " + crashed.summary());
        require(parse(List.of("   Compiling x v0.1.0", "error[E0425]: cannot find value")).isEmpty(),
                "a build failure has no suites");
        passed++;
    }

    private static void writesSurefireXml(Path reports) throws Exception {
        List<TestSuiteReport.Case> cases = List.of(
                new TestSuiteReport.Case("c src/lib.rs", "t::fails", 0, TestSuiteReport.Status.FAILED, "a < b & \"c\"",
                        "\u001b[31mred\u001b[0m <out>"),
                new TestSuiteReport.Case("c src/lib.rs", "t::skip", 0, TestSuiteReport.Status.SKIPPED, "slow", ""));
        Path file = SurefireXmlWriter.write(new TestSuiteReport("c src/lib.rs", cases), reports);
        require(file.getFileName().toString().equals("TEST-c_src_lib.rs.xml"), "sanitized file name: " + file);
        Element suite = xml(file).getDocumentElement();
        require(suite.getAttribute("tests").equals("2") && suite.getAttribute("failures").equals("1")
                && suite.getAttribute("skipped").equals("1") && suite.getAttribute("errors").equals("0"), "counts");
        Element failure = (Element) suite.getElementsByTagName("failure").item(0);
        require(failure.getAttribute("message").equals("a < b & \"c\""), "escaped message round-trips");
        require(suite.getElementsByTagName("system-out").item(0).getTextContent().equals("[31mred[0m <out>"),
                "characters XML forbids (ANSI escape) are dropped, the rest kept");
        passed++;
    }

    private static void plansTheReportRunner(Path root) throws Exception {
        Files.createDirectories(root);
        List<String> argv = M3ToolProviders.command(task(CargoTestProvider.TOOL, "test", "--locked"), root);
        require(argv.subList(0, 4).equals(List.of(EcjCompilerProvider.java(), "-cp", SurefireReportProvider.codeSource(),
                SurefireReportMain.class.getName())) && argv.get(4).equals("libtest"),
                "the surefire report runner's libtest format: " + argv);
        require(Path.of(argv.get(5)).equals(root.toAbsolutePath().normalize().resolve("target/surefire-reports"))
                && argv.subList(6, argv.size()).equals(List.of("cargo", "test", "--locked")), "argv: " + argv);
        expectRefused(task(CargoTestProvider.TOOL, "build"), root, "only cargo test");
        expectRefused(task(CargoTestProvider.TOOL), root, "no arguments");
        expectRefused(task("@m3:cargo:compiler:rust", "test"), root, "no compiler role");
        expectRefused(task("@m3:cargo:interpreter:cpp", "test"), root, "Rust only");
        passed++;
    }

    /** {@code @m3:surefire:interpreter:libtest <reports> <tool> <argument>...}: the same runner, any libtest tool. */
    private static void plansTheSurefireLibtestFormat(Path root) throws Exception {
        Files.createDirectories(root);
        List<String> argv = M3ToolProviders.command(task("@m3:surefire:interpreter:libtest", "target/r", "cargo", "test"),
                root);
        require(argv.subList(3, argv.size()).equals(List.of(SurefireReportMain.class.getName(), "libtest", "target/r",
                "cargo", "test")), "libtest argv: " + argv);
        expectRefused(task("@m3:surefire:interpreter:libtest", "target/r"), root, "libtest without a tool");
        expectRefused(task("@m3:surefire:interpreter:libtest", "../r", "cargo", "test"), root, "a path outside");
        expectRefused(new TaskManifest.Task("t", "@m3:surefire:transpiler:libtest", ".", List.of("target/r", "target/s"),
                List.of(), List.of("target/r"), List.of("target/s")), root, "libtest has no file to convert");
        passed++;
    }

    /** With cargo on PATH: a crate with a failing test fails the test target and still leaves its reports. */
    private static void runsAMavenizedCrate(Path crate) throws Exception {
        if (TaskRunner.resolveTool("cargo", Path.of(".")).equals("cargo")) {
            System.out.println("NOT RUN: real cargo test (cargo is not on PATH)");
            return;
        }
        writeCrate(crate);
        Mavenizer.mavenize(crate, "org.example", "1", true);
        String failure = run(TaskManifest.load(crate.resolve("m3-build.tsv")), crate);
        require(failure.contains("cargo-test") && failure.contains("exit 101"), "failing tests fail the target: " + failure);
        Element unit = xml(crate.resolve("target/surefire-reports/TEST-probe_src_lib.rs.xml")).getDocumentElement();
        require(unit.getAttribute("tests").equals("3") && unit.getAttribute("failures").equals("1")
                && unit.getAttribute("skipped").equals("1"), "real report counts");
        require(Files.isRegularFile(crate.resolve("target/surefire-reports/TEST-adds_tests_adds.rs.xml"))
                && Files.isRegularFile(crate.resolve("target/surefire-reports/TEST-probe_doc-tests.xml")), "--no-fail-fast");
        passed++;
    }

    private static String run(TaskManifest plan, Path crate) throws InterruptedException {
        try {
            TaskRunner.run(plan, crate, "test", false, Duration.ofMinutes(10), line -> {});
            return "no failure";
        } catch (IOException expected) {
            return expected.getMessage();
        }
    }

    private static void writeCrate(Path crate) throws IOException {
        Files.createDirectories(crate.resolve("src"));
        Files.createDirectories(crate.resolve("tests"));
        Files.writeString(crate.resolve("Cargo.toml"), "[package]\nname = \"probe\"\nversion = \"0.1.0\"\n"
                + "edition = \"2021\"\n", StandardCharsets.UTF_8);
        Files.writeString(crate.resolve("src/lib.rs"), "/// ```\n/// assert_eq!(probe::twice(2), 4);\n/// ```\n"
                + "pub fn twice(x: i32) -> i32 { x * 2 }\n#[cfg(test)]\nmod tests {\n"
                + "    #[test] fn ok() { assert_eq!(super::twice(1), 2); }\n"
                + "    #[test] fn wrong() { assert_eq!(super::twice(1), 3, \"twice one\"); }\n"
                + "    #[test] #[ignore] fn later() {}\n}\n", StandardCharsets.UTF_8);
        Files.writeString(crate.resolve("tests/adds.rs"), "#[test] fn adds() { assert_eq!(probe::twice(0), 0); }\n",
                StandardCharsets.UTF_8);
    }

    private static void expectRefused(TaskManifest.Task task, Path root, String message) {
        try {
            M3ToolProviders.command(task, root);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("must be refused: " + message);
    }

    private static TaskManifest.Task task(String tool, String... args) {
        return new TaskManifest.Task("cargo-test", tool, ".", List.of(args), List.of(), List.of(), List.of());
    }

    private static List<TestSuiteReport> parse(List<String> lines) {
        return parse(lines, 0);
    }

    private static List<TestSuiteReport> parse(List<String> lines, int exit) {
        CargoTestOutput parser = new CargoTestOutput();
        lines.forEach(parser::accept);
        return parser.finish(exit);
    }

    private static List<String> fixture(String name) throws IOException {
        try (InputStream in = CargoTestReportContractTestMain.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IOException("missing test resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    private static TestSuiteReport.Case find(TestSuiteReport suite, String name) {
        return suite.cases().stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no case " + name + " in " + suite));
    }

    private static Document xml(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(file.toFile());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void erase(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
