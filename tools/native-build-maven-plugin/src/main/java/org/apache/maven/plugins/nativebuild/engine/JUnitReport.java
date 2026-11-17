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
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Reads the JUnit XML that {@code ctest --output-junit} and {@code meson test} write. CTest's needs normalizing:
 * its suite is named "(empty)", passing cases carry {@code status="run"} and there is no errors attribute; a failure
 * is a {@code <failure>} child; {@code status="disabled"} is skipped; {@code status="notrun"} (CTest could not start
 * the test, which fails ctest) is an error although CTest writes it as {@code <skipped>}.
 */
public final class JUnitReport {
    private JUnitReport() {}

    /** All {@code <testcase>} elements of the file, under {@code suite}. */
    public static TestSuiteReport read(Path file, String suite) throws IOException {
        Document document = XmlSupport.parse(file);
        List<TestSuiteReport.Case> cases = XmlSupport.elements(document, "testcase").stream()
                .map(e -> testCase(e, suite)).toList();
        return new TestSuiteReport(suite, cases);
    }

    private static TestSuiteReport.Case testCase(Element e, String suite) {
        String className = e.getAttribute("classname");
        return new TestSuiteReport.Case(className.isEmpty() ? suite : suite + "." + className,
                e.getAttribute("name"), seconds(e.getAttribute("time")), status(e), message(e), output(e));
    }

    private static TestSuiteReport.Status status(Element e) {
        String status = e.getAttribute("status").toLowerCase(Locale.ROOT);
        if (child(e, "error") != null || "notrun".equals(status)) {
            return TestSuiteReport.Status.ERRORED;
        }
        if (child(e, "failure") != null || "fail".equals(status) || "failed".equals(status)) {
            return TestSuiteReport.Status.FAILED;
        }
        return child(e, "skipped") != null || "disabled".equals(status)
                ? TestSuiteReport.Status.SKIPPED : TestSuiteReport.Status.PASSED;
    }

    private static String message(Element e) {
        return List.of("error", "failure", "skipped").stream().map(tag -> child(e, tag)).filter(c -> c != null)
                .map(c -> c.getAttribute("message").isEmpty() ? c.getTextContent().strip() : c.getAttribute("message"))
                .findFirst().orElse("");
    }

    private static String output(Element e) {
        Element out = child(e, "system-out");
        return out == null ? "" : out.getTextContent();
    }

    private static Element child(Element e, String tag) {
        return XmlSupport.children(e).stream().filter(c -> tag.equals(c.getTagName())).findFirst().orElse(null);
    }

    private static double seconds(String value) {
        try {
            return value.isBlank() ? 0 : Math.max(0, Double.parseDouble(value.strip()));
        } catch (NumberFormatException unreadable) {
            return 0;
        }
    }
}
