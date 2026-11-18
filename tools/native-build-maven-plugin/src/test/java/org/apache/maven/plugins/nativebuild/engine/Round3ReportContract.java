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

import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.LINUX;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.context;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.options;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.path;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import org.w3c.dom.Element;

/**
 * Round-3 LOW findings: failing test names and the step tail reach Maven's error, a multi-line failure is element
 * text (not an attribute), zip times are clamped to the DOS range, a provisioned CMake is re-checked by the hashes of
 * its executables and unpacked from a verified copy, and the Make install heuristic ignores comments and refuses
 * more shell characters in the stage path.
 */
final class Round3ReportContract {
    private static final String MAKE_INSTALL = "all:\n\techo\ninstall:\n\tcp x $(DESTDIR)/usr/local/bin\n";

    private Round3ReportContract() {}

    static void run(Checks c) throws Exception {
        failureDetails(c);
        surefireText(c);
        dosRange(c);
        cmakeMarker(c);
        verifiedCopy(c);
        makeHeuristic(c);
    }

    private static void failureDetails(Checks c) {
        TestSuiteReport report = new TestSuiteReport("native.cmake.x", List.of(
                new TestSuiteReport.Case("native.cmake.x", "adds", 0.1, TestSuiteReport.Status.FAILED,
                        "expected 3\ngot 4", "adds: FAIL"),
                new TestSuiteReport.Case("native.cmake.x", "subs", 0.1, TestSuiteReport.Status.PASSED, "", "")));
        String details = TestReports.failureDetails(report, "ctest exited 8; last 2 lines:\n  TAIL-ONE\n  TAIL-TWO");
        c.check("LOW test failure: Maven's error names the failing case and its message\n" + details,
                details.contains("adds") && details.contains("expected 3") && !details.contains("subs"));
        c.check("LOW test failure: Maven's error carries the step's tail", details.contains("TAIL-TWO"));
    }

    private static void surefireText(Checks c) throws Exception {
        TestSuiteReport report = new TestSuiteReport("native.cmake.x", List.of(new TestSuiteReport.Case(
                "native.cmake.x", "adds", 0.1, TestSuiteReport.Status.FAILED, "first line\nsecond line", "")));
        Element failure = XmlSupport.elements(XmlSupport.parse(SurefireXmlWriter.render(report)), "failure").get(0);
        c.equal("LOW surefire: the message attribute holds one line", "first line", failure.getAttribute("message"));
        c.check("LOW surefire: the whole failure text is element text, line breaks kept",
                failure.getTextContent().contains("first line\nsecond line"));
    }

    private static void dosRange(Checks c) throws Exception {
        c.equal("LOW zip: a time before 1980 is clamped", Instant.parse("1980-01-01T00:00:02Z").toEpochMilli(),
                DeterministicZip.epoch("10"));
        c.equal("LOW zip: a time after 2107 is clamped", Instant.parse("2107-12-31T23:59:58Z").toEpochMilli(),
                DeterministicZip.epoch("2200-01-01T00:00:00Z"));
        Path base = c.dir("r3-dos", Map.of("stage/include/fx.h", "int fx;\n"));
        byte[] utc = zipIn(base, "UTC", "utc.zip");
        byte[] kolkata = zipIn(base, "Asia/Kolkata", "kolkata.zip");
        c.check("LOW zip: an out-of-range time gives the same bytes in every time zone", Arrays.equals(utc, kolkata));
    }

    private static byte[] zipIn(Path base, String zone, String name) throws IOException {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            DeterministicZip.write(base.resolve(name), base.resolve("stage"), List.of("include/fx.h"),
                    DeterministicZip.epoch("10"));
            return Files.readAllBytes(base.resolve(name));
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    private static void cmakeMarker(Checks c) throws Exception {
        Path folder = c.dir("r3-marker", Map.of("cmake-9/bin/cmake.exe", "MZ-cmake", "cmake-9/bin/ctest.exe",
                "MZ-ctest", "cmake-9/bin/cmake", "ELF-cmake", "cmake-9/bin/ctest", "ELF-ctest"));
        CMakeBinaries.mark(folder, "g:cmake-binaries:9", "abc");
        c.check("provisioning: an untouched folder is accepted", CMakeBinaries.unpacked(folder, "abc"));
        Files.writeString(folder.resolve("cmake-9/bin/cmake.exe"), "MZ-replaced");
        c.check("LOW provisioning: a replaced cmake executable is re-provisioned", !CMakeBinaries.unpacked(folder, "abc"));
    }

    private static void verifiedCopy(Checks c) throws Exception {
        Path base = c.dir("r3-copy", Map.of("repo/cmake.jar", "jar bytes"));
        Path copy = base.resolve("target/cmake.jar");
        c.fails("LOW provisioning: a copy whose hash differs is refused", IOException.class, () -> CMakeBinaries
                .copyVerified(base.resolve("repo/cmake.jar"), copy, "g:a:1", Optional.of("00".repeat(32))));
        c.check("LOW provisioning: the refused copy is deleted", !Files.exists(copy));
        String right = NativeDependencies.sha256(base.resolve("repo/cmake.jar"));
        c.equal("provisioning: a matching copy is used", "jar bytes", Files.readString(CMakeBinaries.copyVerified(
                base.resolve("repo/cmake.jar"), copy, "g:a:1", Optional.of(right))));
    }

    private static void makeHeuristic(Checks c) throws Exception {
        Path commented = c.dir("r3-make-comment", Map.of("Makefile",
                "# install honours $(DESTDIR)\nall:\n\techo\ninstall:\n\tcp x /usr/local/bin\n"));
        c.check("LOW make: $(DESTDIR) in a comment does not enable install", installRefusal(commented).isPresent());
        Path plain = c.dir("r3-make-plain", Map.of("Makefile", MAKE_INSTALL));
        c.check("make: $(DESTDIR) in the install rule enables install", installRefusal(plain).isEmpty());
        List.of("r3-st{a}ge", "r3-st~ge", "r3-st!ge", "r3-st[a]ge").forEach(name -> {
            try {
                Path base = c.dir(name, Map.of("Makefile", MAKE_INSTALL));
                c.check("LOW make: a stage path with " + name + " is refused", installRefusal(base).isPresent());
            } catch (IOException failure) {
                c.check("LOW make: " + name + " (" + failure + ")", false);
            }
        });
    }

    private static Optional<String> installRefusal(Path base) throws IOException {
        PlanContext ctx = context(base, BuildSystem.MAKE, "Makefile", LINUX, options().build(), Map.of(),
                path("make", "/usr/bin/make"));
        return new MakePlanner().installRefusal(ctx);
    }
}
