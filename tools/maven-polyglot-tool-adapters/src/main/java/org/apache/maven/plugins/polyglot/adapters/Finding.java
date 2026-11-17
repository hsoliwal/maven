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
package org.apache.maven.plugins.polyglot.adapters;

import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One diagnostic of an analyzer or linter, as collected inside the tool's process: the file relative to the analysed
 * root ('/' separators) or an absolute URI outside it ({@code file:}, or {@code jar:}/{@code jrt:} for a class file
 * javac read), 1-based line and column (UTF-16 code units; 0 = unknown), level, rule id and message as SARIF carries
 * them, and the rule and message of the build-events line in the vocabulary of {@link EventVocabulary}.
 */
record Finding(String file, long line, long column, Level level, String rule, String message, String eventRule,
        String eventMessage) {
    /** Report order: file, line, column, rule, message; makes the reports byte-reproducible. */
    static final Comparator<Finding> ORDER = Comparator.comparing(Finding::file).thenComparingLong(Finding::line)
            .thenComparingLong(Finding::column).thenComparing(Finding::rule).thenComparing(Finding::message)
            .thenComparing(Finding::eventRule).thenComparing(Finding::eventMessage);
    /** An absolute URI as javac or a language server writes it: {@code jar:}, or a scheme, ':' and '/'. */
    private static final Pattern ABSOLUTE = Pattern.compile("^(?:jar:|[A-Za-z][A-Za-z0-9+.-]+:/).*");

    /** SARIF result levels and the matching build-events severities. */
    enum Level {
        ERROR("error", "ERROR"),
        WARNING("warning", "WARNING"),
        NOTE("note", "INFO");

        private final String sarif;
        private final String severity;

        Level(String sarif, String severity) {
            this.sarif = sarif;
            this.severity = severity;
        }

        String sarif() {
            return sarif;
        }

        String severity() {
            return severity;
        }
    }

    /** A finding whose build-events rule and message are its own (a language server's code and text). */
    Finding(String file, long line, long column, Level level, String rule, String message) {
        this(file, line, column, level, rule, message, rule == null ? "" : rule, message);
    }

    Finding {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(level, "level");
        rule = rule == null || rule.isBlank() ? "unnamed" : rule;
        message = message == null || message.isBlank() ? rule : message;
        line = Math.max(0, line);
        column = Math.max(0, column);
        eventRule = eventRule == null ? "" : eventRule;
        eventMessage = eventMessage == null || eventMessage.isBlank() ? message : eventMessage;
    }

    /** True when {@link #file()} is an absolute URI rather than a path relative to the analysed root. */
    boolean absolute() {
        return ABSOLUTE.matcher(file).matches();
    }
}
