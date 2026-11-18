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
package org.apache.maven.plugins.nativebuild.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Collects the results of a native test step into one suite: CTest or Meson JUnit, Automake .trs files, or the exit
 * status when the tool writes nothing readable. A failed step whose results show no failure gets an error case, so
 * a tool that did not start or crashed is never reported as green; a tool that could not be started is an error,
 * not a test failure. A JUnit file without cases from a passing step (CTest found no tests) is reported as no tests,
 * not as one passing case; {@code --no-tests=error} in testArgs makes CTest fail instead.
 */
public final class TestReports {
    /** Failed cases named in Maven's error. */
    public static final int SHOWN_CASES = 10;

    private static final int SHOWN_LINES = 3;
    private static final int MAX_LINE = 400;

    private TestReports() {}

    /** The suite name: {@code native.<system>.<classifier>}. */
    public static String suite(PlanContext c, String classifier) {
        return "native." + c.system().family() + "." + classifier;
    }

    /** The test output file the step writes, if it writes one this plugin reads as JUnit. */
    public static Path junitFile(PlanContext c, StepPlanner planner) throws IOException {
        return switch (c.system()) {
            case CMAKE, CMAKE_PRESETS -> c.layout().path(c.layout().inBuild(CMakePlanner.JUNIT));
            case MESON -> c.layout().path(planner.buildDirectory(c)).resolve(MesonPlanner.JUNIT);
            default -> c.layout().path(c.layout().inBuild("no-junit.xml"));
        };
    }

    /** The results; {@code failure} is the step's error message, empty when it succeeded. */
    public static TestSuiteReport collect(PlanContext c, StepPlanner planner, String suite, String failure,
            double seconds) throws IOException {
        Path junit = junitFile(c, planner);
        TestSuiteReport report;
        if (Files.isRegularFile(junit)) {
            report = JUnitReport.read(junit, suite);
            if (report.cases().isEmpty() && failure.isEmpty()) {
                return report;
            }
        } else if (c.system() == BuildSystem.AUTOTOOLS || c.system() == BuildSystem.AUTOTOOLS_BOOTSTRAP) {
            report = AutomakeTrsReport.read(c.layout().path(c.layout().build()), suite);
        } else {
            report = new TestSuiteReport(suite, List.of());
        }
        return report.cases().isEmpty() ? ExitStatusReport.of(suite, c.system().family() + " test step", failure,
                seconds) : withFailure(report, failure);
    }

    /**
     * What Maven's error shows for failed native tests, as surefire does: each failed or errored case (at most
     * {@value #SHOWN_CASES}) with the first lines of its message and output, then the test step's own failure,
     * which ends with the last lines of the tool's output. Empty when nothing failed and the step passed.
     */
    public static String failureDetails(TestSuiteReport report, String failure) {
        List<TestSuiteReport.Case> bad = report.cases().stream().filter(TestReports::failed).toList();
        Stream<String> cases = bad.stream().limit(SHOWN_CASES).map(TestReports::caseLine);
        Stream<String> more = bad.size() > SHOWN_CASES ? Stream.of("  ... and " + (bad.size() - SHOWN_CASES)
                + " more") : Stream.empty();
        Stream<String> step = failure.isBlank() ? Stream.empty() : Stream.of("test step: " + failure);
        return Stream.of(cases, more, step).flatMap(s -> s).collect(Collectors.joining(System.lineSeparator()));
    }

    private static boolean failed(TestSuiteReport.Case c) {
        return c.status() == TestSuiteReport.Status.FAILED || c.status() == TestSuiteReport.Status.ERRORED;
    }

    private static String caseLine(TestSuiteReport.Case c) {
        String text = c.message() + "\n" + c.output();
        String first = text.lines().map(String::strip).filter(l -> !l.isEmpty()).limit(SHOWN_LINES)
                .collect(Collectors.joining(" | "));
        String line = "  " + c.status() + " " + c.name() + (first.isEmpty() ? "" : ": " + first);
        return line.length() > MAX_LINE ? line.substring(0, MAX_LINE) + " ..." : line;
    }

    private static TestSuiteReport withFailure(TestSuiteReport report, String failure) {
        boolean shown = report.count(TestSuiteReport.Status.FAILED) + report.count(TestSuiteReport.Status.ERRORED) > 0;
        if (failure.isEmpty() || shown) {
            return report;
        }
        List<TestSuiteReport.Case> cases = new ArrayList<>(report.cases());
        cases.add(new TestSuiteReport.Case(report.name(), "test step", 0, TestSuiteReport.Status.ERRORED, failure, ""));
        return new TestSuiteReport(report.name(), cases);
    }
}
