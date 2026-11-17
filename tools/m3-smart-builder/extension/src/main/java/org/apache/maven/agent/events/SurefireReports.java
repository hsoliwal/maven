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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Surefire and Failsafe {@code TEST-*.xml} reports of one test mojo run, read with a DOM parser that refuses DTDs and
 * external entities. Only reports modified since the mojo started are read, so stale reports never count. The
 * report layout follows MavenSkills/mcp-server {@code SurefireReportParser} (Apache-2.0, commit 32ddfdb7).
 */
final class SurefireReports {
    /** Reports larger than this are skipped. */
    static final long MAX_REPORT_BYTES = 32L * 1024 * 1024;
    /** File time granularity allowance when comparing with the mojo start. */
    static final long CLOCK_SLACK_MILLIS = 1000;

    /** One test case: outcome passed, failed (assertion), error (exception) or skipped. */
    record TestCase(String className, String method, String outcome, long durationMs, String exception, String message,
            String trace) {
        boolean failed() {
            return "failed".equals(outcome) || "error".equals(outcome);
        }
    }

    /** One report: a test class with its counts and cases. */
    record Suite(String className, int tests, int failures, int errors, int skipped, long durationMs,
            List<TestCase> cases) {}

    private SurefireReports() {}

    /** The suites of {@code directory} written at or after {@code notBeforeMillis}, by file name. */
    static List<Suite> read(Path directory, long notBeforeMillis) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> report(file, notBeforeMillis)).sorted()
                    .map(SurefireReports::suite).flatMap(Optional::stream).collect(Collectors.toList());
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    private static boolean report(Path file, long notBeforeMillis) {
        String name = file.getFileName().toString();
        try {
            return name.startsWith("TEST-") && name.endsWith(".xml") && Files.isRegularFile(file)
                    && Files.size(file) <= MAX_REPORT_BYTES
                    && Files.getLastModifiedTime(file).toMillis() >= notBeforeMillis - CLOCK_SLACK_MILLIS;
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** One report file; empty when it is not a readable test suite. */
    static Optional<Suite> suite(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Element suite = builder().parse(in).getDocumentElement();
            if (!"testsuite".equals(suite.getTagName())) {
                return Optional.empty();
            }
            List<TestCase> cases = elements(suite.getElementsByTagName("testcase")).stream()
                    .map(SurefireReports::testCase).collect(Collectors.toList());
            return Optional.of(new Suite(suite.getAttribute("name"), number(suite, "tests"), number(suite, "failures"),
                    number(suite, "errors"), number(suite, "skipped"), millis(suite), cases));
        } catch (IOException | SAXException | ParserConfigurationException unreadable) {
            return Optional.empty();
        }
    }

    private static TestCase testCase(Element test) {
        Optional<Element> failure = first(test, "failure");
        Optional<Element> error = first(test, "error");
        Optional<Element> problem = failure.or(() -> error);
        String outcome = failure.isPresent() ? "failed" : error.isPresent() ? "error"
                : first(test, "skipped").isPresent() ? "skipped" : "passed";
        return new TestCase(test.getAttribute("classname"), test.getAttribute("name"), outcome, millis(test),
                problem.map(p -> p.getAttribute("type")).orElse(""), problem.map(p -> p.getAttribute("message")).orElse(""),
                problem.map(p -> p.getTextContent().strip()).orElse(""));
    }

    private static Optional<Element> first(Element parent, String tag) {
        return elements(parent.getChildNodes()).stream().filter(e -> tag.equals(e.getTagName())).findFirst();
    }

    private static List<Element> elements(NodeList nodes) {
        return IntStream.range(0, nodes.getLength()).mapToObj(nodes::item).filter(Element.class::isInstance)
                .map(Element.class::cast).collect(Collectors.toList());
    }

    private static int number(Element element, String attribute) {
        try {
            return Integer.parseInt(element.getAttribute(attribute).trim());
        } catch (NumberFormatException missing) {
            return 0;
        }
    }

    /** The {@code time} attribute (seconds, possibly with grouping commas) in milliseconds. */
    private static long millis(Element element) {
        try {
            return Math.round(Double.parseDouble(element.getAttribute("time").replace(",", "")) * 1000);
        } catch (NumberFormatException missing) {
            return 0;
        }
    }

    /** The JDK's own parser (never one of a plugin realm) for untrusted XML: no DTD, no external entities. */
    static DocumentBuilder builder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }
}
