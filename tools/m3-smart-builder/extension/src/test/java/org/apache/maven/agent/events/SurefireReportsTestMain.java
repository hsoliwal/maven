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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

/** Surefire reports: cases and counts, fresh reports only, no DTD. */
public final class SurefireReportsTestMain {
    /** The failing-test fixture of MavenSkills/mcp-server (Apache-2.0), plus an error and a skipped case. */
    static final String REPORT = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<testsuite name=\"com.example.FailingTest\" time=\"1,234.5\" tests=\"5\" errors=\"1\" skipped=\"1\" failures=\"2\">\n"
            + "  <testcase name=\"shouldPass\" classname=\"com.example.FailingTest\" time=\"0.100\"/>\n"
            + "  <testcase name=\"shouldReturnUser\" classname=\"com.example.FailingTest\" time=\"0.500\">\n"
            + "    <failure message=\"expected:&lt;200&gt; but was:&lt;404&gt;\" type=\"org.opentest4j.AssertionFailedError\">"
            + "org.opentest4j.AssertionFailedError: expected:&lt;200&gt; but was:&lt;404&gt;\n"
            + "\tat org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:150)\n"
            + "\tat com.example.FailingTest.shouldReturnUser(FailingTest.java:42)\n"
            + "\tat java.base/java.lang.reflect.Method.invoke(Method.java:580)</failure>\n"
            + "  </testcase>\n"
            + "  <testcase name=\"shouldHandleNull\" classname=\"com.example.FailingTest\" time=\"0.300\">\n"
            + "    <failure message=\"Unexpected exception: NullPointerException\" type=\"java.lang.AssertionError\">"
            + "java.lang.AssertionError: Unexpected exception: NullPointerException\n"
            + "\tat com.example.FailingTest.shouldHandleNull(FailingTest.java:55)</failure>\n"
            + "  </testcase>\n"
            + "  <testcase name=\"explodes\" classname=\"com.example.FailingTest\" time=\"0.01\">\n"
            + "    <error message=\"boom\" type=\"java.lang.IllegalStateException\">java.lang.IllegalStateException: boom\n"
            + "\tat com.example.FailingTest.explodes(FailingTest.java:60)</error>\n"
            + "  </testcase>\n"
            + "  <testcase name=\"later\" classname=\"com.example.FailingTest\" time=\"0\"><skipped/></testcase>\n"
            + "</testsuite>\n";

    private SurefireReportsTestMain() {}

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("surefire-reports-test");
        long start = System.currentTimeMillis();
        Files.writeString(dir.resolve("TEST-com.example.FailingTest.xml"), REPORT, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("TEST-com.example.Doctype.xml"), "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE testsuite [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>\n<testsuite name=\"x\" tests=\"1\">&x;</testsuite>\n");
        Path stale = dir.resolve("TEST-com.example.Stale.xml");
        Files.writeString(stale, REPORT.replace("FailingTest", "Stale"));
        Files.setLastModifiedTime(stale, FileTime.fromMillis(start - 60_000));
        Files.writeString(dir.resolve("com.example.FailingTest.txt"), "not a report");
        List<SurefireReports.Suite> suites = SurefireReports.read(dir, start);
        Check.equal(1, suites.size(), "only the fresh, DTD-free TEST-*.xml report is read");
        SurefireReports.Suite suite = suites.get(0);
        Check.equal(List.of("com.example.FailingTest", 5, 2, 1, 1, 1_234_500L), List.of(suite.className(), suite.tests(),
                suite.failures(), suite.errors(), suite.skipped(), suite.durationMs()), "suite counts and time with comma");
        Check.equal(List.of("passed", "failed", "failed", "error", "skipped"),
                suite.cases().stream().map(SurefireReports.TestCase::outcome).collect(java.util.stream.Collectors.toList()),
                "case outcomes in report order");
        SurefireReports.TestCase failed = suite.cases().get(1);
        Check.equal(List.of("shouldReturnUser", "org.opentest4j.AssertionFailedError", "expected:<200> but was:<404>", 500L),
                List.of(failed.method(), failed.exception(), failed.message(), failed.durationMs()), "a failed case");
        Check.that(failed.trace().contains("FailingTest.java:42"), "the raw trace is kept for trimming");
        Check.equal(List.of(), SurefireReports.read(dir.resolve("missing"), start), "a missing directory has no suites");
        Check.done("SurefireReportsTestMain");
    }
}
