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
package org.apache.maven.plugins.codepatch;

import java.util.Arrays;

/** Check bookkeeping for the main-class tests: one PASS or FAIL line per check on standard error. */
final class Checks {
    private static int failures;

    /** An action that should be refused. */
    interface Action {
        void run() throws Exception;
    }

    private Checks() { }

    static int failures() {
        return failures;
    }

    /** {@code done}, or the exception the action threw. */
    static String outcome(Action action) {
        try {
            action.run();
            return "done";
        } catch (Exception e) {
            return e.toString();
        }
    }

    /** Runs a group of checks; an exception it throws is one failure, and the next group still runs. */
    static void group(String name, Action action) {
        try {
            action.run();
        } catch (Exception | AssertionError e) {
            check(name + " threw " + e, false);
        }
    }

    static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.err.println((ok ? "PASS " : "FAIL ") + name);
    }

    /** Checks that the action throws a {@link PatchException} whose message holds every fragment. */
    static void refuses(String name, Action action, String... fragments) {
        String message;
        try {
            action.run();
            message = null;
        } catch (PatchException e) {
            message = e.getMessage();
        } catch (Exception e) {
            message = "unexpected " + e;
        }
        String shown = message;
        check(name + " -> " + shown, message != null && Arrays.stream(fragments).allMatch(shown::contains));
    }
}
