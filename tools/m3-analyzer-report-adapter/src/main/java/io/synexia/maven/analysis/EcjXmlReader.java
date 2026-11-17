/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.analysis;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import io.synexia.maven.analysis.AnalyzerXmlNormalizer.Finding;
import io.synexia.maven.analysis.AnalyzerXmlNormalizer.Severity;
import io.synexia.maven.analysis.AnalyzerXmlNormalizer.Status;

/**
 * Reads the XML log ECJ writes with {@code -log <file>.xml} (root {@code compiler}). Each
 * {@code sources/source/problems/problem} is one finding of that source: rule {@code ecj.<id>} (ECJ's problem
 * name, such as {@code TypeMismatch}), its ERROR/WARNING/INFO severity, its line and its message;
 * {@code extra_problems/problem} findings are project-wide. ECJ's log has no column (its context line is trimmed),
 * so the column is 0. An {@code exception} element (ECJ failed) forbids COMPLETE, as PMD processing errors do.
 * The log's own DOCTYPE, which names ECJ's public DTD without an internal subset, is accepted and never loaded;
 * any other DOCTYPE, entity or processing instruction is refused. Design follows plexus-compiler-eclipse's
 * EcjResponseParser (MIT); no code is copied.
 */
final class EcjXmlReader {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_EVENTS = 2_000_000;
    private static final String DOCTYPE = "<!DOCTYPE compiler PUBLIC \"-//Eclipse.org//DTD Eclipse JDT ";

    private final Path root;
    private final Status status;
    private final List<Finding> findings = new ArrayList<>();
    private final Deque<String> open = new ArrayDeque<>();
    private boolean rootSeen;
    private String source;
    private Problem problem;

    /** A problem whose message element has not been read yet. */
    private record Problem(String rule, Severity severity, String path, int line) { }

    private EcjXmlReader(Path root, Status status) {
        this.root = root;
        this.status = status;
    }

    static List<Finding> read(Path root, Status status, byte[] xml) throws IOException {
        EcjXmlReader reader = new EcjXmlReader(root, status);
        try (ByteArrayInputStream in = new ByteArrayInputStream(xml)) {
            XMLStreamReader stream = AnalyzerXmlNormalizer.secureXmlFactory().createXMLStreamReader(in, "UTF-8");
            try {
                reader.consume(stream);
            } finally {
                stream.close();
            }
        } catch (XMLStreamException malformed) {
            throw new IOException("malformed ECJ XML log", malformed);
        }
        return reader.findings;
    }

    private void consume(XMLStreamReader stream) throws IOException, XMLStreamException {
        for (int events = 1; stream.hasNext(); events++) {
            if (events > MAX_EVENTS) throw new IOException("ECJ XML event budget exceeded");
            int event = stream.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                start(stream);
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                end();
            } else if (event == XMLStreamConstants.DTD) {
                doctype(stream.getText());
            } else if (event == XMLStreamConstants.ENTITY_REFERENCE
                    || event == XMLStreamConstants.PROCESSING_INSTRUCTION) {
                throw new IOException("ECJ XML entity or processing instruction refused");
            }
        }
        if (!rootSeen) throw new IOException("expected an ECJ compiler log root");
    }

    private void start(XMLStreamReader stream) throws IOException {
        String name = stream.getLocalName();
        String parent = open.peek();
        check(stream, name, parent);
        open.push(name);
        if ("source".equals(name) && "sources".equals(parent)) {
            source = AnalyzerXmlNormalizer.relative(root, required(stream, "path"));
        } else if ("problem".equals(name) && ("problems".equals(parent) || "extra_problems".equals(parent))) {
            problem = problem(stream, "problems".equals(parent) ? source : "");
        } else if ("message".equals(name) && "problem".equals(parent) && problem != null) {
            finish(required(stream, "value"));
        } else if ("exception".equals(name)) {
            exception(stream);
        }
    }

    private void end() throws IOException {
        String closed = open.pop();
        if ("problem".equals(closed) && problem != null) throw new IOException("ECJ problem without a message");
        if ("source".equals(closed)) source = null;
    }

    private void check(XMLStreamReader stream, String name, String parent) throws IOException {
        String namespace = stream.getNamespaceURI();
        if (open.size() >= MAX_DEPTH || stream.getAttributeCount() > 32
                || (namespace != null && !namespace.isEmpty())) {
            throw new IOException("unsupported ECJ XML depth, namespace or attributes");
        }
        if (parent == null && (rootSeen || !"compiler".equals(name))) {
            throw new IOException("expected one ECJ compiler log root");
        }
        rootSeen = true;
    }

    private Problem problem(XMLStreamReader stream, String path) throws IOException {
        if (path == null) throw new IOException("ECJ problem outside a source");
        String id = required(stream, "id");
        if (!id.matches("[A-Za-z][A-Za-z0-9_]{0,127}")) throw new IOException("invalid ECJ problem id: " + id);
        int line = path.isEmpty() ? 0 : Math.max(0, number(stream.getAttributeValue(null, "line")));
        return new Problem("ecj." + id, severity(required(stream, "severity")), path, line);
    }

    private void finish(String message) throws IOException {
        add(problem.rule(), problem.severity(), problem.path(), problem.line(), message);
        problem = null;
    }

    private void exception(XMLStreamReader stream) throws IOException {
        if (status == Status.COMPLETE) throw new IOException("ECJ reported an exception; its log is not COMPLETE");
        String message = stream.getAttributeValue(null, "message");
        add("ecj.exception", Severity.ERROR, "", 0, message == null || message.isBlank() ? "ECJ failed" : message);
    }

    private void add(String rule, Severity severity, String path, int line, String message) throws IOException {
        if (findings.size() >= AnalyzerXmlNormalizer.MAX_FINDINGS) {
            throw new IOException("original analyzer report exceeds finding limit");
        }
        try {
            findings.add(new Finding(rule, severity, path, line, 0, AnalyzerXmlNormalizer.normalizedMessage(message)));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid ECJ finding", invalid);
        }
    }

    /** ECJ's own DOCTYPE only: its public identifier, no internal subset; the DTD itself is never read. */
    private static void doctype(String text) throws IOException {
        String declaration = text == null ? "" : text.strip();
        if (!declaration.startsWith(DOCTYPE) || declaration.indexOf('[') >= 0) {
            throw new IOException("only ECJ's own DOCTYPE is accepted in an ECJ log");
        }
    }

    static Severity severity(String value) throws IOException {
        return switch (value) {
            case "ERROR" -> Severity.ERROR;
            case "WARNING" -> Severity.WARNING;
            case "INFO" -> Severity.INFO;
            default -> throw new IOException("unknown ECJ severity: " + value);
        };
    }

    private static int number(String value) throws IOException {
        if (value == null || !value.matches("-?[0-9]{1,9}")) throw new IOException("invalid ECJ line: " + value);
        return Integer.parseInt(value);
    }

    private static String required(XMLStreamReader stream, String attribute) throws IOException {
        String value = stream.getAttributeValue(null, attribute);
        if (value == null || value.isBlank()) throw new IOException("ECJ XML log is missing " + attribute);
        return value;
    }
}
