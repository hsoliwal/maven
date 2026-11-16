/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.analysis;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import io.synexia.maven.analysis.AnalyzerXmlNormalizer.Finding;
import io.synexia.maven.analysis.AnalyzerXmlNormalizer.Severity;
import io.synexia.maven.analysis.AnalyzerXmlNormalizer.Status;

/**
 * Reads the original SpotBugs BugCollection XML, not the alternative xdoc
 * report. Preserves original bug types and sourcepath/line annotations; no
 * source tree search, source-root guess, bytecode parsing, filter replay
 * or independent assertion that the original SpotBugs run completed.
 */
final class SpotBugsXmlReader {
    private static final int MAX_DEPTH = 24;
    private static final int MAX_EVENTS = 1_000_000;
    private static final int MAX_MESSAGE = 4_096;
    private static final int MAX_ATTRIBUTES = 32;

    private SpotBugsXmlReader() {}

    static List<Finding> read(Path root, Status status, byte[] xml) throws IOException {
        List<Finding> findings = new ArrayList<>();
        try (ByteArrayInputStream in = new ByteArrayInputStream(xml)) {
            XMLStreamReader reader = AnalyzerXmlNormalizer.secureXmlFactory()
                    .createXMLStreamReader(in, "UTF-8");
            try {
                int depth = 0;
                int events = 0;
                boolean rootSeen = false;
                boolean insideErrors = false;
                boolean originalErrors = false;
                Bug bug = null;
                String capture = null;
                StringBuilder message = new StringBuilder();

                while (reader.hasNext()) {
                    if (++events > MAX_EVENTS) {
                        throw new IOException("SpotBugs XML event budget exceeded");
                    }
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD
                            || event == XMLStreamConstants.ENTITY_REFERENCE
                            || event == XMLStreamConstants.PROCESSING_INSTRUCTION) {
                        throw new IOException("unsafe SpotBugs XML document declaration or entity");
                    }
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        depth++;
                        if (depth > MAX_DEPTH || reader.getAttributeCount() > MAX_ATTRIBUTES
                                || (reader.getNamespaceURI() != null && !reader.getNamespaceURI().isEmpty())) {
                            throw new IOException("unsupported SpotBugs XML depth, namespace or attributes");
                        }
                        String name = reader.getLocalName();
                        if (depth == 1) {
                            if (rootSeen || !"BugCollection".equals(name)) {
                                throw new IOException("expected original SpotBugs BugCollection root");
                            }
                            rootSeen = true;
                        } else if (depth == 2) {
                            if ("BugInstance".equals(name)) {
                                bug = new Bug(required(reader, "type"), priority(reader));
                            } else if ("Errors".equals(name)) {
                                insideErrors = true;
                                originalErrors |= nonzero(reader, "errors") || nonzero(reader, "missingClasses");
                            } else if (!metadata(name)) {
                                throw new IOException("unsupported SpotBugs top-level section: " + name);
                            }
                        } else {
                            if ("BugInstance".equals(name)) {
                                throw new IOException("nested SpotBugs bugs are not supported");
                            }
                            if (insideErrors && ("Error".equals(name) || "MissingClass".equals(name))) {
                                originalErrors = true;
                            }
                            if (bug != null) {
                                if ("SourceLine".equals(name)) {
                                    bug.source(reader, root, depth);
                                } else if (depth == 3 && ("ShortMessage".equals(name)
                                        || "LongMessage".equals(name))) {
                                    capture = name;
                                    message.setLength(0);
                                }
                            }
                        }
                    } else if (event == XMLStreamConstants.CHARACTERS
                            || event == XMLStreamConstants.CDATA) {
                        if (capture != null && depth == 3) {
                            String chars = reader.getText();
                            if (message.length() > MAX_MESSAGE - chars.length()) {
                                throw new IOException("SpotBugs original message budget exceeded");
                            }
                            message.append(chars);
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if (depth == 3 && capture != null && capture.equals(reader.getLocalName())) {
                            bug.message(capture, message.toString());
                            capture = null;
                        }
                        if (depth == 2 && "BugInstance".equals(reader.getLocalName())) {
                            if (bug == null) {
                                throw new IOException("SpotBugs bug closure without a finding");
                            }
                            add(findings, bug.complete());
                            bug = null;
                        } else if (depth == 2 && "Errors".equals(reader.getLocalName())) {
                            insideErrors = false;
                        }
                        if (depth < 1) {
                            throw new IOException("invalid SpotBugs XML structure");
                        }
                        depth--;
                    }
                }
                if (!rootSeen || depth != 0 || bug != null || capture != null) {
                    throw new IOException("incomplete original SpotBugs XML");
                }
                if (originalErrors) {
                    if (status == Status.COMPLETE) {
                        throw new IOException("SpotBugs analysis errors forbid COMPLETE status");
                    }
                    add(findings, new Finding(
                            "SpotBugs.AnalysisError", Severity.ERROR, "", 0, 0,
                            "Original SpotBugs reports missing classes or analysis errors"));
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException malformed) {
            throw new IOException("malformed or unsafe SpotBugs XML", malformed);
        }
        return findings;
    }

    private static boolean metadata(String name) {
        return switch (name) {
            case "Project", "BugPattern", "BugCode", "BugCategory", "FindBugsSummary",
                    "History", "UserPreferences", "ClassFeatures", "Profiler",
                    "ProjectStats", "AppVersion", "AnalysisFeatureSetting", "Plugin" -> true;
            default -> false;
        };
    }

    private static String required(XMLStreamReader reader, String name) throws IOException {
        String value = reader.getAttributeValue(null, name);
        if (value == null || value.isBlank() || value.length() > 256
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("missing or unsafe SpotBugs " + name);
        }
        return value;
    }

    private static int priority(XMLStreamReader reader) throws IOException {
        String value = required(reader, "priority");
        return switch (value) {
            case "1", "2", "3", "4" -> Integer.parseInt(value);
            default -> throw new IOException("invalid original SpotBugs priority");
        };
    }

    private static boolean nonzero(XMLStreamReader reader, String name) throws IOException {
        String value = reader.getAttributeValue(null, name);
        if (value == null) {
            return false;
        }
        if (value.isEmpty() || value.length() > 10
                || (value.length() > 1 && value.charAt(0) == '0')) {
            throw new IOException("invalid SpotBugs error count");
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                throw new IOException("invalid SpotBugs error count");
            }
        }
        try {
            return Integer.parseInt(value) != 0;
        } catch (NumberFormatException overflow) {
            throw new IOException("SpotBugs error count overflow", overflow);
        }
    }

