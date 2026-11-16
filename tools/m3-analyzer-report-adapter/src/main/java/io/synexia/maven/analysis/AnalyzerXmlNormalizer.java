/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.analysis;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Java 17 adapter for original XML, SARIF 2.1.0 and Semgrep CLI JSON.
 * Does not run analyzers, resolve dependencies, inspect source files, publish
 * Maven session evidence or claim that an original analyzer executed.
 *
 * <p>Only a producer that knows the original analyzer invocation completed
 * may supply COMPLETE. Failure elements from PMD cannot become COMPLETE.
 */
public final class AnalyzerXmlNormalizer {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final int MAX_FINDINGS = 65_536;
    private static final int MAX_ROW = 16_384;
    private static final String PMD_NAMESPACE = "http://pmd.sourceforge.net/report/2.0.0";
    private static final String HEADER = "M3-STATIC-ANALYSIS-V1\n";
    private static final String COLUMNS = "rule\tseverity\tpath\tline\tcolumn\tmessage64\n";

    private static final Comparator<Finding> ORDER = Comparator.comparing(Finding::path)
            .thenComparingInt(Finding::line)
            .thenComparingInt(Finding::column)
            .thenComparing(Finding::rule)
            .thenComparing(Finding::severity)
            .thenComparing(Finding::message);

    public enum Format {
        CHECKSTYLE, PMD, SPOTBUGS, CPD, SARIF, SEMGREP
    }

    public enum Status {
        COMPLETE, PARTIAL, FAILED
    }

    public enum Severity {
        INFO, WARNING, ERROR
    }

    public record Finding(String rule, Severity severity, String path, int line, int column, String message) {
        public Finding {
            rule = text(rule, 256, "rule");
            severity = Objects.requireNonNull(severity, "severity");
            path = canonicalPath(path);
            message = text(message, 2_048, "message");
            if (line < 0 || column < 0 || (line == 0 && column != 0)
                    || (path.isEmpty() && (line != 0 || column != 0))) {
                throw new IllegalArgumentException("invalid diagnostic location");
            }
        }
    }

