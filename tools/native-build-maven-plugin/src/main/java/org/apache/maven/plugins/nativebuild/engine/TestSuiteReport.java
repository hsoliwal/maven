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

import java.util.List;

/**
 * Native test results in one suite, whatever wrote them (CTest, Meson, Automake, an exit status), before they become
 * a surefire report.
 *
 * @param name suite name, {@code native.<system>.<classifier>}
 * @param cases the test cases
 */
public record TestSuiteReport(String name, List<Case> cases) {
    /** Outcome of one case. */
    public enum Status {
        /** Passed, or an expected failure (Automake XFAIL). */
        PASSED,
        /** Failed, or an unexpected pass (Automake XPASS). */
        FAILED,
        /** Could not run properly (Automake ERROR, a tool that did not start). */
        ERRORED,
        /** Skipped or disabled. */
        SKIPPED
    }

    /**
     * One test case.
     *
     * @param className surefire class name
     * @param name case name
     * @param seconds duration
     * @param status outcome
     * @param message failure, error or skip message, empty when passed
     * @param output captured output, empty when none
     */
    public record Case(String className, String name, double seconds, Status status, String message, String output) {}

    /** Copies the list. */
    public TestSuiteReport {
        cases = List.copyOf(cases);
    }

    /** The number of cases with {@code status}. */
    public long count(Status status) {
        return cases.stream().filter(c -> c.status() == status).count();
    }

    /** Total duration. */
    public double seconds() {
        return cases.stream().mapToDouble(Case::seconds).sum();
    }

    /** The same cases under another suite name. */
    public TestSuiteReport named(String suite) {
        return new TestSuiteReport(suite, cases);
    }

    /** {@code Tests run: n, Failures: n, Errors: n, Skipped: n}, as surefire prints it. */
    public String summary() {
        return "Tests run: " + cases.size() + ", Failures: " + count(Status.FAILED) + ", Errors: "
                + count(Status.ERRORED) + ", Skipped: " + count(Status.SKIPPED);
    }
}
