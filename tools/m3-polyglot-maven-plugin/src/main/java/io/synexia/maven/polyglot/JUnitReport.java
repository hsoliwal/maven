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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.IntStream;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reads the JUnit XML that {@code ctest --output-junit} writes. CTest's needs normalizing: its suite is named
 * "(empty)", passing cases carry {@code status="run"} and there is no errors attribute; a failure is a
 * {@code <failure>} child; {@code status="disabled"} is skipped although CTest writes no {@code <skipped>} child;
 * {@code status="notrun"} (CTest could not start the test, which fails ctest) is an error although CTest writes it
 * as {@code <skipped>}. Ported from the native-build plugin's {@code engine.JUnitReport}; the document is parsed as
 * TRX files are, so a document type declaration is refused.
 */
final class JUnitReport {
    private JUnitReport() {}

    /** All {@code <testcase>} elements of the file, under {@code suite}. */
    static TestSuiteReport read(Path file, String suite) throws IOException {
        if (Files.size(file) > TrxReport.MAX_BYTES) throw new IOException("JUnit XML file is over 64 MiB: " + file);
        Document document = TrxReport.parse(file, "JUnit XML");
        NodeList nodes = document.getElementsByTagName("testcase");
        List<TestSuiteReport.Case> cases = IntStream.range(0, nodes.getLength())
                .mapToObj(i -> testCase((Element) nodes.item(i), suite)).toList();
        return new TestSuiteReport(suite, cases);
    }

    private static TestSuiteReport.Case testCase(Element e, String suite) {
        String className = e.getAttribute("classname");
        return new TestSuiteReport.Case(className.isEmpty() ? suite : suite + "." + className,
                e.getAttribute("name"), seconds(e.getAttribute("time")), status(e), message(e), output(e));
    }

    private static TestSuiteReport.Status status(Element e) {
        String status = e.getAttribute("status").toLowerCase(Locale.ROOT);
        if (child(e, "error").isPresent() || "notrun".equals(status)) {
            return TestSuiteReport.Status.ERRORED;
        }
        if (child(e, "failure").isPresent() || "fail".equals(status) || "failed".equals(status)) {
            return TestSuiteReport.Status.FAILED;
        }
        return child(e, "skipped").isPresent() || "disabled".equals(status)
                ? TestSuiteReport.Status.SKIPPED : TestSuiteReport.Status.PASSED;
    }

    private static String message(Element e) {
        return List.of("error", "failure", "skipped").stream().map(tag -> child(e, tag)).flatMap(Optional::stream)
                .map(c -> c.getAttribute("message").isEmpty() ? c.getTextContent().strip() : c.getAttribute("message"))
                .findFirst().orElse("");
    }

    private static String output(Element e) {
        return child(e, "system-out").map(Element::getTextContent).orElse("");
    }

    private static Optional<Element> child(Element e, String tag) {
        NodeList nodes = e.getChildNodes();
        return IntStream.range(0, nodes.getLength()).mapToObj(nodes::item).filter(Element.class::isInstance)
                .map(Element.class::cast).filter(c -> tag.equals(c.getTagName())).findFirst();
    }

    private static double seconds(String value) {
        try {
            return value.isBlank() ? 0 : Math.max(0, Double.parseDouble(value.strip()));
        } catch (NumberFormatException unreadable) {
            return 0;
        }
    }

    /** Suite name of a results file: its name without {@code .xml} and a trailing {@code -junit}. */
    static String suite(Path file) {
        String name = file.getFileName().toString().replaceFirst("(?i)\\.xml$", "");
        return name.endsWith("-junit") ? name.substring(0, name.length() - "-junit".length()) : name;
    }

}
