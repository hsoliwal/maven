/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The test results of a Visual Studio TRX file, as {@code dotnet test --logger trx} writes it (VSTest with xUnit,
 * NUnit or MSTest). Each top-level {@code UnitTestResult} is one test case; its class comes from the
 * {@code TestMethod} of the {@code UnitTest} it names. Results nested under {@code InnerResults} (MSTest data rows)
 * are not separate cases. A document type declaration is refused, so no entity or external file is ever read. The
 * cases are {@link TestSuiteReport} cases: the message is the error message followed by the stack trace.
 */
final class TrxReport {
    static final String NAMESPACE = "http://microsoft.com/schemas/VisualStudio/TeamTest/2010";
    static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final Pattern DURATION = Pattern.compile("(?:(\\d+)\\.)?(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");

    private TrxReport() { }

    /** The cases of one TRX file, in file order. */
    static List<TestSuiteReport.Case> read(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) throw new IOException("TRX file is over 64 MiB: " + file);
        Document document = parse(file, "TRX");
        Element run = document.getDocumentElement();
        if (!NAMESPACE.equals(run.getNamespaceURI()) || !"TestRun".equals(run.getLocalName())) {
            throw new IOException("not a TRX TestRun document: " + file);
        }
        Map<String, String> classes = classes(run);
        List<TestSuiteReport.Case> cases = new ArrayList<>();
        for (Element result : children(child(run, "Results"), "UnitTestResult")) cases.add(toCase(result, classes));
        return List.copyOf(cases);
    }

    /** A secure DOM parse: namespaces on, no document type declaration, no entity, no XInclude. */
    static Document parse(Path file, String kind) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(file.toFile());
        } catch (ParserConfigurationException | SAXException failure) {
            throw new IOException("not a readable " + kind + " file: " + file + ": " + failure.getMessage(), failure);
        }
    }

    /** Test id to the class name of its test method. */
    private static Map<String, String> classes(Element run) {
        Map<String, String> classes = new HashMap<>();
        for (Element test : children(child(run, "TestDefinitions"), "UnitTest")) {
            Element method = child(test, "TestMethod");
            if (method != null && !method.getAttribute("className").isEmpty()) {
                classes.put(test.getAttribute("id"), method.getAttribute("className"));
            }
        }
        return classes;
    }

    private static TestSuiteReport.Case toCase(Element result, Map<String, String> classes) {
        String className = classes.getOrDefault(result.getAttribute("testId"), "dotnet");
        String name = result.getAttribute("testName");
        if (name.startsWith(className + ".")) name = name.substring(className.length() + 1);
        Element output = child(result, "Output");
        Element error = child(output, "ErrorInfo");
        String message = (text(child(error, "Message")) + "\n" + text(child(error, "StackTrace"))).strip();
        return new TestSuiteReport.Case(className, name, seconds(result.getAttribute("duration")),
                status(result.getAttribute("outcome")), message, text(child(output, "StdOut")));
    }

    /**
     * VSTest outcomes: Passed, PassedButRunAborted, Completed and Warning pass; Failed fails; Error, Timeout, Aborted
     * and NotRunnable (the test could not run) are errors; NotExecuted, Inconclusive and the others are skipped.
     */
    static TestSuiteReport.Status status(String outcome) {
        return switch (outcome) {
            case "Passed", "PassedButRunAborted", "Completed", "Warning" -> TestSuiteReport.Status.PASSED;
            case "Failed" -> TestSuiteReport.Status.FAILED;
            case "Error", "Timeout", "Aborted", "NotRunnable" -> TestSuiteReport.Status.ERRORED;
            default -> TestSuiteReport.Status.SKIPPED;
        };
    }

    /** A TimeSpan such as {@code 00:00:01.2345678} or {@code 1.02:03:04}, in seconds; 0 when absent. */
    static double seconds(String duration) {
        Matcher time = DURATION.matcher(duration);
        if (!time.matches()) return 0;
        double days = time.group(1) == null ? 0 : Double.parseDouble(time.group(1));
        return ((days * 24 + Double.parseDouble(time.group(2))) * 60 + Double.parseDouble(time.group(3))) * 60
                + Double.parseDouble(time.group(4));
    }

    private static Element child(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent == null ? null : parent.getChildNodes();
        for (int i = 0; nodes != null && i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && NAMESPACE.equals(element.getNamespaceURI())
                    && name.equals(element.getLocalName())) found.add(element);
        }
        return found;
    }

    private static String text(Element element) {
        return element == null ? "" : element.getTextContent();
    }
}
