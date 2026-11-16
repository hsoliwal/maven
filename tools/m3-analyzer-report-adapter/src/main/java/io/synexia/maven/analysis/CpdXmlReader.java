/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.analysis;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import io.synexia.maven.analysis.AnalyzerXmlNormalizer.Finding;
import io.synexia.maven.analysis.AnalyzerXmlNormalizer.Severity;
import io.synexia.maven.analysis.AnalyzerXmlNormalizer.Status;

/**
 * Original PMD CPD xml/xmlold report reader. No CPD engine, source scanner or
 * copy of source code fragments. Every duplication occurrence becomes a
 * project-relative, source-positioned M3 v1 finding.
 */
final class CpdXmlReader {
    private static final String NAMESPACE = "https://pmd-code.org/schema/cpd-report";
    private static final String RULE = "PMD.CPD.Duplication";
    private static final int MAX_EVENTS = 1_000_000;
    private static final int MAX_ATTRIBUTES = 32;
    private static final Comparator<Location> LOCATIONS = Comparator.comparing(Location::path)
            .thenComparingInt(Location::line)
            .thenComparingInt(Location::column);

    private CpdXmlReader() {}

    static List<Finding> read(Path root, Status status, byte[] xml) throws IOException {
        List<Finding> facts = new ArrayList<>();
        try (ByteArrayInputStream input = new ByteArrayInputStream(xml)) {
            XMLStreamReader reader = AnalyzerXmlNormalizer.secureXmlFactory()
                    .createXMLStreamReader(input, "UTF-8");
            try {
                int depth = 0;
                int events = 0;
                boolean rootSeen = false;
                boolean analysisErrors = false;
                String namespace = null;
                Group group = null;
                while (reader.hasNext()) {
                    if (++events > MAX_EVENTS) {
                        throw new IOException("CPD XML event budget exceeded");
                    }
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD
                            || event == XMLStreamConstants.ENTITY_REFERENCE
                            || event == XMLStreamConstants.PROCESSING_INSTRUCTION) {
                        throw new IOException("CPD XML DTD, entity or instruction refused");
                    }
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        depth++;
                        if (depth > 3 || reader.getAttributeCount() > MAX_ATTRIBUTES) {
                            throw new IOException("unsupported CPD XML nesting or attributes");
                        }
                        String name = reader.getLocalName();
                        String uri = reader.getNamespaceURI();
                        uri = uri == null ? "" : uri;
                        if (depth == 1) {
                            if (rootSeen || !"pmd-cpd".equals(name)
                                    || !(uri.isEmpty() || NAMESPACE.equals(uri))) {
                                throw new IOException("unexpected original CPD XML root or namespace");
                            }
                            namespace = uri;
                            rootSeen = true;
                        } else if (!namespace.equals(uri)) {
                            throw new IOException("foreign CPD XML namespace");
                        } else if (depth == 2 && "duplication".equals(name)) {
                            if (group != null) {
                                throw new IOException("nested CPD duplication");
                            }
                            group = new Group(positive(reader, "lines"), positive(reader, "tokens"));
                        } else if (depth == 2 && "file".equals(name)) {
                            // Original CPD metadata can appear before duplicates.
                            AnalyzerXmlNormalizer.relative(root, required(reader, "path"));
                            optionalNonnegative(reader, "totalNumberOfTokens");
                        } else if (depth == 2 && "error".equals(name)) {
                            analysisErrors = true;
                            if (status == Status.COMPLETE) {
                                throw new IOException("original CPD processing error forbids COMPLETE");
                            }
                            AnalyzerXmlNormalizer.relative(root, required(reader, "filename"));
                            required(reader, "msg");
                        } else if (depth == 3 && group != null && "file".equals(name)) {
                            group.add(
                                    AnalyzerXmlNormalizer.relative(root, required(reader, "path")),
                                    positive(reader, "line"), optionalPositive(reader, "column"));
                            optionalNonnegative(reader, "begintoken");
                            optionalNonnegative(reader, "endtoken");
                            optionalPositive(reader, "endline");
                            optionalPositive(reader, "endcolumn");
                        } else if (depth == 3 && group != null && "codefragment".equals(name)) {
                            group.fragment();
                        } else {
                            throw new IOException("unsupported original CPD XML element: " + name);
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if (depth == 2 && "duplication".equals(reader.getLocalName())) {
                            if (group == null) {
                                throw new IOException("CPD duplication closed without a group");
                            }
                            group.append(facts);
                            group = null;
                        }
                        if (depth < 1) {
                            throw new IOException("invalid CPD XML depth");
                        }
                        depth--;
                    }
                }
                if (!rootSeen || depth != 0 || group != null) {
                    throw new IOException("incomplete original CPD XML report");
                }
                if (analysisErrors) {
                    add(facts, new Finding(
                            "PMD.CPD.AnalysisError", Severity.ERROR, "", 0, 0,
                            "Original PMD CPD reported a processing error"));
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException malformed) {
            throw new IOException("malformed or unsafe original CPD XML", malformed);
        }
        return facts;
    }

    private static String required(XMLStreamReader xml, String name) throws IOException {
        String value = xml.getAttributeValue(null, name);
        if (value == null || value.isBlank() || value.length() > 8_192
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("missing or unsafe CPD attribute: " + name);
        }
        return value;
    }

    private static int positive(XMLStreamReader xml, String name) throws IOException {
        String value = required(xml, name);
        int number = unsigned(value, name);
        if (number < 1) {
            throw new IOException("CPD " + name + " must be positive");
        }
        return number;
    }

    private static int optionalPositive(XMLStreamReader xml, String name) throws IOException {
        String value = xml.getAttributeValue(null, name);
        if (value == null) {
            return 0;
        }
        int number = unsigned(value, name);
        if (number < 1) {
            throw new IOException("CPD " + name + " must be positive");
        }
        return number;
    }

    private static void optionalNonnegative(XMLStreamReader xml, String name) throws IOException {
        String value = xml.getAttributeValue(null, name);
        if (value != null) {
            unsigned(value, name);
        }
    }

    private static int unsigned(String value, String name) throws IOException {
        if (value.isEmpty() || value.length() > 10
                || (value.length() > 1 && value.charAt(0) == '0')) {
            throw new IOException("noncanonical CPD numeric " + name);
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                throw new IOException("noncanonical CPD numeric " + name);
            }
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException overflow) {
            throw new IOException("CPD " + name + " overflow", overflow);
        }
    }