    /** Content-addressed report bytes; caller still owns producer provenance. */
    public record Result(byte[] bytes, String sha256, int count) {
        public Result {
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            Objects.requireNonNull(sha256, "sha256");
            if (!sha256.matches("[0-9a-f]{64}") || count < 0) {
                throw new IllegalArgumentException("invalid normalization result");
            }
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private AnalyzerXmlNormalizer() {}

    /**
     * Normalize already produced XML or JSON without opening source files.
     * Input file paths must resolve within the caller-supplied original root.
     * The input is untrusted, so XML entities, unsupported structure and
     * oversized output fail closed instead of losing original findings.
     */
    public static Result normalize(Format format, Status status, Path projectRoot, byte[] xml)
            throws IOException {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(xml, "xml");
        Path root = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || xml.length > MAX_BYTES) {
            throw new IOException("original source root or analyzer report size is invalid");
        }
        // Refuse malformed UTF-8 even if an XML provider might guess
        // another character set from an untrusted declaration.
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(xml));
        } catch (CharacterCodingException malformed) {
            throw new IOException("original analyzer report must be strict UTF-8", malformed);
        }
        List<Finding> facts = switch (format) {
            case SARIF -> SarifJsonReader.read(root, status, xml);
            case SEMGREP -> SemgrepJsonReader.read(root, status, xml);
            default -> readXml(format, status, root, xml);
        };
        facts.sort(ORDER);
        StringBuilder result = new StringBuilder(HEADER);
        result.append("status\t").append(status).append('\n').append(COLUMNS);
        Finding previous = null;
        for (Finding fact : facts) {
            if (previous != null && ORDER.compare(previous, fact) >= 0) {
                throw new IOException("original XML contains duplicate canonical findings");
            }
            previous = fact;
            appendRow(result, fact);
            if (result.length() > MAX_BYTES) {
                throw new IOException("normalized report byte budget exceeded");
            }
        }
        byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw new IOException("normalized report byte budget exceeded");
        }
        return new Result(bytes, sha256(bytes), facts.size());
    }

    private static List<Finding> readXml(Format format, Status status, Path root, byte[] xml) throws IOException {
        if (format == Format.SPOTBUGS) {
            return SpotBugsXmlReader.read(root, status, xml);
        }
        if (format == Format.CPD) {
            return CpdXmlReader.read(root, status, xml);
        }
        final XMLInputFactory factory = secureXmlFactory();
        ArrayList<Finding> findings = new ArrayList<>();
        try (ByteArrayInputStream input = new ByteArrayInputStream(xml)) {
            XMLStreamReader reader = factory.createXMLStreamReader(input, "UTF-8");
            try {
                int depth = 0;
                String file = null;
                Pending pending = null;
                String rootNamespace = null;
                boolean seenRoot = false;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD
                            || event == XMLStreamConstants.ENTITY_REFERENCE
                            || event == XMLStreamConstants.PROCESSING_INSTRUCTION) {
                        throw new IOException("XML DTD, entity or processing instruction refused");
                    }
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        depth++;
                        if (depth > 3 || reader.getAttributeCount() > 32) {
                            throw new IOException("unsupported analyzer XML depth or attributes");
                        }
                        String element = reader.getLocalName();
                        String namespace = reader.getNamespaceURI();
                        namespace = namespace == null ? "" : namespace;
                        if (depth == 1) {
                            if (seenRoot || !element.equals(format == Format.PMD ? "pmd" : "checkstyle")
                                    || (format == Format.CHECKSTYLE && !namespace.isEmpty())
                                    || (format == Format.PMD && !namespace.isEmpty()
                                            && !namespace.equals(PMD_NAMESPACE))) {
                                throw new IOException("unexpected analyzer XML root or namespace");
                            }
                            seenRoot = true;
                            rootNamespace = namespace;
                        } else if (!namespace.equals(rootNamespace)) {
                            throw new IOException("foreign analyzer XML namespace");
                        } else if (depth == 2 && "file".equals(element)) {
                            file = relative(root, requiredAttribute(reader, "name"));
                        } else if (format == Format.CHECKSTYLE && depth == 3
                                && "error".equals(element) && file != null) {
                            add(findings, checkstyleFinding(reader, file));
                        } else if (format == Format.PMD && depth == 3
                                && "violation".equals(element) && file != null) {
                            pending = pmdViolation(reader, file);
                        } else if (format == Format.PMD && depth == 2
                                && ("error".equals(element) || "configerror".equals(element))) {
                            if (status == Status.COMPLETE) {
                                throw new IOException("PMD processing/config errors forbid COMPLETE status");
                            }
                            add(findings, new Finding(
                                    element.equals("error") ? "PMD.ProcessingError" : "PMD.ConfigError",
                                    Severity.ERROR, "", 0, 0,
                                    "Original PMD reported a processing or configuration error"));
                        } else if (format == Format.PMD && depth == 2
                                && "suppressedviolation".equals(element)) {
                            // Suppression is the original PMD provider's decision.
                        } else {
                            throw new IOException("unsupported analyzer XML element or nesting: " + element);
                        }
                    } else if (event == XMLStreamConstants.CDATA
                            || event == XMLStreamConstants.CHARACTERS) {
                        if (pending != null && depth == 3) {
                            pending.append(reader.getText());
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if (format == Format.PMD && pending != null && depth == 3
                                && "violation".equals(reader.getLocalName())) {
                            add(findings, pending.complete());
                            pending = null;
                        }
                        if (depth == 2 && "file".equals(reader.getLocalName())) {
                            file = null;
                        }
                        if (depth < 1) {
                            throw new IOException("malformed analyzer XML depth");
                        }
                        depth--;
                    }
                }
                if (!seenRoot || depth != 0 || pending != null) {
                    throw new IOException("incomplete analyzer XML");
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException invalid) {
            throw new IOException("malformed or unsafe original analyzer XML", invalid);
        }
        return findings;
    }

    /** Shared untrusted XML parser configuration, never a second analyzer. */
    static XMLInputFactory secureXmlFactory() throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        try {
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setXMLResolver((publicId, systemId, base, namespace) -> {
                throw new XMLStreamException("external XML resources refused");
            });
        } catch (IllegalArgumentException unsupported) {
            throw new IOException("secure XML parser properties unavailable", unsupported);
        }
        return factory;
    }

    private static Finding checkstyleFinding(XMLStreamReader xml, String file) throws IOException {
        String rule = requiredAttribute(xml, "source");
        String message = requiredAttribute(xml, "message");
        Severity severity = switch (requiredAttribute(xml, "severity").toLowerCase(java.util.Locale.ROOT)) {
            case "error" -> Severity.ERROR;
            case "warning" -> Severity.WARNING;
            case "info" -> Severity.INFO;
            default -> throw new IOException("unknown original Checkstyle severity");
        };
        return finding(rule, severity, file, requiredNumber(xml, "line"),
                optionalNumber(xml, "column"), message);
    }

    private static Pending pmdViolation(XMLStreamReader xml, String file) throws IOException {
        String rule = requiredAttribute(xml, "rule");
        String ruleset = xml.getAttributeValue(null, "ruleset");
        if (ruleset != null && !ruleset.isBlank()) {
            rule = ruleset + "." + rule;
        }
        int priority = requiredNumber(xml, "priority");
        if (priority < 1 || priority > 5) {
            throw new IOException("invalid PMD rule priority");
        }
        Severity severity = priority <= 2 ? Severity.ERROR
                : priority == 3 ? Severity.WARNING : Severity.INFO;
        return new Pending(rule, severity, file, requiredNumber(xml, "beginline"),
                optionalNumber(xml, "begincolumn"));
    }

    private static Finding finding(
            String rule, Severity severity, String file, int line, int column, String message) throws IOException {
        try {
            return new Finding(rule, severity, file, line, column, normalizedMessage(message));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid original analyzer finding", invalid);
        }
    }

    private static final class Pending {
        private final String rule;
        private final Severity severity;
        private final String file;
        private final int line;
        private final int column;
        private final StringBuilder body = new StringBuilder();

        Pending(String rule, Severity severity, String file, int line, int column) {
            this.rule = rule;
            this.severity = severity;
            this.file = file;
            this.line = line;
            this.column = column;
        }

        void append(String value) throws IOException {
            if (body.length() > 4096 - value.length()) {
                throw new IOException("original PMD message budget exceeded");
            }
            body.append(value);
        }

        Finding complete() throws IOException {
            return finding(rule, severity, file, line, column, body.toString());
        }
    }

    static String normalizedMessage(String value) {
        return value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
    }

    private static void add(List<Finding> facts, Finding value) throws IOException {
        if (facts.size() >= MAX_FINDINGS) {
            throw new IOException("original analyzer report exceeds finding limit");
        }
        facts.add(value);
    }

    private static String requiredAttribute(XMLStreamReader reader, String attribute) throws IOException {
        String value = reader.getAttributeValue(null, attribute);
        if (value == null || value.isBlank()) {
            throw new IOException("original analyzer XML is missing " + attribute);
        }
        return value;
    }

    private static int requiredNumber(XMLStreamReader reader, String attribute) throws IOException {
        int number = optionalNumber(reader, attribute);
        if (number <= 0) {
            throw new IOException("invalid or missing positive analyzer " + attribute);
        }
        return number;
    }

    private static int optionalNumber(XMLStreamReader reader, String attribute) throws IOException {
        String value = reader.getAttributeValue(null, attribute);
        if (value == null || value.isEmpty()) {
            return 0;
        }
        if (value.length() > 10 || (value.length() > 1 && value.charAt(0) == '0')) {
            throw new IOException("noncanonical original analyzer location");
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                throw new IOException("noncanonical original analyzer location");
            }
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException malformed) {
            throw new IOException("original analyzer location overflow", malformed);
        }
    }

    static String relative(Path root, String raw) throws IOException {
        try {
            if (raw.isBlank() || raw.length() > 8192
                    || raw.chars().anyMatch(Character::isISOControl)) {
                throw new IOException("invalid original analyzer source name");
            }
            Path original = Path.of(raw);
            Path absolute = (original.isAbsolute() ? original : root.resolve(original)).normalize();
            if (!absolute.startsWith(root) || absolute.equals(root)) {
                throw new IOException("original analyzer path outside project root");
            }
            String path = root.relativize(absolute).toString().replace('\\', '/');
            return canonicalPath(path);
        } catch (IllegalArgumentException badPath) {
            throw new IOException("invalid original analyzer source path", badPath);
        }
    }

    private static String canonicalPath(String value) {
        Objects.requireNonNull(value, "path");
        if (value.isEmpty()) {
            return value; // project-wide PMD execution/configuration error
        }
        if (value.length() > 2_048
                || value.startsWith("/") || value.indexOf(':') >= 0
                || value.indexOf('\\') >= 0 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("noncanonical normalized path");
        }
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("noncanonical normalized path segment");
            }
        }
        checkUnicode(value);
        return value;
    }

    private static String text(String value, int length, String field) {
        if (value == null || value.isBlank() || value.length() > length
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid analyzer " + field);
        }
        checkUnicode(value);
        return value;
    }

    private static void checkUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("unpaired high surrogate");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("unpaired low surrogate");
            }
        }
    }

    private static void appendRow(StringBuilder output, Finding fact) throws IOException {
        int start = output.length();
        output.append(fact.rule()).append('\t').append(fact.severity())
                .append('\t').append(fact.path()).append('\t').append(fact.line())
                .append('\t').append(fact.column()).append('\t')
                .append(Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(fact.message().getBytes(StandardCharsets.UTF_8)))
                .append('\n');
        if (output.length() - start - 1 > MAX_ROW) {
            throw new IOException("normalized finding row budget exceeded");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError(unavailable);
        }
    }
}
