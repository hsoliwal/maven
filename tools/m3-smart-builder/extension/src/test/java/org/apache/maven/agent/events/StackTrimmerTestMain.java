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
package org.apache.maven.agent.events;

import java.util.List;

/** Stack trimming: framework runs collapse, the root cause stays, the cap keeps the root cause header. */
public final class StackTrimmerTestMain {
    static final String TRACE = "java.lang.IllegalStateException: wrapper\n"
            + "\tat org.junit.Assert.fail(Assert.java:89)\n"
            + "\tat org.junit.Assert.assertTrue(Assert.java:42)\n"
            + "\tat com.example.app.BTest.adds(BTest.java:12)\n"
            + "\tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)\n"
            + "Caused by: java.lang.ArithmeticException: / by zero\n"
            + "\tat com.example.app.Calc.div(Calc.java:7)\n"
            + "\tat com.example.app.BTest.adds(BTest.java:11)\n"
            + "\t... 30 more\n";

    private StackTrimmerTestMain() {}

    public static void main(String[] args) {
        Check.equal("com.example.", StackTrimmer.appPackage("com.example.app.BTest"), "two package segments");
        Check.equal("fix.", StackTrimmer.appPackage("fix.BTest"), "one package segment");
        Check.equal("", StackTrimmer.appPackage("BTest"), "default package keeps every frame");
        List<String> trimmed = StackTrimmer.trim(TRACE, "com.example.", 0);
        Check.equal(List.of("java.lang.IllegalStateException: wrapper", "... 2 framework frames omitted",
                "at com.example.app.BTest.adds(BTest.java:12)", "... 1 framework frames omitted",
                "Caused by: java.lang.ArithmeticException: / by zero", "at com.example.app.Calc.div(Calc.java:7)",
                "at com.example.app.BTest.adds(BTest.java:11)", "... 1 framework frames omitted"), trimmed,
                "framework runs collapse, application frames and the cause chain stay");
        List<String> capped = StackTrimmer.trim(TRACE, "com.example.", 4);
        Check.equal(List.of("java.lang.IllegalStateException: wrapper", "... (intermediate frames truncated)",
                "Caused by: java.lang.ArithmeticException: / by zero", "at com.example.app.Calc.div(Calc.java:7)"), capped,
                "the cap keeps the top header, the root cause header and its first frame");
        Check.equal(9, StackTrimmer.trim(TRACE, "", 0).size(), "a blank package keeps every line");
        Check.equal(List.of(), StackTrimmer.trim("  ", "x", 3), "a blank trace has no lines");
        String longHeader = "java.lang.AssertionError: " + "x".repeat(300);
        Check.equal(203, StackTrimmer.trim(longHeader, "", 0).get(0).length(), "a long header is cut to 200 + ...");
        Check.that(StackTrimmer.structural("\tSuppressed: java.io.IOException") && !StackTrimmer.structural("Caused by: x"),
                "Suppressed lines and only indented Caused by lines are structural");
        Check.done("StackTrimmerTestMain");
    }
}