    private static void add(List<Finding> facts, Finding fact) throws IOException {
        if (facts.size() >= AnalyzerXmlNormalizer.MAX_FINDINGS) {
            throw new IOException("CPD finding budget exceeded");
        }
        facts.add(fact);
    }

    private record Location(String path, int line, int column) {}

    /** Per-duplication array-backed transient observations, no retained entry graph. */
    private static final class Group {
        private final int lines;
        private final int tokens;
        private Location[] locations = new Location[4];
        private int count;
        private boolean hasFragment;

        Group(int lines, int tokens) {
            this.lines = lines;
            this.tokens = tokens;
        }

        void add(String path, int line, int column) throws IOException {
            if (count >= AnalyzerXmlNormalizer.MAX_FINDINGS) {
                throw new IOException("CPD duplication occurrence budget exceeded");
            }
            if (count == locations.length) {
                locations = Arrays.copyOf(locations, Math.min(AnalyzerXmlNormalizer.MAX_FINDINGS, count * 2));
            }
            locations[count++] = new Location(path, line, column);
        }

        void fragment() throws IOException {
            if (hasFragment) {
                throw new IOException("duplicate CPD source code fragment");
            }
            hasFragment = true; // Source fragment is deliberately not retained or copied.
        }

        void append(List<Finding> facts) throws IOException {
            if (count < 2 || !hasFragment || count > AnalyzerXmlNormalizer.MAX_FINDINGS - facts.size()) {
                throw new IOException("incomplete or oversized original CPD duplication");
            }
            Arrays.sort(locations, 0, count, LOCATIONS);
            for (int i = 1; i < count; i++) {
                if (LOCATIONS.compare(locations[i - 1], locations[i]) == 0) {
                    throw new IOException("duplicate original CPD occurrence");
                }
            }
            String group = digest();
            for (int i = 0; i < count; i++) {
                Location location = locations[i];
                String message = "Original CPD duplicate group " + group + ": " + lines
                        + " lines, " + tokens + " tokens; occurrence " + (i + 1) + "/" + count;
                CpdXmlReader.add(facts, new Finding(RULE, Severity.WARNING,
                        location.path(), location.line(), location.column(), message));
            }
        }

        private String digest() {
            try {
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                update(sha, Integer.toString(lines));
                update(sha, Integer.toString(tokens));
                for (int i = 0; i < count; i++) {
                    Location loc = locations[i];
                    update(sha, loc.path());
                    update(sha, Integer.toString(loc.line()));
                    update(sha, Integer.toString(loc.column()));
                }
                return HexFormat.of().formatHex(sha.digest());
            } catch (NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
        }

        private static void update(MessageDigest digest, String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update((byte) (bytes.length >>> 24));
            digest.update((byte) (bytes.length >>> 16));
            digest.update((byte) (bytes.length >>> 8));
            digest.update((byte) bytes.length);
            digest.update(bytes);
        }
    }
}