    private static int start(XMLStreamReader reader) throws IOException {
        String value = reader.getAttributeValue(null, "start");
        if (value == null || "-1".equals(value)) {
            return 0; // Original source location is unknown.
        }
        if (value.isEmpty() || value.length() > 10
                || (value.length() > 1 && value.charAt(0) == '0')) {
            throw new IOException("invalid SpotBugs source position");
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                throw new IOException("invalid SpotBugs source position");
            }
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException overflow) {
            throw new IOException("SpotBugs source position overflow", overflow);
        }
    }

    private static void add(List<Finding> findings, Finding finding) throws IOException {
        if (findings.size() >= AnalyzerXmlNormalizer.MAX_FINDINGS) {
            throw new IOException("SpotBugs finding budget exceeded");
        }
        findings.add(finding);
    }

    /** One mutable atom per original BugInstance; retained only until its XML end tag. */
    private static final class Bug {
        private final String type;
        private final Severity severity;
        private String path = "";
        private int line;
        private int sourceScore = -1;
        private String shortMessage;
        private String longMessage;

        Bug(String type, int priority) {
            this.type = type;
            this.severity = priority == 1 ? Severity.ERROR
                    : priority == 2 ? Severity.WARNING : Severity.INFO;
        }

        void source(XMLStreamReader reader, Path root, int depth) throws IOException {
            String sourcepath = reader.getAttributeValue(null, "sourcepath");
            if (sourcepath == null || sourcepath.isBlank()) {
                return; // Never fabricate a path from a Java classname.
            }
            String candidate = AnalyzerXmlNormalizer.relative(root, sourcepath);
            int firstLine = start(reader);
            String primary = reader.getAttributeValue(null, "primary");
            if (primary != null && !primary.equals("true") && !primary.equals("false")) {
                throw new IOException("invalid SpotBugs primary annotation");
            }
            int score = ("true".equals(primary) ? 100 : 0) + (depth == 3 ? 10 : 0)
                    + (firstLine > 0 ? 1 : 0);
            if (score > sourceScore) {
                path = candidate;
                line = firstLine;
                sourceScore = score;
            }
        }

        void message(String kind, String raw) {
            String normalized = AnalyzerXmlNormalizer.normalizedMessage(raw);
            if (!normalized.isEmpty()) {
                if ("LongMessage".equals(kind)) {
                    longMessage = normalized;
                } else {
                    shortMessage = normalized;
                }
            }
        }

        Finding complete() throws IOException {
            String message = longMessage != null ? longMessage
                    : shortMessage != null ? shortMessage : "Original SpotBugs bug type " + type;
            // A type/priority is always retained even without a known SourceLine.
            try {
                return new Finding(type, severity, path, line, 0, message);
            } catch (IllegalArgumentException malformed) {
                throw new IOException("invalid original SpotBugs bug annotation", malformed);
            }
        }
    }
}
