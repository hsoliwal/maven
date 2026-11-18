/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/**
 * CTest results as surefire reports: the test step of a mavenized CMake folder writes CTest's JUnit XML to
 * target/ctest and converts it, so a disabled test is skipped, a test CTest could not run is an error and the suite
 * has a name. With cmake and ctest on PATH it runs a real CMake project whose tests need no compiler.
 */
public final class CTestReportContractTestMain {
    /** Four tests: one passes, one fails, one is disabled, one names a program that does not exist. */
    static final String CMAKE_PROJECT = "cmake_minimum_required(VERSION 3.21)\nproject(probe NONE)\nenable_testing()\n"
            + "add_test(NAME passes COMMAND ${CMAKE_COMMAND} -E echo hello)\n"
            + "add_test(NAME fails COMMAND ${CMAKE_COMMAND} -E false)\n"
            + "add_test(NAME off COMMAND ${CMAKE_COMMAND} -E echo never)\n"
            + "set_tests_properties(off PROPERTIES DISABLED TRUE)\n"
            + "add_test(NAME missing COMMAND does-not-exist-4711)\n";

    private static int passed;

    private CTestReportContractTestMain() { }

    public static void main(String[] ignored) throws Exception {
        Path temp = Files.createTempDirectory("ctest-report-test-");
        try {
            normalisesARealCTestFile();
            convertsTheResultsFolder(temp.resolve("convert"));
            runsAMavenizedCMakeProject(temp.resolve("cmake"));
            System.out.println("PASS: " + passed + " CTest report contract cases");
        } finally {
            erase(temp);
        }
    }

    /** ctest 4.3.1's own file for CMAKE_PROJECT: disabled is skipped, notrun is an error, the suite is named. */
    private static void normalisesARealCTestFile() throws Exception {
        TestSuiteReport report = JUnitReport.read(fixture(), "ctest");
        List<String> seen = report.cases().stream()
                .map(c -> c.className() + "#" + c.name() + "=" + c.status() + ":" + c.message()).toList();
        require(seen.equals(List.of("ctest.passes#passes=PASSED:", "ctest.fails#fails=FAILED:Failed",
                "ctest.off#off=SKIPPED:", "ctest.missing#missing=ERRORED:Unable to find executable")), "cases " + seen);
        require(report.cases().get(0).output().equals("hello\n") && report.cases().get(0).seconds() > 0, "output, time");
        require(JUnitReport.suite(Path.of("target/ctest/ctest-junit.xml")).equals("ctest")
                && JUnitReport.suite(Path.of("x/Other.XML")).equals("Other"), "suite named after the file");
        passed++;
    }

    /** The junit format converts every XML file of the results folder, one report each. */
    private static void convertsTheResultsFolder(Path root) throws Exception {
        Path results = Files.createDirectories(root.resolve("target/ctest"));
        Files.copy(fixture(), results.resolve("ctest-junit.xml"));
        String summary = SurefireReportMain.convert(SurefireReportMain.JUNIT, results, root.resolve("reports"));
        require(summary.equals("M3-SUREFIRE junit=1 tests=4 failures=1 errors=1 skipped=1 reports=1"), summary);
        require(Files.isRegularFile(root.resolve("reports/TEST-ctest.xml")), "TEST-ctest.xml");
        passed++;
    }

    private static Path fixture() throws Exception {
        return Path.of(CTestReportContractTestMain.class.getResource("/ctest/ctest-junit-4.3.1.xml").toURI());
    }

    /** The validate and test targets of a mavenized CMake folder: a failing test fails, the report is normalised. */
    private static void runsAMavenizedCMakeProject(Path root) throws Exception {
        if (TaskRunner.resolveTool("ctest", Path.of(".")).equals("ctest")) {
            System.out.println("NOT RUN: real ctest (ctest is not on PATH)");
            return;
        }
        Files.createDirectories(root);
        Files.writeString(root.resolve("CMakeLists.txt"), CMAKE_PROJECT, StandardCharsets.UTF_8);
        Mavenizer.mavenize(root, "org.example", "1", true);
        TaskManifest plan = TaskManifest.load(root.resolve("m3-build.tsv"));
        TaskRunner.run(plan, root, "validate", false, Duration.ofSeconds(300), line -> { });
        requireNormalisedReport(root, failure(plan, root, "test"));
        passed++;
    }

    private static void requireNormalisedReport(Path root, String failure) throws Exception {
        Element suite = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(root.resolve("target/surefire-reports/TEST-ctest.xml").toFile()).getDocumentElement();
        require(failure.contains("ctest-test") && suite.getAttribute("name").equals("ctest")
                && suite.getAttribute("tests").equals("4") && suite.getAttribute("failures").equals("1")
                && suite.getAttribute("errors").equals("1") && suite.getAttribute("skipped").equals("1"),
                "a normalised CTest report: " + failure + " " + suite.getAttribute("name"));
    }

    private static String failure(TaskManifest plan, Path root, String target) throws Exception {
        try {
            TaskRunner.run(plan, root, target, false, Duration.ofSeconds(300), line -> { });
        } catch (IOException expected) {
            return String.valueOf(expected.getMessage());
        }
        throw new AssertionError("a failing CTest test must fail the " + target + " target");
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
