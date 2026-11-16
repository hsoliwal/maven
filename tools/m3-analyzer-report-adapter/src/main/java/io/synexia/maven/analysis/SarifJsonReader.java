/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.analysis;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;

import io.synexia.maven.analysis.AnalyzerXmlNormalizer.Finding;
import io.synexia.maven.analysis.AnalyzerXmlNormalizer.Severity;
import io.synexia.maven.analysis.AnalyzerXmlNormalizer.Status;

/**
 * Bounded original SARIF 2.1.0 CodeQL/tool finding adapter. A single
 * streaming JSON pass retains only selected result metadata and URI bases,
 * not source contents, fixes, snippets or the original JSON object graph.
 * Unsupported or ambiguous source URIs fail closed.
 */
final class SarifJsonReader {
    private static final int MAX_RUNS = 64;
    private static final int MAX_BASES = 256;
    private static final int MAX_ARTIFACTS = 65_536;
    private static final int MAX_TEXT = 8_192;

    private SarifJsonReader() {}

    static List<Finding> read(Path root, Status status, byte[] json) throws IOException {
        JsonFactory factory = new JsonFactory();
        factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        factory.setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(48).maxStringLength(MAX_TEXT).maxNumberLength(32).build());
        List<Finding> output = new ArrayList<>();
        try (JsonParser parser = factory.createParser(json)) {
            expect(parser.nextToken(), JsonToken.START_OBJECT, "SARIF object");
            boolean version = false;
            boolean runs = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = name(parser);
                parser.nextToken();
                if ("version".equals(field)) {
                    if (version || !"2.1.0".equals(string(parser, "SARIF version"))) {
                        throw new IOException("unsupported SARIF version");
                    }
                    version = true;
                } else if ("runs".equals(field)) {
                    if (runs) {
                        throw new IOException("duplicate SARIF runs");
                    }
                    runs = true;
                    expect(parser.currentToken(), JsonToken.START_ARRAY, "SARIF runs");
                    int count = 0;
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        if (++count > MAX_RUNS) {
                            throw new IOException("SARIF run count exceeded");
                        }
                        expect(parser.currentToken(), JsonToken.START_OBJECT, "SARIF run");
                        run(parser, root, status, output);
                    }
                } else {
                    parser.skipChildren();
                }
            }
            if (!version || !runs || parser.nextToken() != null) {
                throw new IOException("incomplete SARIF 2.1.0 document");
            }
        }
        return output;
    }

    private static void run(JsonParser parser, Path root, Status status, List<Finding> out) throws IOException {
        Run data = new Run();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = name(parser);
            parser.nextToken();
            switch (field) {
                case "tool" -> tool(parser, data);
                case "results" -> results(parser, data, out.size());
                case "originalUriBaseIds" -> bases(parser, data);
                case "artifacts" -> artifacts(parser, data);
                case "invocations" -> invocations(parser, data);
                default -> parser.skipChildren();
            }
        }
        if (data.tool == null || !data.resultsSeen) {
            throw new IOException("SARIF run needs original tool and results");
        }
        if (status == Status.COMPLETE && data.failed) {
            throw new IOException("failed SARIF tool invocation forbids COMPLETE");
        }
        for (Candidate candidate : data.results) {
            if ("pass".equals(candidate.kind) || "notApplicable".equals(candidate.kind)) {
                continue; // SARIF explicitly says the original rule did not produce an issue.
            }
            if (candidate.message == null || candidate.message.isBlank()
                    || candidate.rule == null || candidate.rule.isBlank()) {
                throw new IOException("SARIF result lacks rule identifier or message");
            }
            String source = "";
            int line = 0;
            int column = 0;
            ArtifactPath location = candidate.location;
            if (location != null) {
                if (location.uri == null && location.index >= 0) {
                    if (location.index >= data.artifacts.size()) {
                        throw new IOException("SARIF artifact index is outside the original run");
                    }
                    ArtifactPath indexed = data.artifacts.get(location.index);
                    location = new ArtifactPath(indexed.uri, indexed.baseId, -1,
                            location.line, location.column);
                }
                if (location.uri != null) {
                    source = resolve(root, data.bases, location.uri, location.baseId);
                    line = location.line;
                    column = location.column;
                }
            }
            Severity severity = switch (candidate.level) {
                case "error" -> Severity.ERROR;
                case "warning" -> Severity.WARNING;
                case "note", "none" -> Severity.INFO;
                default -> throw new IOException("unknown original SARIF result level");
            };
            String message = AnalyzerXmlNormalizer.normalizedMessage(candidate.message);
            try {
                add(out, new Finding(data.tool + "." + candidate.rule,
                        severity, source, line, column, message));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("unrepresentable original SARIF result", invalid);
            }
        }
        if (data.failed) {
            add(out, new Finding("SARIF.ToolExecutionError", Severity.ERROR, "", 0, 0,
                    "Original SARIF reports an unsuccessful tool invocation"));
        }
    }

    private static void tool(JsonParser parser, Run data) throws IOException {
        expect(parser.currentToken(), JsonToken.START_OBJECT, "SARIF tool");
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String key = name(parser);
            parser.nextToken();
            if ("driver".equals(key)) {
                expect(parser.currentToken(), JsonToken.START_OBJECT, "SARIF driver");
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String field = name(parser);
                    parser.nextToken();
                    if ("name".equals(field)) {
                        String name = string(parser, "SARIF original tool name");
                        if (name.isBlank() || name.length() > 128 || name.indexOf('\t') >= 0) {
                            throw new IOException("unrepresentable original SARIF tool name");
                        }
                        data.tool = name;
                    } else {
                        parser.skipChildren();
                    }
                }
            } else {
                parser.skipChildren();
            }
        }
    }

    private static void results(JsonParser parser, Run data, int existing) throws IOException {
        if (data.resultsSeen) {
            throw new IOException("duplicate SARIF result arrays");
        }
        data.resultsSeen = true;
        expect(parser.currentToken(), JsonToken.START_ARRAY, "SARIF results");
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (existing + data.results.size() >= AnalyzerXmlNormalizer.MAX_FINDINGS) {
                throw new IOException("SARIF finding budget exceeded");
            }
            expect(parser.currentToken(), JsonToken.START_OBJECT, "SARIF result");
            Candidate result = new Candidate();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = name(parser);
                parser.nextToken();
                switch (field) {
                    case "ruleId" -> result.rule = string(parser, "SARIF ruleId");
                    case "level" -> result.level = string(parser, "SARIF level");
                    case "kind" -> result.kind = string(parser, "SARIF kind");
                    case "message" -> result.message = message(parser);
                    case "locations" -> result.location = locations(parser);
                    default -> parser.skipChildren();
                }
            }
            if (!("fail".equals(result.kind) || "pass".equals(result.kind)
                    || "notApplicable".equals(result.kind) || "review".equals(result.kind)
                    || "open".equals(result.kind) || "informational".equals(result.kind))) {
                throw new IOException("unsupported SARIF result kind");
            }
            data.results.add(result);
        }
    }

    private static String message(JsonParser parser) throws IOException {
        expect(parser.currentToken(), JsonToken.START_OBJECT, "SARIF result message");
        String text = null;
        String markdown = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = name(parser);
            parser.nextToken();
            if ("text".equals(field)) {
                text = messageText(parser, "SARIF result text");
            } else if ("markdown".equals(field)) {
                markdown = messageText(parser, "SARIF result markdown");
            } else {
                parser.skipChildren();
            }
        }
        return text != null ? text : markdown;
    }

    private static ArtifactPath locations(JsonParser parser) throws IOException {
        expect(parser.currentToken(), JsonToken.START_ARRAY, "SARIF locations");
        ArtifactPath first = null;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            expect(parser.currentToken(), JsonToken.START_OBJECT, "SARIF location");
            ArtifactPath current = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = name(parser);
                parser.nextToken();
                if ("physicalLocation".equals(field)) {
                    current = physical(parser);
                } else {
                    parser.skipChildren();
                }
            }
            if (first == null && current != null) {
                first = current;
            }
        }
        return first;
    }

    private static ArtifactPath physical(JsonParser parser) throws IOException {
        expect(parser.currentToken(), JsonToken.START_OBJECT, "SARIF physicalLocation");
        ArtifactPath base = null;
        int line = 0;
        int column = 0;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = name(parser);
            parser.nextToken();
            if ("artifactLocation".equals(field)) {
                base = artifactPath(parser);
            } else if ("region".equals(field)) {
                expect(parser.currentToken(), JsonToken.START_OBJECT, "SARIF region");
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String name = name(parser);
                    parser.nextToken();
                    if ("startLine".equals(name)) {
                        line = positive(parser, "SARIF startLine");
                    } else if ("startColumn".equals(name)) {
                        column = positive(parser, "SARIF startColumn");
                    } else {
                        parser.skipChildren();
                    }
                }
            } else {
                parser.skipChildren();
            }
        }
        if (line == 0 && column != 0) {
            throw new IOException("SARIF column without line");
        }
        return base == null ? null : new ArtifactPath(base.uri, base.baseId, base.index, line, column);
    }

    private static ArtifactPath artifactPath(JsonParser parser) throws IOException {
        expect(parser.currentToken(), JsonToken.START_OBJECT, "SARIF artifactLocation");
        String uri = null;
        String baseId = null;
        int index = -1;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = name(parser);
            parser.nextToken();
            if ("uri".equals(field)) {
                uri = string(parser, "SARIF source URI");
            } else if ("uriBaseId".equals(field)) {
                baseId = string(parser, "SARIF source URI base");
            } else if ("index".equals(field)) {
                index = nonnegative(parser, "SARIF artifact index");
            } else {
                parser.skipChildren();
            }
        }
        if (uri == null && index < 0) {
            throw new IOException("SARIF artifactLocation lacks URI and index");
        }
        return new ArtifactPath(uri, baseId, index, 0, 0);
    }

    private static void artifacts(JsonParser parser, Run data) throws IOException {
        expect(parser.currentToken(), JsonToken.START_ARRAY, "SARIF artifacts");
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (data.artifacts.size() >= MAX_ARTIFACTS) {
                throw new IOException("SARIF artifacts budget exceeded");
            }
            expect(parser.currentToken(), JsonToken.START_OBJECT, "SARIF artifact");
            ArtifactPath found = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = name(parser);
                parser.nextToken();
                if ("location".equals(field)) {
                    found = artifactPath(parser);
                } else {
                    parser.skipChildren();
                }
            }
            data.artifacts.add(found == null ? new ArtifactPath(null, null, -1, 0, 0) : found);
        }
    }

    private static void bases(JsonParser parser, Run data) throws IOException {
        expect(parser.currentToken(), JsonToken.START_OBJECT, "SARIF originalUriBaseIds");
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (data.bases.size() >= MAX_BASES) {
                throw new IOException("SARIF URI base budget exceeded");
            }
            String id = name(parser);
            parser.nextToken();
            expect(parser.currentToken(), JsonToken.START_OBJECT, "SARIF URI base");
            String uri = null;
            String base = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = name(parser);
                parser.nextToken();
                if ("uri".equals(field)) {
                    uri = string(parser, "SARIF base URI");
                } else if ("uriBaseId".equals(field)) {
                    base = string(parser, "SARIF parent URI base");
                } else {
                    parser.skipChildren();
                }
            }
            if (uri == null || data.bases.putIfAbsent(id, new Base(uri, base)) != null) {
                throw new IOException("incomplete or duplicate SARIF URI base");
            }
        }
    }

    private static void invocations(JsonParser parser, Run data) throws IOException {
        expect(parser.currentToken(), JsonToken.START_ARRAY, "SARIF invocations");
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            expect(parser.currentToken(), JsonToken.START_OBJECT, "SARIF invocation");
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = name(parser);
                parser.nextToken();
                if ("executionSuccessful".equals(field)) {
                    if (parser.currentToken() == JsonToken.VALUE_FALSE) {
                        data.failed = true;
                    } else {
                        expect(parser.currentToken(), JsonToken.VALUE_TRUE, "SARIF invocation result");
                    }
                } else {
                    parser.skipChildren();
                }
            }
        }
    }

    private static String resolve(Path root, Map<String, Base> bases, String uri, String id) throws IOException {
        Path start = root;
        Set<String> visited = new HashSet<>();
        List<Base> parents = new ArrayList<>();
        while (id != null) {
            if (!visited.add(id) || visited.size() > 8) {
                throw new IOException("cyclic or excessive SARIF URI base chain");
            }
            Base base = bases.get(id);
            if (base == null) {
                throw new IOException("unknown original SARIF source URI base");
            }
            parents.add(base);
            id = base.parent;
        }
        for (int i = parents.size() - 1; i >= 0; i--) {
            start = resolvePath(start, parents.get(i).uri);
        }
        return AnalyzerXmlNormalizer.relative(root, resolvePath(start, uri).toString());
    }

    private static Path resolvePath(Path start, String raw) throws IOException {
        try {
            URI uri = new URI(raw);
            if (uri.isOpaque() || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getScheme() != null && !"file".equalsIgnoreCase(uri.getScheme()))) {
                throw new IOException("unsupported SARIF source URI");
            }
            Path path = uri.getScheme() == null ? Path.of(uri.getPath()) : Path.of(uri);
            if (path.toString().isBlank()) {
                throw new IOException("empty SARIF source URI");
            }
            return (path.isAbsolute() ? path : start.resolve(path)).normalize();
        } catch (URISyntaxException | IllegalArgumentException malformed) {
            throw new IOException("invalid SARIF source URI", malformed);
        }
    }

    private static void add(List<Finding> out, Finding fact) throws IOException {
        if (out.size() >= AnalyzerXmlNormalizer.MAX_FINDINGS) {
            throw new IOException("SARIF result budget exceeded");
        }
        out.add(fact);
    }

    private static int positive(JsonParser parser, String field) throws IOException {
        int value = nonnegative(parser, field);
        if (value < 1) {
            throw new IOException(field + " must be positive");
        }
        return value;
    }

    private static int nonnegative(JsonParser parser, String field) throws IOException {
        if (!parser.currentToken().isIntegralNumber()) {
            throw new IOException(field + " must be an integer");
        }
        int result = parser.getIntValue();
        if (result < 0) {
            throw new IOException(field + " must not be negative");
        }
        return result;
    }

    private static String messageText(JsonParser parser, String field) throws IOException {
        expect(parser.currentToken(), JsonToken.VALUE_STRING, field);
        String value = parser.getText();
        if (value.length() > MAX_TEXT) {
            throw new IOException("oversized original " + field);
        }
        return value;
    }

    private static String string(JsonParser parser, String field) throws IOException {
        expect(parser.currentToken(), JsonToken.VALUE_STRING, field);
        String result = parser.getText();
        if (result.length() > MAX_TEXT || result.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("unrepresentable original " + field);
        }
        return result;
    }

    private static String name(JsonParser parser) throws IOException {
        expect(parser.currentToken(), JsonToken.FIELD_NAME, "SARIF property");
        return parser.currentName();
    }

    private static void expect(JsonToken actual, JsonToken expected, String name) throws IOException {
        if (actual != expected) {
            throw new IOException("expected " + name);
        }
    }

    private static final class Run {
        String tool;
        boolean resultsSeen;
        boolean failed;
        final List<Candidate> results = new ArrayList<>();
        final List<ArtifactPath> artifacts = new ArrayList<>();
        final Map<String, Base> bases = new HashMap<>();
    }

    private static final class Candidate {
        String rule;
        String level = "warning"; // SARIF 2.1.0 default level when omitted.
        String kind = "fail";
        String message;
        ArtifactPath location;
    }

    private record Base(String uri, String parent) {}
    private record ArtifactPath(String uri, String baseId, int index, int line, int column) {}
}
