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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Located diagnostics in build output: Maven compiler ({@code [ERROR] /p/File.java:[12,5] msg}, also without the
 * level as in a mojo failure message), Checkstyle console ({@code [WARN] File.java:3:1: msg [Rule]}), javac
 * ({@code File.java:12: error: msg}) and gcc/clang-style ({@code file:12:5: warning: msg}). A javac
 * {@code symbol:} or {@code location:} line continues the diagnostic above it. Mojo headers
 * ({@code --- compiler:3.13.0:compile (default-compile) @ a ---}) set the mojo and project of what follows.
 * Javac warnings get their {@code -Xlint} key as rule. Line formats ported from a build-log recipe (Apache-2.0).
 */
final class DiagnosticLines {
    static final String ERROR = "ERROR";
    static final String WARNING = "WARNING";
    static final String INFO = "INFO";

    /** One located diagnostic as read; {@code file} is the text of the output with '/' separators. */
    record Raw(String severity, String file, int line, int column, String rule, String message, String mojo,
            String artifactId) {
        Raw continued(String extra) {
            return new Raw(severity, file, line, column, rule, message + "; " + extra, mojo, artifactId);
        }
    }

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
    private static final Pattern LEVEL = Pattern.compile("^\\[(ERROR|WARNING|WARN|INFO|DEBUG)\\]\\s*");
    private static final Pattern HEADER =
            Pattern.compile("^\\[INFO\\] --- ([\\w.-]+):([^:\\s]+):([\\w.-]+) \\(([^)]+)\\) @ ([\\w.-]+) ---$");
    // A file never starts with '[' or a blank, so a level token or an indented detail line is never a file name.
    private static final Pattern MAVEN = Pattern.compile("^([^\\[\\s].*\\.\\w+):\\[(\\d{1,9}),(\\d{1,9})\\] (.+)$");
    private static final Pattern JAVAC = Pattern.compile("^([^\\[\\s].*\\.java):(\\d{1,9}): (error|warning): (.+)$");
    private static final Pattern GCC =
            Pattern.compile("^([^\\[\\s].*\\.\\w+):(\\d{1,9}):(\\d{1,9}): (error|warning|note): (.+)$");
    private static final Pattern CHECKSTYLE =
            Pattern.compile("^([^\\[\\s].*\\.\\w+):(\\d{1,9}):(?:(\\d{1,9}):)? (.+?)(?: \\[(\\w+)\\])?$");
    private static final Pattern CONTINUATION = Pattern.compile("^(symbol|location)\\s*:\\s*(.+)$");
    private static final Pattern DRIVE = Pattern.compile("^/[A-Za-z]:/.*");
    /** Javac warning message to -Xlint key, first match wins (javax.tools messages carry no key). */
    private static final List<Map.Entry<Pattern, String>> LINT = List.of(
            Map.entry(Pattern.compile("has been deprecated and marked for removal"), "removal"),
            Map.entry(Pattern.compile("has been deprecated"), "deprecation"),
            Map.entry(Pattern.compile("^found raw type"), "rawtypes"),
            Map.entry(Pattern.compile("^unchecked"), "unchecked"),
            Map.entry(Pattern.compile("^redundant cast"), "cast"),
            Map.entry(Pattern.compile("serializable class"), "serial"),
            Map.entry(Pattern.compile("possible 'this' escape"), "this-escape"),
            Map.entry(Pattern.compile("^non-varargs call"), "varargs"),
            Map.entry(Pattern.compile("^empty statement after if"), "empty"),
            Map.entry(Pattern.compile("fall-through"), "fallthrough"),
            Map.entry(Pattern.compile("^division by zero"), "divzero"),
            Map.entry(Pattern.compile("finally clause cannot complete normally"), "finally"));
    private static final Pattern LINT_PREFIX = Pattern.compile("^\\[([a-z-]+)\\]\\s*(.*)$");

    /** A line split into its level token (null without one) and the text after it. */
    private record Line(String level, String body) {}

    private final String fallbackSeverity;
    private final List<Raw> found = new ArrayList<>();
    private final List<Function<Line, Optional<Raw>>> formats =
            List.of(this::maven, this::javac, this::gcc, this::checkstyle);
    private String mojo;
    private String artifactId;
    private boolean open;

    /** A reader whose unlevelled diagnostics get {@code fallbackSeverity}, in the given mojo and project. */
    DiagnosticLines(String fallbackSeverity, String mojo, String artifactId) {
        this.fallbackSeverity = fallbackSeverity;
        this.mojo = mojo;
        this.artifactId = artifactId;
    }

