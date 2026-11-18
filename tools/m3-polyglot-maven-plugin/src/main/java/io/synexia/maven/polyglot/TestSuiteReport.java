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
package io.synexia.maven.polyglot;

import java.util.List;

/**
 * Test results of one suite, whatever wrote them (cargo test, CTest, an exit status), before they become a surefire
 * report. Same record, cases and statuses as the native-build plugin's {@code engine.TestSuiteReport}, so that plugin
 * can use this one.
 *
 * @param name suite name
 * @param cases the test cases
 */
public record TestSuiteReport(String name, List<Case> cases) {
    /** Outcome of one case. */
    public enum Status {
        /** Passed. */
        PASSED,
        /** Failed. */
        FAILED,
        /** Could not run properly (the test binary ended without a result). */
        ERRORED,
        /** Skipped or ignored. */
        SKIPPED
    }

    /**
     * One test case.
     *
     * @param className surefire class name
     * @param name case name
     * @param seconds duration, 0 when the tool does not report one
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

    /** {@code Tests run: n, Failures: n, Errors: n, Skipped: n}, as surefire prints it. */
    public String summary() {
        return "Tests run: " + cases.size() + ", Failures: " + count(Status.FAILED) + ", Errors: "
                + count(Status.ERRORED) + ", Skipped: " + count(Status.SKIPPED);
    }
}
