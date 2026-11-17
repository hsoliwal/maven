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
 * A one-case report from the exit status of a test step whose tool writes no machine-readable results (a
 * hand-written {@code make check} or {@code ninja test}).
 */
public final class ExitStatusReport {
    private ExitStatusReport() {}

    /** One case named after the step; failed with {@code failure} as message when it is not empty. */
    public static TestSuiteReport of(String suite, String caseName, String failure, double seconds) {
        TestSuiteReport.Status status = failure.isEmpty() ? TestSuiteReport.Status.PASSED : TestSuiteReport.Status.FAILED;
        return new TestSuiteReport(suite, List.of(new TestSuiteReport.Case(suite, caseName, seconds, status, failure,
                "")));
    }
}