    /** The diagnostics of a whole text, such as a mojo failure message. */
    static List<Raw> parse(String text, String fallbackSeverity, String mojo, String artifactId) {
        DiagnosticLines reader = new DiagnosticLines(fallbackSeverity, mojo, artifactId);
        text.lines().forEach(reader::accept);
        return reader.found();
    }

    /** Reads one line of output. */
    DiagnosticLines accept(String rawLine) {
        String line = ANSI.matcher(rawLine).replaceAll("").stripTrailing();
        Matcher header = HEADER.matcher(line);
        if (header.matches()) {
            mojo = header.group(1) + ":" + header.group(3);
            artifactId = header.group(5);
            open = false;
        } else if (!(open && continued(line))) {
            Line split = split(line);
            Optional<Raw> raw = "DEBUG".equals(split.level()) ? Optional.empty()
                    : formats.stream().map(format -> format.apply(split)).flatMap(Optional::stream).findFirst();
            raw.ifPresent(found::add);
            open = raw.isPresent();
        }
        return this;
    }

    private static Line split(String line) {
        Matcher level = LEVEL.matcher(line);
        return level.find() ? new Line(level.group(1), line.substring(level.end())) : new Line(null, line);
    }

    /** The severity of a line: its level token, else {@code fallback}. */
    private String severity(Line line, String fallback) {
        return line.level() == null ? fallback : severity(line.level());
    }

    List<Raw> found() {
        return List.copyOf(found);
    }

    private boolean continued(String line) {
        Matcher m = CONTINUATION.matcher(split(line).body().trim());
        if (!m.matches() || found.isEmpty()) {
            return false;
        }
        String extra = m.group(1) + ": " + m.group(2).trim().replaceAll("\\s+", " ");
        found.set(found.size() - 1, found.get(found.size() - 1).continued(extra));
        return true;
    }

    /** {@code [ERROR] /p/File.java:[12,5] msg}, or without the level in a failure message. */
    private Optional<Raw> maven(Line line) {
        Matcher m = MAVEN.matcher(line.body());
        return m.matches()
                ? Optional.of(raw(severity(line, fallbackSeverity), m.group(1), number(m.group(2)), number(m.group(3)),
                        "", m.group(4)))
                : Optional.empty();
    }

    /** {@code [WARN] File.java:3:1: msg [Rule]}; only with a level token, as Checkstyle prints it. */
    private Optional<Raw> checkstyle(Line line) {
        Matcher m = CHECKSTYLE.matcher(line.body());
        return line.level() != null && m.matches()
                ? Optional.of(raw(severity(line.level()), m.group(1), number(m.group(2)), number(m.group(3)),
                        Optional.ofNullable(m.group(5)).orElse(""), m.group(4)))
                : Optional.empty();
    }

    /** {@code File.java:12: error: msg}. */
    private Optional<Raw> javac(Line line) {
        Matcher m = JAVAC.matcher(line.body());
        return m.matches()
                ? Optional.of(raw(severity(m.group(3)), m.group(1), number(m.group(2)), 0, "", m.group(4)))
                : Optional.empty();
    }

    /** {@code file:12:5: warning: msg} (gcc, clang and tools that print like them). */
    private Optional<Raw> gcc(Line line) {
        Matcher m = GCC.matcher(line.body());
        return m.matches()
                ? Optional.of(raw(severity(m.group(4)), m.group(1), number(m.group(2)), number(m.group(3)), "",
                        m.group(5)))
                : Optional.empty();
    }

    private Raw raw(String severity, String file, int line, int column, String rule, String message) {
        Matcher prefixed = LINT_PREFIX.matcher(message);
        String text = prefixed.matches() ? prefixed.group(2) : message;
        String key = !rule.isEmpty() ? rule : prefixed.matches() ? prefixed.group(1) : lintKey(text);
        return new Raw(severity, path(file), line, column, key, text, mojo, artifactId);
    }

    /** The -Xlint key of a javac warning message, or "" when it is not one. */
    static String lintKey(String message) {
        return LINT.stream().filter(entry -> entry.getKey().matcher(message).find()).map(Map.Entry::getValue)
                .findFirst().orElse("");
    }

    /** ERROR, WARNING or INFO from a level token (WARN, warning, note...). */
    static String severity(String token) {
        String upper = token.toUpperCase(Locale.ROOT);
        if (upper.startsWith(ERROR)) {
            return ERROR;
        }
        return upper.startsWith("WARN") ? WARNING : upper.startsWith("NOTE") ? INFO : upper;
    }

    /** '/' separators; the leading '/' of a Windows {@code /C:/...} path (Maven compiler output) removed. */
    static String path(String file) {
        String text = file.trim().replace('\\', '/');
        return DRIVE.matcher(text).matches() ? text.substring(1) : text;
    }

    private static int number(String digits) {
        return digits == null ? 0 : Integer.parseInt(digits);
    }
}
