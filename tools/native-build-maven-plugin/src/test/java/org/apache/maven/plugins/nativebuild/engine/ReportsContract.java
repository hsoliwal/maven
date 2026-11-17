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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Report conversion: CTest JUnit (samples written by CTest 4.3.1 in this repository's proofs, passing and failing)
 * and Meson JUnit to surefire XML; Automake .trs results from make check; exit-status reports; XML escaping.
 */
final class ReportsContract {
    /** ctest --output-junit, CTest 4.3.1, two passing tests. */
    static final String CTEST_PASS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"(empty)\"\n"
            + "\ttests=\"2\"\n\tfailures=\"0\"\n\tdisabled=\"0\"\n\tskipped=\"0\"\n\thostname=\"\"\n\ttime=\"0\"\n"
            + "\ttimestamp=\"2026-11-17T14:32:08\"\n\t>\n"
            + "\t<testcase name=\"adds\" classname=\"adds\" time=\"0.0539002\" status=\"run\">\n\t\t<properties/>\n"
            + "\t\t<system-out>5\n</system-out>\n\t</testcase>\n"
            + "\t<testcase name=\"rejects_wrong_sum\" classname=\"rejects_wrong_sum\" time=\"0.0133929\" status=\"run\">\n"
            + "\t\t<properties/>\n\t\t<system-out>4\n</system-out>\n\t</testcase>\n</testsuite>\n";
    /** ctest --output-junit, CTest 4.3.1: passed, failed, disabled, not run (executable missing). */
    static final String CTEST_MIXED = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"(empty)\"\n"
            + "\ttests=\"4\"\n\tfailures=\"1\"\n\tdisabled=\"1\"\n\tskipped=\"1\"\n\thostname=\"\"\n\ttime=\"3\"\n"
            + "\ttimestamp=\"2026-11-17T15:41:36\"\n\t>\n"
            + "\t<testcase name=\"passes\" classname=\"passes\" time=\"1.06995\" status=\"run\">\n\t\t<properties/>\n"
            + "\t\t<system-out></system-out>\n\t</testcase>\n"
            + "\t<testcase name=\"fails\" classname=\"fails\" time=\"1.98697\" status=\"fail\">\n"
            + "\t\t<failure message=\"Failed\"/>\n\t\t<properties/>\n\t\t<system-out></system-out>\n\t</testcase>\n"
            + "\t<testcase name=\"skipped\" classname=\"skipped\" time=\"0\" status=\"disabled\">\n\t\t<properties/>\n"
            + "\t\t<system-out>Disabled</system-out>\n\t</testcase>\n"
            + "\t<testcase name=\"notrun\" classname=\"notrun\" time=\"0\" status=\"notrun\">\n"
            + "\t\t<skipped message=\"Unable to find executable\"/>\n\t\t<properties/>\n"
            + "\t\t<system-out>Unable to find executable: does-not-exist-xyz</system-out>\n\t</testcase>\n</testsuite>\n";
    private static final String MESON = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<testsuites tests=\"2\" "
            + "errors=\"0\" failures=\"1\"><testsuite name=\"fx\" tests=\"2\" errors=\"0\" failures=\"1\" skipped=\"0\" "
            + "time=\"0.1\"><testcase name=\"adds\" classname=\"fx\" time=\"0.05\"/><testcase name=\"rejects\" "
            + "classname=\"fx\" time=\"0.05\"><failure/></testcase></testsuite></testsuites>\n";

    private ReportsContract() {}

    static void run(Checks c) throws Exception {
        Path dir = c.dir("reports", Map.of("pass.xml", CTEST_PASS, "mixed.xml", CTEST_MIXED, "meson.xml", MESON));
        ctest(c, dir);
        meson(c, dir);
        automake(c);
        exitStatus(c);
        escaping(c, dir);
    }

    private static void ctest(Checks c, Path dir) throws Exception {
        TestSuiteReport pass = JUnitReport.read(dir.resolve("pass.xml"), "native.cmake.linux-x86_64");
        c.equal("ctest pass: suite renamed from (empty), status=run is passed", "Tests run: 2, Failures: 0, Errors: 0, "
                + "Skipped: 0", pass.summary());
        c.equal("ctest output kept", "5\n", pass.cases().get(0).output());
        TestSuiteReport mixed = JUnitReport.read(dir.resolve("mixed.xml"), "native.cmake.x");
        c.equal("ctest statuses", List.of("PASSED", "FAILED", "SKIPPED", "ERRORED"), mixed.cases().stream()
                .map(k -> k.status().name()).toList());
        c.equal("ctest messages", List.of("", "Failed", "", "Unable to find executable"), mixed.cases().stream()
                .map(TestSuiteReport.Case::message).toList());
        surefire(c, dir, mixed);
    }

    private static void surefire(Checks c, Path dir, TestSuiteReport mixed) throws Exception {
        Path written = SurefireXmlWriter.write(mixed, dir.resolve("out"));
        String xml = Files.readString(written);
        c.equal("surefire file name", "TEST-native.cmake.x.xml", written.getFileName().toString());
        c.check("surefire suite attributes", xml.contains("name=\"native.cmake.x\"") && xml.contains("tests=\"4\"")
                && xml.contains("errors=\"1\"") && xml.contains("skipped=\"1\"") && xml.contains("failures=\"1\""));
        c.equal("surefire XML reads back the same", mixed.cases().stream().map(k -> k.status().name()).toList(),
                JUnitReport.read(written, "native.cmake.x").cases().stream().map(k -> k.status().name()).toList());
    }

    private static void meson(Checks c, Path dir) throws Exception {
        TestSuiteReport meson = JUnitReport.read(dir.resolve("meson.xml"), "native.meson.x");
        c.equal("meson JUnit", "Tests run: 2, Failures: 1, Errors: 0, Skipped: 0", meson.summary());
        c.equal("meson class name", "native.meson.x.fx", meson.cases().get(0).className());
    }

    private static void automake(Checks c) throws Exception {
        String trs = ":global-test-result: %s\n:recheck: no\n:copy-in-global-log: no\n";
        Path build = c.dir("reports-automake", Map.of("fxadds.trs", ":test-result: PASS\n" + trs.formatted("PASS"),
                "fxadds.log", "PASS fxadds (exit status: 0)\n", "fxrejects.trs", ":test-result: XFAIL\n"
                + trs.formatted("XFAIL"), "sub/broken.trs", ":test-result: FAIL\n", "err.trs", ":test-result: ERROR\n",
                "skip.trs", ":test-result: SKIP\n", "xpass.trs", ":test-result: XPASS\n", "tap.trs",
                ":test-result: PASS 1 first\n:test-result: FAIL 2 second\n"));
        TestSuiteReport r = AutomakeTrsReport.read(build, "native.autotools.x");
        c.equal("trs names (sorted, TAP numbered)", List.of("err", "fxadds", "fxrejects (expected failure)", "skip",
                "sub/broken", "tap#1", "tap#2", "xpass"), r.cases().stream().map(TestSuiteReport.Case::name).toList());
        c.equal("trs statuses", List.of("ERRORED", "PASSED", "PASSED", "SKIPPED", "FAILED", "PASSED", "FAILED",
                "FAILED"), r.cases().stream().map(k -> k.status().name()).toList());
        c.equal("trs log is the case output", "PASS fxadds (exit status: 0)\n", r.cases().get(1).output());
    }

    private static void exitStatus(Checks c) {
        c.equal("exit status pass", "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0",
                ExitStatusReport.of("native.make.x", "make check", "", 1).summary());
        TestSuiteReport failed = ExitStatusReport.of("native.make.x", "make check", "polyglot tool failed: "
                + "native-test (exit 2)", 1);
        c.equal("exit status failure", List.of("FAILED", "polyglot tool failed: native-test (exit 2)"),
                List.of(failed.cases().get(0).status().name(), failed.cases().get(0).message()));
    }

    private static void escaping(Checks c, Path dir) throws Exception {
        TestSuiteReport odd = new TestSuiteReport("native.make.x", List.of(new TestSuiteReport.Case("native.make.x",
                "a<b>&\"c'", 0.5, TestSuiteReport.Status.FAILED, "bad \u0001 char & <tag>", "out ]]> \u0007")));
        Path written = SurefireXmlWriter.write(odd, dir.resolve("odd"));
        TestSuiteReport back = JUnitReport.read(written, "native.make.x");
        c.equal("escaped names and messages read back", List.of("a<b>&\"c'", "bad  char & <tag>", "out ]]> "),
                List.of(back.cases().get(0).name(), back.cases().get(0).message(), back.cases().get(0).output()));
    }
}
