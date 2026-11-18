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
package org.apache.maven.impl;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import org.apache.maven.api.ProtoSession;
import org.apache.maven.api.services.ResourceFilterRequest;
import org.apache.maven.impl.model.DefaultInterpolator;
import org.apache.maven.shared.filtering.FilteringUtils;
import org.apache.maven.shared.filtering.InterpolatorFilterReaderLineEnding;
import org.apache.maven.shared.filtering.MultiDelimiterInterpolatorFilterReaderLineEnding;
import org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.codehaus.plexus.interpolation.SimpleRecursionInterceptor;
import org.codehaus.plexus.interpolation.StringSearchInterpolator;
import org.codehaus.plexus.interpolation.multi.DelimiterSpecification;
import org.codehaus.plexus.interpolation.multi.MultiDelimiterStringSearchInterpolator;
import org.codehaus.plexus.util.InterpolationFilterReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the same texts through the {@link DefaultResourceFilter} and through the Maven 3 readers it replaces:
 * maven-filtering 3.5.0 {@code MultiDelimiterInterpolatorFilterReaderLineEnding} wired as its
 * {@code BaseFilter.Wrapper} does, maven-filtering {@code InterpolatorFilterReaderLineEnding}, and plexus-utils
 * {@code InterpolationFilterReader}. Every case must give the same text, except the cases listed in
 * {@link #KNOWN_DIFFERENCES}, which must still differ (so the list stays true). The whole table is written to
 * {@code target/resource-filter-parity/READER_PARITY.tsv}.
 */
class ResourceFilterParityTest {

    static final Map<String, String> VALUES = values();

    /** Configuration of one comparison: delimiters, escape string, multi-line, Windows path escaping. */
    record Config(String id, List<String> delimiters, String escape, boolean multiLine, boolean windowsPaths) {}

    static final List<Config> CONFIGS = List.of(
            new Config("default", ResourceFilterRequest.DEFAULT_DELIMITERS, "\\", false, true),
            new Config("multiline", ResourceFilterRequest.DEFAULT_DELIMITERS, null, true, false),
            new Config("dollar-only", List.of("${*}"), "\\", false, false),
            new Config("custom", List.of("#{*}", "<<*>>", "@"), "##", false, true));

    /** Inputs covering delimiters, escapes and line endings. */
    static final Map<String, String> TEXTS = texts();

    private static final String INTERPOLATOR_UNESCAPES = "the Interpolator unescapes \\{, \\} and $__ in values";

    /** (text id, comparator/config) pairs whose outputs differ on purpose; see the site page for the reasons. */
    static final Map<String, String> KNOWN_DIFFERENCES = Map.ofEntries(
            Map.entry("escape-at-eof|multi:default", "maven-filtering appends U+FFFF after a trailing escape"),
            Map.entry("escape-at-eof|multi:dollar-only", "maven-filtering appends U+FFFF after a trailing escape"),
            Map.entry("value-with-at|multi:default", "custom delimiters are not expanded inside property values"),
            Map.entry("value-with-at|multi:multiline", "custom delimiters are not expanded inside property values"),
            Map.entry(
                    "custom-hash|multi:custom",
                    "maven-filtering misreads a begin token that starts with the"
                            + " first character of a multi-character escape string"),
            Map.entry(
                    "hash-wraps-angle|multi:custom",
                    "maven-filtering misreads a begin token that starts with the"
                            + " first character of a multi-character escape string"),
            Map.entry(
                    "at-around-escaped-dollar|multi:default",
                    "inside an unresolved expression plexus-interpolation removes the character before the escape"
                            + " string, not the escape string"),
            Map.entry("operator-default|multi:default", "the Interpolator resolves ${a:-x}; plexus looks up 'a:-x'"),
            Map.entry("operator-default|multi:multiline", "the Interpolator resolves ${a:-x}; plexus looks up 'a:-x'"),
            Map.entry(
                    "operator-default|multi:dollar-only", "the Interpolator resolves ${a:-x}; plexus looks up 'a:-x'"),
            Map.entry("operator-default|single", "the Interpolator resolves ${a:-x}; plexus looks up 'a:-x'"),
            Map.entry("operator-default|plexus-utils", "the Interpolator resolves ${a:-x}; plexus looks up 'a:-x'"),
            Map.entry("nested-value|plexus-utils", "plexus-utils InterpolationFilterReader does not resolve values"),
            Map.entry("leading-dot|plexus-utils", "plexus-utils keeps a leading dot; plexus-interpolation drops it"),
            Map.entry("double-dollar|plexus-utils", "plexus-utils misses ${ right after a $"),
            Map.entry("long-expression|plexus-utils", "plexus-utils has no search window; maven-filtering has one"),
            Map.entry("long-expression|single", "InterpolatorFilterReaderLineEnding has no search window"),
            Map.entry("value-with-escapes|multi:default", INTERPOLATOR_UNESCAPES),
            Map.entry("value-with-escapes|multi:multiline", INTERPOLATOR_UNESCAPES),
            Map.entry("value-with-escapes|multi:dollar-only", INTERPOLATOR_UNESCAPES),
            Map.entry("value-with-escapes|single", INTERPOLATOR_UNESCAPES),
            Map.entry("value-with-escapes|plexus-utils", INTERPOLATOR_UNESCAPES));

    private static final List<String> TABLE = new ArrayList<>();

    private final DefaultResourceFilter filter =
            new DefaultResourceFilter(new DefaultInterpolator(), new DefaultPathMatcherFactory());

    private final ProtoSession session =
            ProtoSession.newBuilder().withTopDirectory(Path.of(".")).build();

    private static Map<String, String> values() {
        Map<String, String> values = new HashMap<>();
        values.put("a", "A");
        values.put("b", "B");
        values.put("empty", "");
        values.put("path", "C:\\dir\\sub");
        values.put("nested", "<${a}>");
        values.put("at", "@a@");
        values.put("dot.name", "D");
        values.put("brace", "x}y");
        values.put("unicode", "\u00e9\u20ac");
        values.put("multi", "line1\nline2");
        values.put("escapes", "a\\{b}$__c");
        values.put("k".repeat(240), "LONG");
        values.put("k".repeat(300), "TOOLONG");
        return values;
    }

    private static Map<String, String> texts() {
        Map<String, String> t = new TreeMap<>();
        delimiterTexts(t);
        escapeTexts(t);
        lineEndingTexts(t);
        valueTexts(t);
        return t;
    }

    private static void delimiterTexts(Map<String, String> t) {
        t.put("plain", "plain text without expressions");
        t.put("dollar", "${a}");
        t.put("dollar-mixed", "x${a}y${b}z");
        t.put("at", "@a@ and ${b}");
        t.put("email", "user@example.com");
        t.put("unknown", "${unknown} @unknown@");
        t.put("empty-expression", "${} @@ ${empty}|@empty@");
        t.put("unterminated", "${a");
        t.put("unterminated-at", "x @a");
        t.put("nested-expression", "${x.${a}}");
        t.put("extra-brace", "${a}}");
        t.put("double-dollar", "$${a}");
        t.put("at-chain", "@a@b@");
        t.put("custom-angle", "<<a>> <<a>x>> <<b>>>");
        t.put("at-around-dollar", "contact=dev@example.org,build=${b},ops@example.org");
        t.put("at-around-mail", "mail a@b.com v=${a} c@d.com");
        t.put("at-around-words", "@x ${a} y@");
        t.put("at-wraps-dollar", "@${a}@");
        t.put("dollar-wraps-at", "${x @a@ y}");
        t.put("at-around-escaped-dollar", "@x \\${a} y@");
        t.put("at-around-hash", "@x #{a} y@");
        t.put("angle-wraps-at", "<<x @a@ y>>");
        t.put("hash-wraps-angle", "#{x <<a>> y}");
    }

    private static void escapeTexts(Map<String, String> t) {
        t.put("escaped-dollar", "\\${a} and ${a}");
        t.put("escaped-at", "\\@a@ then @b@");
        t.put("double-escape", "\\\\${a}");
        t.put("escape-not-before-delimiter", "a\\b c\\\\d");
        t.put("escape-before-newline", "a\\\n${a}");
        t.put("escape-at-eof", "end\\");
        t.put("custom-hash", "#{a} #{unknown} ##{a}");
        t.put("custom-escape", "##@a@ #@a@ ##x");
    }

    private static void lineEndingTexts(Map<String, String> t) {
        t.put("email-lines", "mail: a@b.c\nnext @a@ line\n");
        t.put("expression-over-lf", "${a\n} @a\n@");
        t.put("crlf", "line1\r\nline2 ${a}\r\n@b@\r\n");
        t.put("cr-only", "cr\r${a}\rcr\r");
        t.put("mixed-endings", "a\r\n${a}\n\r${b}\r");
        t.put("expression-over-crlf", "${a\r\n}");
        t.put("unicode", "\u00e9 ${unicode} \u20ac");
        t.put("bom", "\ufeff${a}");
    }

    private static void valueTexts(Map<String, String> t) {
        t.put("windows-path", "${path} @path@");
        t.put("nested-value", "${nested}");
        t.put("value-with-at", "${at}");
        t.put("value-with-newline", "${multi}");
        t.put("value-with-brace", "${brace}");
        t.put("value-with-escapes", "${escapes}");
        t.put("leading-dot", "${.a}");
        t.put("dotted-name", "${dot.name}");
        t.put("operator-default", "${a:-x}");
        t.put("long-expression", "${" + "k".repeat(300) + "}");
        t.put("near-window", "${" + "k".repeat(240) + "} ${a}");
    }

    @Test
    void multiDelimiterReaderParity() throws IOException {
        List<String> failures = new ArrayList<>();
        for (Config config : CONFIGS) {
            for (Map.Entry<String, String> text : TEXTS.entrySet()) {
                String reference = multiDelimiter(text.getValue(), config);
                compare(text.getKey(), "multi:" + config.id(), reference, core(text.getValue(), config), failures);
            }
        }
        assertEquals(List.of(), failures);
    }

    /**
     * maven-filtering's own use of {@code InterpolatorFilterReaderLineEnding}
     * ({@code MavenResourcesExecution.addFilerWrapperWithEscaping}) gives the escape string to the interpolator
     * only, where it never applies to a single expression, so it compares with no escape string.
     */
    @Test
    void singleDelimiterReaderParity() throws IOException {
        Config config = new Config("single", List.of("${*}"), null, false, false);
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> text : TEXTS.entrySet()) {
            String reference = singleDelimiter(text.getValue(), "\\", config.multiLine());
            compare(text.getKey(), "single", reference, core(text.getValue(), config), failures);
        }
        assertEquals(List.of(), failures);
    }

    @Test
    void plexusUtilsReaderParity() throws IOException {
        Config config = new Config("plexus-utils", List.of("${*}"), null, true, false);
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> text : TEXTS.entrySet()) {
            String reference = plexusUtils(text.getValue());
            compare(text.getKey(), "plexus-utils", reference, core(text.getValue(), config), failures);
        }
        assertEquals(List.of(), failures);
    }

    @Test
    void delimiterSpecificationParsingParity() {
        for (String spec : List.of("${*}", "@", "@*", "<<*>>", "#{*}", "$[*]", "%")) {
            DelimiterSpecification reference = DelimiterSpecification.parse(spec);
            ResourceFilterReader.Delimiter core = ResourceFilterReader.Delimiter.parse(spec);
            assertEquals(reference.getBegin() + "|" + reference.getEnd(), core.begin() + "|" + core.end(), spec);
        }
    }

    @Test
    void windowsPathEscapingParity() {
        for (String value :
                List.of("C:\\dir\\sub", "C:\\\\dir", "a\\b", "plain", "", " ", "\\\\server\\share", "x:y\\z")) {
            assertEquals(FilteringUtils.escapeWindowsPath(value), ResourceFilterValues.escapeWindowsPath(value), value);
        }
    }

    private static void compare(
            String textId, String comparator, String reference, String core, List<String> failures) {
        String key = textId + "|" + comparator;
        boolean equal = reference.equals(core);
        boolean known = KNOWN_DIFFERENCES.containsKey(key);
        String result = equal ? "EQUAL" : known ? "KNOWN_DIFFERENCE" : "DIFFERENT";
        TABLE.add(String.join("\t", textId, comparator, result, escape(reference), escape(core)));
        if (equal == known) {
            failures.add(key
                    + (known
                            ? " is a known difference but gives the same text"
                            : ": expected <" + escape(reference) + "> but was <" + escape(core) + ">"));
        }
    }

    @AfterAll
    static void writeTable() throws IOException {
        Path dir = Path.of("target", "resource-filter-parity");
        Files.createDirectories(dir);
        List<String> rows = new ArrayList<>();
        rows.add("text\tcomparator\tresult\treference\tcore");
        rows.addAll(TABLE.stream().sorted().toList());
        Files.write(dir.resolve("READER_PARITY.tsv"), rows, StandardCharsets.UTF_8);
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\ufeff", "\\uFEFF")
                .replace("\uffff", "\\uFFFF");
    }

    private String core(String text, Config config) {
        ResourceFilterRequest request = ResourceFilterRequest.builder()
                .session(session)
                .properties(VALUES)
                .delimiters(config.delimiters())
                .escapeString(config.escape())
                .supportMultiLineFiltering(config.multiLine())
                .escapeWindowsPaths(config.windowsPaths())
                .build();
        return filter.filter(text, request);
    }

    static String multiDelimiter(String text, Config config) throws IOException {
        return drain(multiDelimiterReader(new StringReader(text), config));
    }

    /** As maven-filtering {@code BaseFilter.Wrapper.getReader} and {@code createInterpolator}. */
    static Reader multiDelimiterReader(Reader in, Config config) {
        MultiDelimiterStringSearchInterpolator interpolator = new MultiDelimiterStringSearchInterpolator();
        interpolator.setDelimiterSpecs(new LinkedHashSet<>(config.delimiters()));
        interpolator.addValueSource(new PropertiesBasedValueSource(properties()));
        interpolator.setEscapeString(config.escape());
        if (config.windowsPaths()) {
            interpolator.addPostProcessor(
                    (expression, value) -> value instanceof String s ? FilteringUtils.escapeWindowsPath(s) : value);
        }
        MultiDelimiterInterpolatorFilterReaderLineEnding reader =
                new MultiDelimiterInterpolatorFilterReaderLineEnding(in, interpolator, config.multiLine());
        reader.setRecursionInterceptor(new SimpleRecursionInterceptor());
        reader.setDelimiterSpecs(new LinkedHashSet<>(config.delimiters()));
        reader.setInterpolateWithPrefixPattern(false);
        reader.setEscapeString(config.escape());
        return reader;
    }

    /** As maven-filtering {@code MavenResourcesExecution.addFilerWrapperWithEscaping}. */
    static String singleDelimiter(String text, String escape, boolean multiLine) throws IOException {
        StringSearchInterpolator interpolator = new StringSearchInterpolator("${", "}");
        interpolator.addValueSource(new PropertiesBasedValueSource(properties()));
        interpolator.setEscapeString(escape);
        InterpolatorFilterReaderLineEnding reader =
                new InterpolatorFilterReaderLineEnding(new StringReader(text), interpolator, "${", "}", multiLine);
        reader.setInterpolateWithPrefixPattern(false);
        return drain(reader);
    }

    static String plexusUtils(String text) throws IOException {
        Map<String, Object> variables = new HashMap<>(VALUES);
        return drain(new InterpolationFilterReader(new StringReader(text), variables, "${", "}"));
    }

    static Properties properties() {
        Properties properties = new Properties();
        properties.putAll(VALUES);
        return properties;
    }

    static String drain(Reader reader) throws IOException {
        try (Reader r = reader;
                StringWriter writer = new StringWriter()) {
            char[] buffer = new char[37];
            for (int n = r.read(buffer, 0, buffer.length); n >= 0; n = r.read(buffer, 0, buffer.length)) {
                writer.write(buffer, 0, n);
            }
            return writer.toString();
        }
    }
}
