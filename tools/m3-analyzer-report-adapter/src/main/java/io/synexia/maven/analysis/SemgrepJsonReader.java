/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.analysis;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import io.synexia.maven.analysis.AnalyzerXmlNormalizer.Finding;
import io.synexia.maven.analysis.AnalyzerXmlNormalizer.Severity;
import io.synexia.maven.analysis.AnalyzerXmlNormalizer.Status;

/**
 * Original Semgrep CLI --json output adapter. The upstream CLI owns rules,
 * target discovery, suppressions and scan completion; this bounded reader
 * only projects original result fields into the existing native v1 format.
 *
 * <p>Never keeps snippets, fixes, metavars, traces or entire JSON documents.
 */
final class SemgrepJsonReader {
    private static final int MAX_ERRORS = 65_536;
    private static final int MAX_RESULTS = 65_536;
    private static final int MAX_TEXT = 8_192;

    private SemgrepJsonReader() {}

    static List<Finding> read(Path root, Status status, byte[] json) throws IOException {
        JsonFactory factory = OriginalJsonStreams.factory();
        List<Finding> results = new ArrayList<>();
        try (JsonParser parser = factory.createParser(json)) {
            expect(parser.nextToken(), JsonToken.START_OBJECT, "Semgrep CLI object");
            boolean resultsSeen = false;
            boolean errorsSeen = false;
            boolean pathsSeen = false;
            boolean hasErrors = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String property = property(parser);
                parser.nextToken();
                switch (property) {
                    case "results" -> {
                        if (resultsSeen) {
                            throw new IOException("duplicate Semgrep results");
                        }
                        resultsSeen = true;
                        findings(parser, root, results);
                    }
                    case "errors" -> {
                        if (errorsSeen) {
                            throw new IOException("duplicate Semgrep errors");
                        }
                        errorsSeen = true;
                        hasErrors = errors(parser);
                    }
                    case "paths" -> {
                        if (pathsSeen) {
                            throw new IOException("duplicate Semgrep targeting inventory");
                        }
                        pathsSeen = true;
                        expect(parser.currentToken(), JsonToken.START_OBJECT, "Semgrep paths inventory");
                        parser.skipChildren();
                    }
                    default -> parser.skipChildren();
                }
            }
            if (!resultsSeen || !errorsSeen || !pathsSeen || parser.nextToken() != null) {
                throw new IOException("incomplete original Semgrep CLI output");
            }
            if (hasErrors) {
                if (status == Status.COMPLETE) {
                    throw new IOException("original Semgrep errors forbid COMPLETE status");
                }
                add(results, new Finding(
                        "Semgrep.ScanError", Severity.ERROR, "", 0, 0,
                        "Original Semgrep reports an incomplete or failed scan"));
            }
        }
        return results;
    }

    /** Atom: process original match rows independently of output order. */
    private static void findings(JsonParser parser, Path root, List<Finding> output) throws IOException {
        expect(parser.currentToken(), JsonToken.START_ARRAY, "Semgrep results");
        int visited = 0;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (++visited > MAX_RESULTS) {
                throw new IOException("Semgrep finding budget exceeded");
            }
            expect(parser.currentToken(), JsonToken.START_OBJECT, "Semgrep result");
            Candidate fact = new Candidate();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = property(parser);
                parser.nextToken();
                switch (field) {
                    case "check_id" -> fact.rule = text(parser, "Semgrep rule ID");
                    case "path" -> fact.path = text(parser, "Semgrep source path");
                    case "start" -> fact.start = position(parser, "start");
                    case "end" -> fact.end = position(parser, "end");
                    case "extra" -> extra(parser, fact);
                    default -> parser.skipChildren();
                }
            }
            if (fact.rule == null || fact.path == null || fact.start == null
                    || fact.end == null || !fact.extraSeen || fact.message == null
                    || fact.severity == null) {
                throw new IOException("incomplete original Semgrep result");
            }
            if (fact.end.line < fact.start.line
                    || fact.end.line == fact.start.line && fact.end.column < fact.start.column) {
                throw new IOException("original Semgrep end position precedes start");
            }
            String path = AnalyzerXmlNormalizer.relative(root, fact.path);
            if (!fact.ignored) {
                String message = AnalyzerXmlNormalizer.normalizedMessage(fact.message);
                try {
                    add(output, new Finding(fact.rule, fact.severity, path,
                            fact.start.line, fact.start.column, message));
                } catch (IllegalArgumentException malformed) {
                    throw new IOException("unrepresentable original Semgrep result", malformed);
                }
            }
        }
    }

    /** Atom: reject unsafe Semgrep scan-error arrays, without copying error payloads. */
    private static boolean errors(JsonParser parser) throws IOException {
        expect(parser.currentToken(), JsonToken.START_ARRAY, "Semgrep errors");
        boolean encountered = false;
        int count = 0;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (++count > MAX_ERRORS) {
                throw new IOException("Semgrep scan error budget exceeded");
            }
            expect(parser.currentToken(), JsonToken.START_OBJECT, "Semgrep scan error");
            encountered = true;
            parser.skipChildren();
        }
        return encountered;
    }

    /** Atom: extract only original message, severity and suppression metadata. */
    private static void extra(JsonParser parser, Candidate match) throws IOException {
        if (match.extraSeen) {
            throw new IOException("duplicate Semgrep extra metadata");
        }
        match.extraSeen = true;
        expect(parser.currentToken(), JsonToken.START_OBJECT, "Semgrep extra metadata");
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = property(parser);
            parser.nextToken();
            switch (field) {
                case "message" -> match.message = text(parser, "Semgrep message");
                case "severity" -> match.severity = severity(text(parser, "Semgrep severity"));
                case "is_ignored" -> {
                    if (parser.currentToken() == JsonToken.VALUE_TRUE) {
                        match.ignored = true;
                    } else {
                        expect(parser.currentToken(), JsonToken.VALUE_FALSE, "Semgrep ignored state");
                    }
                }
                default -> parser.skipChildren(); // Never copy snippets, fixes, secrets or traces.
            }
        }
    }

    /** Atom: original one-based CLI line and column, not UTF-16 LSP offsets. */
    private static Position position(JsonParser parser, String field) throws IOException {
        expect(parser.currentToken(), JsonToken.START_OBJECT, "Semgrep " + field + " position");
        int line = 0;
        int column = 0;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String key = property(parser);
            parser.nextToken();
            if ("line".equals(key)) {
                line = positive(parser, "Semgrep " + field + " line");
            } else if ("col".equals(key)) {
                column = positive(parser, "Semgrep " + field + " column");
            } else {
                parser.skipChildren();
            }
        }
        if (line == 0 || column == 0) {
            throw new IOException("Semgrep " + field + " requires positive line/column");
        }
        return new Position(line, column);
    }

    /**
     * Explicit original Semgrep-to-M3 *presentation* mapping. This does not
     * change the original rule severity, exit policy or suppression decision.
     */
    private static Severity severity(String value) throws IOException {
        return switch (value) {
            case "CRITICAL", "HIGH", "ERROR" -> Severity.ERROR;
            case "MEDIUM", "WARNING" -> Severity.WARNING;
            case "LOW", "INFO", "INVENTORY", "EXPERIMENT" -> Severity.INFO;
            default -> throw new IOException("unknown original Semgrep severity");
        };
    }

    private static String text(JsonParser parser, String field) throws IOException {
        expect(parser.currentToken(), JsonToken.VALUE_STRING, field);
        String value = parser.getText();
        if (value.isBlank() || value.length() > MAX_TEXT) {
            throw new IOException("empty or oversized original " + field);
        }
        return value;
    }

    private static int positive(JsonParser parser, String field) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            throw new IOException("invalid original " + field);
        }
        int value = parser.getIntValue();
        if (value < 1) {
            throw new IOException("nonpositive original " + field);
        }
        return value;
    }

    private static void add(List<Finding> results, Finding value) throws IOException {
        if (results.size() >= AnalyzerXmlNormalizer.MAX_FINDINGS) {
            throw new IOException("original Semgrep report finding budget exceeded");
        }
        results.add(value);
    }

    private static String property(JsonParser parser) throws IOException {
        expect(parser.currentToken(), JsonToken.FIELD_NAME, "Semgrep JSON field");
        return parser.currentName();
    }

    private static void expect(JsonToken actual, JsonToken expected, String label) throws IOException {
        if (actual != expected) {
            throw new IOException("expected " + label);
        }
    }

    private record Position(int line, int column) {}

    /** One short-lived mutable state holder per streamed result. */
    private static final class Candidate {
        String rule;
        String path;
        String message;
        Severity severity;
        Position start;
        Position end;
        boolean ignored;
        boolean extraSeen;
    }
}
