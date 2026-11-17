/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.synexia.maven.analysis.AnalyzerXmlNormalizer.Finding;
import io.synexia.maven.analysis.AnalyzerXmlNormalizer.Severity;
import io.synexia.maven.analysis.AnalyzerXmlNormalizer.Status;

/**
 * Reads javac's diagnostics as javac prints them by default (its error stream, captured as UTF-8), into the same
 * rows the ECJ log gives: {@code File.java:12: error: message}, then the source line, a caret line that gives the
 * column, and detail lines ({@code symbol:}, {@code where ...}) that join the message. The rule is
 * {@code javac.<lint key>} for a warning such as {@code [deprecation] ...}, otherwise {@code javac.error} or
 * {@code javac.warning}; {@code Note:} lines are project-wide {@code javac.note} INFO rows, and diagnostics without
 * a file ({@code warning: [options] ...}) are project-wide too. Count lines are skipped and other lines ignored.
 * When javac says it showed only the first errors or warnings, the log cannot be COMPLETE.
 */
final class JavacLogReader {
    private static final Pattern LOCATED = Pattern.compile("^(.+?\\.java):(\\d{1,9}): (error|warning): (.*)$");
    private static final Pattern GLOBAL = Pattern.compile("^(error|warning): (.*)$");
    private static final Pattern NOTE = Pattern.compile("^Note: (.*)$");
    private static final Pattern CARET = Pattern.compile("^[ \\t]*\\^[ \\t]*$");
    private static final Pattern COUNT = Pattern.compile("^\\d+ (errors?|warnings?)$");
    private static final Pattern TRUNCATED = Pattern.compile("^only showing the first \\d+ (errors|warnings), of .*$");
    private static final Pattern LINT = Pattern.compile("^\\[([a-z][a-z-]{0,63})\\] (.*)$");

    private final Path root;
    private final List<Finding> findings = new ArrayList<>();
    private Open current;
    private boolean truncated;

    /** A diagnostic still taking lines: after its header come the source line, the caret and details. */
    private static final class Open {
        private final String rule;
        private final Severity severity;
        private final String path;
        private final int line;
        private final StringBuilder message;
        private int column;
        private int lines;

        Open(String rule, Severity severity, String path, int line, String message) {
            this.rule = rule;
            this.severity = severity;
            this.path = path;
            this.line = line;
            this.message = new StringBuilder(message);
        }
    }

    private JavacLogReader(Path root) {
        this.root = root;
    }

    static List<Finding> read(Path root, Status status, byte[] log) throws IOException {
        JavacLogReader reader = new JavacLogReader(root);
        for (String line : new String(log, StandardCharsets.UTF_8).split("\r?\n", -1)) reader.accept(line);
        reader.flush();
        if (reader.truncated && status == Status.COMPLETE) {
            throw new IOException("javac showed only part of its diagnostics; the log is not COMPLETE");
        }
        return reader.findings;
    }

    private void accept(String line) throws IOException {
        Matcher located = LOCATED.matcher(line);
        Matcher global = GLOBAL.matcher(line);
        Matcher note = NOTE.matcher(line);
        if (located.matches()) {
            flush();
            current = open(located.group(3), AnalyzerXmlNormalizer.relative(root, located.group(1)),
                    Integer.parseInt(located.group(2)), located.group(4));
        } else if (global.matches()) {
            flush();
            current = open(global.group(1), "", 0, global.group(2));
        } else if (note.matches() || COUNT.matcher(line).matches() || TRUNCATED.matcher(line).matches()) {
            flush();
            truncated |= TRUNCATED.matcher(line).matches();
            if (note.matches()) add(new Open("javac.note", Severity.INFO, "", 0, note.group(1)));
        } else if (current != null) {
            continued(line);
        }
    }

    /** The source line follows a located header; then the caret gives the column; anything else is detail. */
    private void continued(String line) {
        current.lines++;
        if (current.column == 0 && !current.path.isEmpty() && CARET.matcher(line).matches()) {
            current.column = line.indexOf('^') + 1;
        } else if (current.lines > 1 || current.path.isEmpty()) {
            current.message.append('\n').append(line);
        }
    }

    private static Open open(String kind, String path, int line, String text) {
        Matcher lint = LINT.matcher(text);
        boolean keyed = "warning".equals(kind) && lint.matches();
        String rule = keyed ? "javac." + lint.group(1) : "javac." + kind;
        Severity severity = "error".equals(kind) ? Severity.ERROR : Severity.WARNING;
        return new Open(rule, severity, path, line, keyed ? lint.group(2) : text);
    }

    private void flush() throws IOException {
        if (current != null) add(current);
        current = null;
    }

    private void add(Open diagnostic) throws IOException {
        if (findings.size() >= AnalyzerXmlNormalizer.MAX_FINDINGS) {
            throw new IOException("original analyzer report exceeds finding limit");
        }
        try {
            findings.add(new Finding(diagnostic.rule, diagnostic.severity, diagnostic.path, diagnostic.line,
                    diagnostic.column, AnalyzerXmlNormalizer.normalizedMessage(diagnostic.message.toString())));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid javac diagnostic", invalid);
        }
    }
}
